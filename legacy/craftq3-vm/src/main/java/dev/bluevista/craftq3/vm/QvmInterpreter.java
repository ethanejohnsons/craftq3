package dev.bluevista.craftq3.vm;

import static dev.bluevista.craftq3.vm.QvmException.Reason.*;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checked stack-machine execution, independently implemented in Java. All guest loads and stores
 * address owned VM bytes, never host pointers. A failed invocation requires reset before reuse.
 */
public final class QvmInterpreter {
  public record Limits(
      long instructionBudget,
      long syscallBudget,
      long maxExecutionNanos,
      int maxOperandStack,
      int maxCallDepth,
      int maxProgramStackBytes,
      int maxInvocationDepth) {
    public Limits {
      if (instructionBudget <= 0
          || syscallBudget <= 0
          || maxExecutionNanos < 0
          || maxOperandStack < 16
          || maxOperandStack > 65_536
          || maxCallDepth < 1
          || maxCallDepth > 4096
          || maxProgramStackBytes < 128
          || maxProgramStackBytes > 1024 * 1024
          || maxInvocationDepth < 1
          || maxInvocationDepth > 32)
        throw new IllegalArgumentException("Invalid QVM execution limits");
    }

    public static Limits defaults() {
      return new Limits(50_000_000, 200_000, 5_000_000_000L, 4096, 1024, 65536, 16);
    }
  }

  public record Stats(
      String module,
      long instructions,
      long syscalls,
      long invocations,
      int maxOperandDepth,
      int maxCallDepth,
      int programStack,
      int lastPc,
      boolean faulted,
      Map<Opcode, Long> opcodeCounts) {
    public Stats {
      opcodeCounts = Map.copyOf(opcodeCounts);
    }
  }

  public record TraceStep(
      int pc, Opcode opcode, int operand, int operandDepth, int programStack, long instructions) {}

  private final QvmModule module;
  private final QvmSyscalls syscalls;
  private final Limits limits;
  private final QvmMemory memory;
  private final int stackBottom;
  private final long[] opcodeCounts = new long[Opcode.values().length];
  private final ArrayDeque<TraceStep> trace = new ArrayDeque<>();
  private boolean tracing;
  private boolean faulted;
  private int programStack;
  private int invocationDepth;
  private int callDepth;
  private int maxOperandDepth;
  private int maxCallDepth;
  private int lastPc = -1;
  private long instructionCount;
  private long syscallCount;
  private long invocationCount;
  private long workRemaining;
  private long syscallsRemaining;
  private long invocationStarted;

  public QvmInterpreter(QvmModule module, QvmSyscalls syscalls) {
    this(module, syscalls, Limits.defaults());
  }

  public QvmInterpreter(QvmModule module, QvmSyscalls syscalls, Limits limits) {
    this.module = Objects.requireNonNull(module);
    this.syscalls = Objects.requireNonNull(syscalls);
    this.limits = Objects.requireNonNull(limits);
    memory = new QvmMemory(module.memorySize());
    stackBottom =
        Math.max(
            module.header().dataLength() + module.header().literalLength(),
            module.memorySize() - limits.maxProgramStackBytes());
    if (module.memorySize() - stackBottom < 128)
      throw new IllegalArgumentException("QVM has no usable program stack");
    reset();
  }

  public QvmModule module() {
    return module;
  }

  public QvmMemory memory() {
    return memory;
  }

  public synchronized void setTracing(boolean enabled) {
    tracing = enabled;
    if (!enabled) trace.clear();
  }

  public synchronized List<TraceStep> recentTrace() {
    return List.copyOf(trace);
  }

  public synchronized Stats stats() {
    var histogram = new EnumMap<Opcode, Long>(Opcode.class);
    for (Opcode opcode : Opcode.values())
      if (opcodeCounts[opcode.ordinal()] != 0)
        histogram.put(opcode, opcodeCounts[opcode.ordinal()]);
    return new Stats(
        module.name(),
        instructionCount,
        syscallCount,
        invocationCount,
        maxOperandDepth,
        maxCallDepth,
        programStack,
        lastPc,
        faulted,
        histogram);
  }

  /**
   * Restores initialized data, clears BSS/stack and counters, and clears a prior contained fault.
   */
  public synchronized void reset() {
    if (invocationDepth != 0) throw new IllegalStateException("Cannot reset an active QVM");
    memory.reset(module.initialData());
    programStack = memory.size();
    faulted = false;
    instructionCount = syscallCount = invocationCount = 0;
    callDepth = maxCallDepth = maxOperandDepth = 0;
    lastPc = -1;
    java.util.Arrays.fill(opcodeCounts, 0);
    trace.clear();
  }

  /** Enters vmMain(command,arg0..arg11). Synchronous host callbacks may reenter the same VM. */
  public synchronized int invoke(int command, int... arguments) {
    Objects.requireNonNull(arguments);
    if (arguments.length > 12)
      throw new IllegalArgumentException("vmMain accepts at most 12 arguments");
    if (faulted) throw fault(FAULTED, "Previous invocation faulted; reset is required");
    if (invocationDepth >= limits.maxInvocationDepth())
      throw fault(STACK, "Invocation nesting limit exceeded");
    if (invocationDepth == 0) {
      workRemaining = limits.instructionBudget();
      syscallsRemaining = limits.syscallBudget();
      invocationStarted = System.nanoTime();
    }
    int savedStack = programStack;
    int savedCalls = callDepth;
    invocationDepth++;
    invocationCount++;
    try {
      // Two reserved words, then command and twelve arguments. This is the Q3 vmMain frame ABI.
      int incoming = reserve(programStack, 60);
      memory.fill(incoming, 60, 0);
      memory.writeInt(incoming, -1);
      memory.writeInt(incoming + 8, command);
      for (int i = 0; i < arguments.length; i++)
        memory.writeInt(incoming + 12 + i * 4, arguments[i]);
      programStack = incoming;
      return new Execution(incoming).run();
    } catch (QvmException failure) {
      faulted = true;
      if (!failure.module().isEmpty()) throw failure;
      throw new QvmException(
          failure.reason(), module.name(), lastPc, failure.getMessage(), failure);
    } catch (RuntimeException failure) {
      faulted = true;
      throw new QvmException(
          HOST_FAILURE,
          module.name(),
          lastPc,
          "Execution failure: " + failure.getMessage(),
          failure);
    } finally {
      programStack = savedStack;
      callDepth = savedCalls;
      invocationDepth--;
    }
  }

  private record Frame(
      int entryPc, int base, int callerStack, int bytes, int returnPc, int operandBase) {}

  private final class Execution {
    private final int[] operands = new int[limits.maxOperandStack()];
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private int pc;
    private int depth;
    private int pendingReturn = -1;
    private int pendingStack;
    private int lastExecutedPc = -1;
    private boolean entering = true;

    Execution(int incomingStack) {
      pendingStack = incomingStack;
    }

    int run() {
      while (true) {
        target(pc);
        lastPc = pc;
        int predecessor = lastExecutedPc;
        lastExecutedPc = pc;
        var instruction = module.instructions().get(pc++);
        Opcode opcode = instruction.opcode();
        int value = instruction.operand();
        charge(1);
        instructionCount++;
        opcodeCounts[opcode.ordinal()]++;
        if (tracing) {
          if (trace.size() == 128) trace.removeFirst();
          trace.addLast(
              new TraceStep(lastPc, opcode, value, depth, programStack, instructionCount));
        }
        if (entering && opcode != Opcode.ENTER)
          throw fault(CONTROL_FLOW, "Call target does not begin a procedure");
        switch (opcode) {
          case UNDEF, BREAK ->
              throw fault(UNSUPPORTED_OPCODE, "Unsupported executed opcode " + opcode);
          case IGNORE -> {}
          case ENTER -> {
            if (!entering) throw fault(CONTROL_FLOW, "Fell into ENTER without CALL");
            if (++callDepth > limits.maxCallDepth())
              throw fault(STACK, "Call depth limit exceeded");
            int base = reserve(programStack, value);
            frames.push(new Frame(lastPc, base, pendingStack, value, pendingReturn, depth));
            programStack = base;
            entering = false;
            maxCallDepth = Math.max(maxCallDepth, callDepth);
          }
          case LEAVE -> {
            Frame frame = frame();
            if (frame.bytes() != value || programStack != frame.base())
              throw fault(STACK, "Mismatched procedure frame on LEAVE");
            if (depth != frame.operandBase() + 1)
              throw fault(STACK, "Procedure must return exactly one operand");
            if (memory.readInt(frame.callerStack()) != frame.returnPc())
              throw fault(CONTROL_FLOW, "Guest modified a saved return address");
            frames.pop();
            callDepth--;
            programStack = frame.callerStack();
            if (frames.isEmpty()) {
              if (frame.returnPc() != -1)
                throw fault(CONTROL_FLOW, "Invalid vmMain return sentinel");
              return operands[depth - 1];
            }
            pc = frame.returnPc();
          }
          case CALL -> {
            int destination = pop();
            if (destination < 0) {
              if (--syscallsRemaining < 0) throw fault(SYSCALL_BUDGET, "Syscall budget exhausted");
              syscallCount++;
              int syscall = -(destination + 1);
              memory.writeInt(programStack + 4, syscall);
              int[] arguments = new int[15];
              for (int i = 0; i < arguments.length; i++) {
                long address = (long) programStack + 8 + i * 4;
                arguments[i] = address + 4 <= memory.size() ? memory.readInt((int) address) : 0;
              }
              int callPc = lastPc;
              try {
                checkCancellation();
                int result = syscalls.invoke(memory, syscall, arguments);
                checkCancellation();
                if (faulted) throw fault(FAULTED, "Nested invocation failed");
                push(result);
              } catch (QvmException failure) {
                throw failure;
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new QvmException(
                    INTERRUPTED, module.name(), callPc, "Host syscall interrupted", failure);
              } catch (Exception failure) {
                throw new QvmException(
                    HOST_FAILURE,
                    module.name(),
                    callPc,
                    "Syscall " + syscall + " failed: " + failure.getMessage(),
                    failure);
              } finally {
                lastPc = callPc;
              }
            } else {
              target(destination);
              if (module.instructions().get(destination).opcode() != Opcode.ENTER)
                throw fault(CONTROL_FLOW, "CALL target is not an ENTER instruction");
              memory.writeInt(programStack, pc);
              pendingReturn = pc;
              pendingStack = programStack;
              entering = true;
              pc = destination;
            }
          }
          case PUSH -> push(0);
          case POP -> pop();
          case CONST -> push(value);
          case LOCAL -> {
            long address = (long) programStack + value;
            if (address > memory.size())
              throw fault(MEMORY_BOUNDS, "Local address exceeds VM memory");
            push((int) address);
          }
          case JUMP -> {
            int destination = pop();
            branch(destination);
            if (module.header().version() == 2
                && (lastPc == 0
                    || predecessor != lastPc - 1
                    || module.instructions().get(lastPc - 1).opcode() != Opcode.CONST
                    || module.instructions().get(lastPc - 1).operand() != destination)
                && !module.jumpTarget(destination))
              throw fault(CONTROL_FLOW, "Indirect target missing from v2 jump table");
            pc = destination;
          }
          case EQ, NE, LTI, LEI, GTI, GEI, LTU, LEU, GTU, GEU, EQF, NEF, LTF, LEF, GTF, GEF -> {
            int right = pop(), left = pop();
            if (compare(opcode, left, right)) {
              branch(value);
              pc = value;
            }
          }
          case LOAD1 -> push(memory.readUnsignedByte(address(pop(), 1)));
          case LOAD2 -> push(memory.readUnsignedShort(address(pop(), 2)));
          case LOAD4 -> push(memory.readInt(address(pop(), 4)));
          case STORE1, STORE2, STORE4 -> {
            int stored = pop(), destination = pop();
            if (opcode == Opcode.STORE1) memory.writeByte(address(destination, 1), stored);
            else if (opcode == Opcode.STORE2) memory.writeShort(address(destination, 2), stored);
            else memory.writeInt(address(destination, 4), stored);
          }
          case ARG -> {
            Frame frame = frame();
            if (value > frame.bytes() - 4)
              throw fault(STACK, "ARG writes outside outgoing argument area");
            memory.writeInt(programStack + value, pop());
          }
          case BLOCK_COPY -> {
            int source = pop(), destination = pop();
            charge((value + 3L) / 4);
            memory.copy(address(destination, value), address(source, value), value);
          }
          case SEX8 -> push((byte) pop());
          case SEX16 -> push((short) pop());
          case NEGI -> push(-pop());
          case BCOM -> push(~pop());
          case NEGF -> push(pop() ^ 0x80000000);
          case CVIF -> push(Float.floatToIntBits((float) pop()));
          case CVFI -> {
            float f = Float.intBitsToFloat(pop());
            push(
                !Float.isFinite(f) || f >= 0x1.0p31f || f < -0x1.0p31f
                    ? Integer.MIN_VALUE
                    : (int) f);
          }
          case ADD,
              SUB,
              DIVI,
              DIVU,
              MODI,
              MODU,
              MULI,
              MULU,
              BAND,
              BOR,
              BXOR,
              LSH,
              RSHI,
              RSHU,
              ADDF,
              SUBF,
              DIVF,
              MULF -> {
            int right = pop(), left = pop();
            push(binary(opcode, left, right));
          }
        }
      }
    }

    private Frame frame() {
      if (frames.isEmpty()) throw fault(STACK, "No active procedure frame");
      return frames.peek();
    }

    private int pop() {
      if (depth <= frame().operandBase()) throw fault(STACK, "Operand stack underflow");
      return operands[--depth];
    }

    private void push(int value) {
      if (depth == operands.length) throw fault(STACK, "Operand stack overflow");
      operands[depth++] = value;
      maxOperandDepth = Math.max(maxOperandDepth, depth);
    }

    private void target(int destination) {
      if (destination < 0 || destination >= module.instructions().size())
        throw fault(CONTROL_FLOW, "Instruction target outside code: " + destination);
    }

    private void branch(int destination) {
      target(destination);
      if (module.functionAt(destination) != frame().entryPc()
          || module.instructions().get(destination).opcode() == Opcode.ENTER)
        throw fault(CONTROL_FLOW, "Jump crosses a procedure boundary");
    }
  }

  /** Q3 wraps the starting guest address into its power-of-two data region; ranges never wrap. */
  private int address(int guestAddress, int length) {
    int address = guestAddress & (memory.size() - 1);
    memory.checkRange(address, length);
    return address;
  }

  private int reserve(int top, int bytes) {
    long base = (long) top - bytes;
    if (base < stackBottom || base > memory.size() - 8 || (base & 3) != 0)
      throw fault(STACK, "Program stack exhausted");
    return (int) base;
  }

  private void charge(long cost) {
    workRemaining -= cost;
    if (workRemaining < 0) throw fault(INSTRUCTION_BUDGET, "Instruction/work budget exhausted");
    if ((instructionCount & 1023) == 0) {
      checkCancellation();
    }
  }

  private void checkCancellation() {
    if (Thread.currentThread().isInterrupted()) throw fault(INTERRUPTED, "Execution interrupted");
    if (limits.maxExecutionNanos() > 0
        && System.nanoTime() - invocationStarted > limits.maxExecutionNanos())
      throw fault(DEADLINE, "Execution deadline exceeded");
  }

  private QvmException fault(QvmException.Reason reason, String message) {
    return new QvmException(reason, module.name(), lastPc, message, null);
  }

  private int binary(Opcode opcode, int left, int right) {
    if ((opcode == Opcode.DIVI
            || opcode == Opcode.DIVU
            || opcode == Opcode.MODI
            || opcode == Opcode.MODU)
        && right == 0) throw fault(ARITHMETIC, "Integer division by zero");
    return switch (opcode) {
      case ADD -> left + right;
      case SUB -> left - right;
      case MULI, MULU -> left * right;
      case DIVI -> left / right;
      case DIVU -> Integer.divideUnsigned(left, right);
      case MODI -> left % right;
      case MODU -> Integer.remainderUnsigned(left, right);
      case BAND -> left & right;
      case BOR -> left | right;
      case BXOR -> left ^ right;
      case LSH -> left << (right & 31);
      case RSHI -> left >> (right & 31);
      case RSHU -> left >>> (right & 31);
      case ADDF -> Float.floatToIntBits(Float.intBitsToFloat(left) + Float.intBitsToFloat(right));
      case SUBF -> Float.floatToIntBits(Float.intBitsToFloat(left) - Float.intBitsToFloat(right));
      case DIVF -> Float.floatToIntBits(Float.intBitsToFloat(left) / Float.intBitsToFloat(right));
      case MULF -> Float.floatToIntBits(Float.intBitsToFloat(left) * Float.intBitsToFloat(right));
      default -> throw fault(UNSUPPORTED_OPCODE, "Not a binary opcode: " + opcode);
    };
  }

  private static boolean compare(Opcode opcode, int left, int right) {
    return switch (opcode) {
      case EQ -> left == right;
      case NE -> left != right;
      case LTI -> left < right;
      case LEI -> left <= right;
      case GTI -> left > right;
      case GEI -> left >= right;
      case LTU -> Integer.compareUnsigned(left, right) < 0;
      case LEU -> Integer.compareUnsigned(left, right) <= 0;
      case GTU -> Integer.compareUnsigned(left, right) > 0;
      case GEU -> Integer.compareUnsigned(left, right) >= 0;
      case EQF -> Float.intBitsToFloat(left) == Float.intBitsToFloat(right);
      case NEF -> Float.intBitsToFloat(left) != Float.intBitsToFloat(right);
      case LTF -> Float.intBitsToFloat(left) < Float.intBitsToFloat(right);
      case LEF -> Float.intBitsToFloat(left) <= Float.intBitsToFloat(right);
      case GTF -> Float.intBitsToFloat(left) > Float.intBitsToFloat(right);
      case GEF -> Float.intBitsToFloat(left) >= Float.intBitsToFloat(right);
      default -> throw new IllegalArgumentException("Not a comparison opcode");
    };
  }
}
