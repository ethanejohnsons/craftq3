package dev.bluevista.craftq3.engine.vm;

import static dev.bluevista.craftq3.engine.vm.QvmException.NO_SYSCALL;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.ARITHMETIC;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.CONTROL_FLOW;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.DEADLINE;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.FAULTED;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.HOST_FAILURE;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.INSTRUCTION_BUDGET;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.INTERRUPTED;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.MEMORY_BOUNDS;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.STACK;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.SYSCALL_BUDGET;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.UNSUPPORTED_OPCODE;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.Clock;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;

/**
 * One running VM: its data segment and interpreter state over a shared {@link QvmImage}. Used by a
 * single owner thread ({@link #owner()}). Syscall handlers may re-enter {@link #vmMain}
 * synchronously; a fault leaves the instance faulted until {@link #reset()}.
 *
 * <p>Execution checks every guest access against the data segment, keeps procedure frames intact
 * (frame sizes, saved return addresses, one returned operand) and confines jumps to the current
 * procedure. Instruction budgets are charged per straight-line run at each control transfer, not
 * per instruction, so a budget may be exceeded by at most one run before it faults.
 */
public final class QvmInstance {
  /** Execution limits, applied per outermost {@link #vmMain} call (nested calls share them). */
  public record Limits(
      long instructionBudget,
      long syscallBudget,
      long maxExecutionMillis,
      int operandStackSize,
      int maxCallDepth,
      int maxInvocationDepth) {
    public Limits {
      if (instructionBudget <= 0
          || instructionBudget > Long.MAX_VALUE / 4
          || syscallBudget <= 0
          || syscallBudget > Long.MAX_VALUE / 4
          || maxExecutionMillis < 0
          || operandStackSize < 16
          || operandStackSize > 1 << 20
          || maxCallDepth < 1
          || maxCallDepth > 1 << 16
          || maxInvocationDepth < 1
          || maxInvocationDepth > 64) throw new IllegalArgumentException("Invalid QVM limits");
    }

    public static Limits defaults() {
      return new Limits(50_000_000L, 200_000L, 5_000L, 4096, 1024, 16);
    }
  }

  /** Counters since construction or the last {@link #reset()}. */
  public record Stats(
      long instructions,
      long syscalls,
      long invocations,
      int maxCallDepth,
      int programStack,
      int lastPc,
      boolean faulted) {}

  /** vmMain accepts a command and at most this many arguments. */
  public static final int MAX_VMMAIN_ARGS = 12;

  /** Return sentinel, syscall slot, command and {@link #MAX_VMMAIN_ARGS} argument words. */
  private static final int VMMAIN_FRAME_BYTES = 12 + 4 * MAX_VMMAIN_ARGS;

  private static final int VMMAIN_RETURN = -1;
  private static final int CANCEL_CHECK_INTERVAL = 1 << 16;
  private static final int FRAME_WORDS = 3;
  private static final VarHandle INT = QvmMemory.INT;
  private static final VarHandle SHORT = QvmMemory.SHORT;
  private static final Opcode[] OPCODES = Opcode.values();

  private final QvmImage image;
  private final Syscalls handler;
  private final Limits limits;
  private final Clock clock;
  private final QvmMemory memory;
  private final ThreadOwner owner;
  private final int[] operandStack;
  private final int[] frames;
  private final int[][] argumentBuffers;
  private VmTracer tracer;

  // Interpreter state shared with nested invocations; the dispatch loop works on locals and
  // publishes them here only around syscalls.
  private int programStack;
  private int operandTop;
  private int callDepth;
  private int invocationDepth;
  private boolean faulted;

  private long instructions;
  private long syscalls;
  private long invocations;
  private int maxCallDepth;
  private int lastPc = -1;

  private long budgetEnd;
  private long syscallEnd;
  private long deadline;
  private long checkpoint;

  public QvmInstance(QvmImage image, Syscalls syscalls) {
    this(image, syscalls, Limits.defaults(), Clock.system());
  }

  public QvmInstance(QvmImage image, Syscalls syscalls, Limits limits, Clock clock) {
    this.image = Objects.requireNonNull(image);
    this.handler = Objects.requireNonNull(syscalls);
    this.limits = Objects.requireNonNull(limits);
    this.clock = Objects.requireNonNull(clock);
    memory = new QvmMemory(image.memorySize());
    owner = new ThreadOwner("QVM " + image.name());
    operandStack = new int[limits.operandStackSize()];
    frames = new int[FRAME_WORDS * limits.maxCallDepth()];
    argumentBuffers = new int[limits.maxInvocationDepth()][];
    reset();
  }

  public QvmImage image() {
    return image;
  }

  public QvmMemory memory() {
    return memory;
  }

  public Limits limits() {
    return limits;
  }

  /** The owning thread; claim it from the thread that will run this VM. */
  public ThreadOwner owner() {
    return owner;
  }

  /** The current program stack pointer (the top of the data segment when idle). */
  public int programStack() {
    return programStack;
  }

  public boolean faulted() {
    return faulted;
  }

  /** Installs a tracer for subsequent invocations, or removes it with {@code null}. */
  public void setTracer(VmTracer tracer) {
    this.tracer = tracer;
  }

  public Stats stats() {
    return new Stats(
        instructions, syscalls, invocations, maxCallDepth, programStack, lastPc, faulted);
  }

  /** Restores initialized data, zeroes BSS and the stack, clears counters and any prior fault. */
  public void reset() {
    owner.check();
    if (invocationDepth != 0) throw new IllegalStateException("Cannot reset an active QVM");
    memory.reset(image.initialData);
    programStack = memory.size();
    operandTop = 0;
    callDepth = 0;
    faulted = false;
    instructions = syscalls = invocations = 0;
    maxCallDepth = 0;
    lastPc = -1;
  }

  public int vmMain(int command) {
    return invoke(command, null, 0, 0, 0, 0);
  }

  public int vmMain(int command, int arg0) {
    return invoke(command, null, 1, arg0, 0, 0);
  }

  public int vmMain(int command, int arg0, int arg1) {
    return invoke(command, null, 2, arg0, arg1, 0);
  }

  public int vmMain(int command, int arg0, int arg1, int arg2) {
    return invoke(command, null, 3, arg0, arg1, arg2);
  }

  /** Calls {@code vmMain(command, args...)} with at most {@link #MAX_VMMAIN_ARGS} arguments. */
  public int vmMain(int command, int... args) {
    if (args.length > MAX_VMMAIN_ARGS)
      throw new IllegalArgumentException("vmMain accepts at most " + MAX_VMMAIN_ARGS + " args");
    return invoke(command, args, args.length, 0, 0, 0);
  }

  private int invoke(int command, int[] args, int argc, int arg0, int arg1, int arg2) {
    owner.check();
    if (faulted) throw fault(FAULTED, -1, "Previous invocation faulted; reset is required");
    if (invocationDepth >= limits.maxInvocationDepth())
      throw fault(STACK, lastPc, "Invocation nesting limit exceeded");
    boolean outermost = invocationDepth == 0;
    int savedStack = programStack, savedTop = operandTop, savedDepth = callDepth;
    invocationDepth++;
    invocations++;
    try {
      if (outermost) startBudget();
      int frame = programStack - VMMAIN_FRAME_BYTES;
      if (frame < image.stackBottom())
        throw fault(STACK, lastPc, "Program stack exhausted entering vmMain");
      byte[] data = memory.data;
      Arrays.fill(data, frame, frame + VMMAIN_FRAME_BYTES, (byte) 0);
      INT.set(data, frame, VMMAIN_RETURN);
      INT.set(data, frame + 8, command);
      for (int i = 0; i < argc; i++) {
        int value = args != null ? args[i] : i == 0 ? arg0 : i == 1 ? arg1 : arg2;
        INT.set(data, frame + 12 + 4 * i, value);
      }
      return execute(frame);
    } catch (QvmException failure) {
      faulted = true;
      throw failure;
    } catch (RuntimeException failure) {
      faulted = true;
      throw new QvmException(
          HOST_FAILURE,
          image.name(),
          lastPc,
          opcodeAt(lastPc),
          NO_SYSCALL,
          "Execution failure: " + failure,
          failure);
    } finally {
      programStack = savedStack;
      operandTop = savedTop;
      callDepth = savedDepth;
      invocationDepth--;
    }
  }

  /**
   * The dispatch loop for one invocation, starting at the procedure at instruction 0 with its
   * incoming frame at {@code frame}. The current procedure lives in locals ({@code entry}, {@code
   * ret}, {@code base}, {@code frameSize}); callers are saved as triples in {@link #frames}.
   */
  private int execute(int frame) {
    final int[] ops = image.opcodes;
    final int[] imm = image.operands;
    final int[] procedures = image.procedures;
    final byte[] flags = image.flags;
    final boolean jumpTable = image.version() == 2;
    final int count = image.instructionCount();
    final byte[] mem = memory.data;
    final int mask = mem.length - 1;
    final int size = mem.length;
    final int stackBottom = image.stackBottom();
    final int[] stack = operandStack;
    final int[] saved = frames;
    final int maxDepth = limits.maxCallDepth();
    final VmTracer tracer = this.tracer;
    final int rootDepth = callDepth;

    int depth = rootDepth;
    int sp = operandTop;
    if (depth + 1 > maxDepth) throw fault(STACK, 0, "Call depth limit exceeded");
    int entry = 0;
    int ret = VMMAIN_RETURN;
    int base = sp;
    int frameSize = imm[0];
    int ps = frame - frameSize;
    if (ps < stackBottom) throw fault(STACK, 0, "Program stack exhausted");
    if (depth + 1 > maxCallDepth) maxCallDepth = depth + 1;
    if (tracer != null) tracer.instruction(0, Opcode.ENTER, frameSize, sp, ps);
    int pc = 1;
    int runStart = 0;
    try {
      for (; ; ) {
        final int at = pc;
        final int op = ops[at];
        if (tracer != null && op != Op.END) tracer.instruction(at, OPCODES[op], imm[at], sp, ps);
        pc = at + 1;
        switch (op) {
          case Op.UNDEF, Op.BREAK ->
              throw fault(UNSUPPORTED_OPCODE, at, "Unsupported executed opcode " + OPCODES[op]);
          case Op.IGNORE -> {}
          case Op.ENTER -> throw fault(CONTROL_FLOW, at, "Fell into ENTER without CALL");
          case Op.LEAVE -> {
            if (imm[at] != frameSize) throw fault(STACK, at, "Mismatched procedure frame on LEAVE");
            if (sp != base + 1) throw fault(STACK, at, "Procedure must return exactly one operand");
            int caller = ps + frameSize;
            if ((int) INT.get(mem, caller) != ret)
              throw fault(CONTROL_FLOW, at, "Guest modified a saved return address");
            charge(pc - runStart, at);
            if (depth == rootDepth) {
              lastPc = at;
              return stack[sp - 1];
            }
            ps = caller;
            pc = ret;
            runStart = ret;
            depth--;
            int slot = depth * FRAME_WORDS;
            entry = saved[slot];
            ret = saved[slot + 1];
            base = saved[slot + 2];
            frameSize = imm[entry];
          }
          case Op.CALL -> {
            int target = stack[--sp];
            if (target < 0) {
              charge(pc - runStart, at);
              runStart = pc;
              programStack = ps;
              operandTop = sp;
              callDepth = depth + 1;
              int result = syscall(at, -1 - target, ps);
              stack[sp++] = result;
            } else {
              if (target >= count || ops[target] != Op.ENTER)
                throw fault(CONTROL_FLOW, at, "CALL target " + target + " is not a procedure");
              int calleeSize = imm[target];
              int calleeStack = ps - calleeSize;
              if (calleeStack < stackBottom) throw fault(STACK, at, "Program stack exhausted");
              if (depth + 2 > maxDepth) throw fault(STACK, at, "Call depth limit exceeded");
              INT.set(mem, ps, pc);
              int slot = depth * FRAME_WORDS;
              saved[slot] = entry;
              saved[slot + 1] = ret;
              saved[slot + 2] = base;
              depth++;
              if (depth + 1 > maxCallDepth) maxCallDepth = depth + 1;
              entry = target;
              ret = pc;
              base = sp;
              frameSize = calleeSize;
              ps = calleeStack;
              if (tracer != null) tracer.instruction(target, Opcode.ENTER, calleeSize, sp, ps);
              charge(pc - runStart + 1, at);
              pc = target + 1;
              runStart = pc;
            }
          }
          case Op.PUSH -> stack[sp++] = 0;
          case Op.POP -> {
            if (--sp < base) throw fault(STACK, at, "Operand stack underflow");
          }
          case Op.CONST -> stack[sp++] = imm[at];
          case Op.LOCAL -> {
            int address = ps + imm[at];
            if (address > size) throw fault(MEMORY_BOUNDS, at, "Local address exceeds VM memory");
            stack[sp++] = address;
          }
          case Op.JUMP -> {
            int target = stack[--sp];
            if (target < 0 || target >= count || procedures[target] != entry || target == entry)
              throw fault(CONTROL_FLOW, at, "Jump to " + target + " leaves the procedure");
            if (jumpTable
                && (flags[at] & QvmImage.FLAG_DIRECT_JUMP) == 0
                && (flags[target] & QvmImage.FLAG_JUMP_TABLE_TARGET) == 0)
              throw fault(CONTROL_FLOW, at, "Indirect target " + target + " not in v2 jump table");
            charge(pc - runStart, at);
            pc = target;
            runStart = target;
          }
          case Op.EQ -> {
            int r = stack[--sp], l = stack[--sp];
            if (l == r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.NE -> {
            int r = stack[--sp], l = stack[--sp];
            if (l != r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.LTI -> {
            int r = stack[--sp], l = stack[--sp];
            if (l < r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.LEI -> {
            int r = stack[--sp], l = stack[--sp];
            if (l <= r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.GTI -> {
            int r = stack[--sp], l = stack[--sp];
            if (l > r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.GEI -> {
            int r = stack[--sp], l = stack[--sp];
            if (l >= r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.LTU -> {
            int r = stack[--sp], l = stack[--sp];
            if (Integer.compareUnsigned(l, r) < 0) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.LEU -> {
            int r = stack[--sp], l = stack[--sp];
            if (Integer.compareUnsigned(l, r) <= 0) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.GTU -> {
            int r = stack[--sp], l = stack[--sp];
            if (Integer.compareUnsigned(l, r) > 0) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.GEU -> {
            int r = stack[--sp], l = stack[--sp];
            if (Integer.compareUnsigned(l, r) >= 0) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.EQF -> {
            float r = Float.intBitsToFloat(stack[--sp]), l = Float.intBitsToFloat(stack[--sp]);
            if (l == r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.NEF -> {
            float r = Float.intBitsToFloat(stack[--sp]), l = Float.intBitsToFloat(stack[--sp]);
            if (l != r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.LTF -> {
            float r = Float.intBitsToFloat(stack[--sp]), l = Float.intBitsToFloat(stack[--sp]);
            if (l < r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.LEF -> {
            float r = Float.intBitsToFloat(stack[--sp]), l = Float.intBitsToFloat(stack[--sp]);
            if (l <= r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.GTF -> {
            float r = Float.intBitsToFloat(stack[--sp]), l = Float.intBitsToFloat(stack[--sp]);
            if (l > r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.GEF -> {
            float r = Float.intBitsToFloat(stack[--sp]), l = Float.intBitsToFloat(stack[--sp]);
            if (l >= r) {
              charge(pc - runStart, at);
              pc = runStart = imm[at];
            }
          }
          case Op.LOAD1 -> stack[sp - 1] = mem[stack[sp - 1] & mask] & 0xff;
          case Op.LOAD2 -> {
            int address = stack[sp - 1] & mask;
            if (address > size - 2) throw memoryFault(at, address, 2);
            stack[sp - 1] = (short) SHORT.get(mem, address) & 0xffff;
          }
          case Op.LOAD4 -> {
            int address = stack[sp - 1] & mask;
            if (address > size - 4) throw memoryFault(at, address, 4);
            stack[sp - 1] = (int) INT.get(mem, address);
          }
          case Op.STORE1 -> {
            int value = stack[--sp];
            mem[stack[--sp] & mask] = (byte) value;
          }
          case Op.STORE2 -> {
            int value = stack[--sp];
            int address = stack[--sp] & mask;
            if (address > size - 2) throw memoryFault(at, address, 2);
            SHORT.set(mem, address, (short) value);
          }
          case Op.STORE4 -> {
            int value = stack[--sp];
            int address = stack[--sp] & mask;
            if (address > size - 4) throw memoryFault(at, address, 4);
            INT.set(mem, address, value);
          }
          case Op.ARG -> {
            int offset = imm[at];
            if (offset > frameSize - 4)
              throw fault(STACK, at, "ARG writes outside outgoing argument area");
            INT.set(mem, ps + offset, stack[--sp]);
          }
          case Op.BLOCK_COPY -> {
            int length = imm[at];
            int source = stack[--sp] & mask;
            int destination = stack[--sp] & mask;
            charge(pc - runStart + (length + 3L) / 4, at);
            runStart = pc;
            if (length > size - source) throw memoryFault(at, source, length);
            if (length > size - destination) throw memoryFault(at, destination, length);
            System.arraycopy(mem, source, mem, destination, length);
          }
          case Op.SEX8 -> stack[sp - 1] = (byte) stack[sp - 1];
          case Op.SEX16 -> stack[sp - 1] = (short) stack[sp - 1];
          case Op.NEGI -> stack[sp - 1] = -stack[sp - 1];
          case Op.ADD -> {
            int r = stack[--sp];
            stack[sp - 1] += r;
          }
          case Op.SUB -> {
            int r = stack[--sp];
            stack[sp - 1] -= r;
          }
          case Op.DIVI -> {
            int r = stack[--sp];
            if (r == 0) throw fault(ARITHMETIC, at, "Integer division by zero");
            stack[sp - 1] /= r;
          }
          case Op.DIVU -> {
            int r = stack[--sp];
            if (r == 0) throw fault(ARITHMETIC, at, "Integer division by zero");
            stack[sp - 1] = Integer.divideUnsigned(stack[sp - 1], r);
          }
          case Op.MODI -> {
            int r = stack[--sp];
            if (r == 0) throw fault(ARITHMETIC, at, "Integer division by zero");
            stack[sp - 1] %= r;
          }
          case Op.MODU -> {
            int r = stack[--sp];
            if (r == 0) throw fault(ARITHMETIC, at, "Integer division by zero");
            stack[sp - 1] = Integer.remainderUnsigned(stack[sp - 1], r);
          }
          case Op.MULI, Op.MULU -> {
            int r = stack[--sp];
            stack[sp - 1] *= r;
          }
          case Op.BAND -> {
            int r = stack[--sp];
            stack[sp - 1] &= r;
          }
          case Op.BOR -> {
            int r = stack[--sp];
            stack[sp - 1] |= r;
          }
          case Op.BXOR -> {
            int r = stack[--sp];
            stack[sp - 1] ^= r;
          }
          case Op.BCOM -> stack[sp - 1] = ~stack[sp - 1];
          // Shift counts use the low five bits, as x86 does (Java's shifts do the same).
          case Op.LSH -> {
            int r = stack[--sp];
            stack[sp - 1] <<= r;
          }
          case Op.RSHI -> {
            int r = stack[--sp];
            stack[sp - 1] >>= r;
          }
          case Op.RSHU -> {
            int r = stack[--sp];
            stack[sp - 1] >>>= r;
          }
          case Op.NEGF -> stack[sp - 1] ^= 0x80000000;
          case Op.ADDF -> {
            float r = Float.intBitsToFloat(stack[--sp]);
            stack[sp - 1] = Float.floatToIntBits(Float.intBitsToFloat(stack[sp - 1]) + r);
          }
          case Op.SUBF -> {
            float r = Float.intBitsToFloat(stack[--sp]);
            stack[sp - 1] = Float.floatToIntBits(Float.intBitsToFloat(stack[sp - 1]) - r);
          }
          case Op.DIVF -> {
            float r = Float.intBitsToFloat(stack[--sp]);
            stack[sp - 1] = Float.floatToIntBits(Float.intBitsToFloat(stack[sp - 1]) / r);
          }
          case Op.MULF -> {
            float r = Float.intBitsToFloat(stack[--sp]);
            stack[sp - 1] = Float.floatToIntBits(Float.intBitsToFloat(stack[sp - 1]) * r);
          }
          case Op.CVIF -> stack[sp - 1] = Float.floatToRawIntBits((float) stack[sp - 1]);
          case Op.CVFI -> stack[sp - 1] = truncate(Float.intBitsToFloat(stack[sp - 1]));
          case Op.END ->
              throw fault(CONTROL_FLOW, at - 1, "Execution ran past the last instruction");
          default -> throw fault(UNSUPPORTED_OPCODE, at, "Unknown opcode " + op);
        }
      }
    } catch (ArrayIndexOutOfBoundsException overrun) {
      // Code, frame and buffer indices are validated, so only the operand stack can overrun.
      throw fault(
          STACK,
          pc - 1,
          NO_SYSCALL,
          sp < 0 ? "Operand stack underflow" : "Operand stack overflow",
          overrun);
    }
  }

  /** x86 {@code cvttss2si}: NaN and out-of-range values become {@code 0x80000000}. */
  private static int truncate(float value) {
    return !Float.isFinite(value) || value >= 0x1.0p31f || value < -0x1.0p31f
        ? Integer.MIN_VALUE
        : (int) value;
  }

  private int syscall(int pc, int number, int ps) {
    if (syscalls >= syscallEnd)
      throw fault(SYSCALL_BUDGET, pc, number, "Syscall budget exhausted", null);
    syscalls++;
    byte[] data = memory.data;
    INT.set(data, ps + 4, number);
    int[] args = argumentBuffers[invocationDepth - 1];
    if (args == null) args = argumentBuffers[invocationDepth - 1] = new int[Syscalls.MAX_ARGS];
    int limit = data.length - 4;
    for (int i = 0, address = ps + 8; i < Syscalls.MAX_ARGS; i++, address += 4)
      args[i] = address <= limit ? (int) INT.get(data, address) : 0;
    lastPc = pc;
    int result;
    try {
      result = handler.call(this, number, args);
    } catch (QvmException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw fault(
          HOST_FAILURE,
          pc,
          number,
          "Syscall " + number + " failed: " + failure.getMessage(),
          failure);
    }
    lastPc = pc;
    if (faulted) throw fault(FAULTED, pc, number, "Nested invocation failed", null);
    checkCancellation(pc, number);
    return result;
  }

  private void startBudget() {
    budgetEnd = instructions + limits.instructionBudget();
    syscallEnd = syscalls + limits.syscallBudget();
    deadline =
        limits.maxExecutionMillis() > 0
            ? clock.millis() + limits.maxExecutionMillis()
            : Long.MAX_VALUE;
    checkCancellation(lastPc, NO_SYSCALL);
    checkpoint = Math.min(budgetEnd + 1, instructions + CANCEL_CHECK_INTERVAL);
  }

  /** Adds executed instructions; the slow path runs only at budget or cancellation checkpoints. */
  private void charge(long executed, int pc) {
    if ((instructions += executed) >= checkpoint) checkpointReached(pc);
  }

  private void checkpointReached(int pc) {
    if (instructions > budgetEnd)
      throw fault(
          INSTRUCTION_BUDGET,
          pc,
          "Instruction budget of " + limits.instructionBudget() + " exhausted");
    checkCancellation(pc, NO_SYSCALL);
    checkpoint = Math.min(budgetEnd + 1, instructions + CANCEL_CHECK_INTERVAL);
  }

  private void checkCancellation(int pc, int syscall) {
    if (Thread.currentThread().isInterrupted())
      throw fault(INTERRUPTED, pc, syscall, "Execution interrupted", null);
    if (deadline != Long.MAX_VALUE && clock.millis() > deadline)
      throw fault(
          DEADLINE,
          pc,
          syscall,
          "Execution deadline of " + limits.maxExecutionMillis() + " ms exceeded",
          null);
  }

  private QvmException memoryFault(int pc, int address, int length) {
    return fault(
        MEMORY_BOUNDS,
        pc,
        length
            + "-byte access at "
            + address
            + " exceeds the "
            + memory.size()
            + "-byte data segment");
  }

  private QvmException fault(QvmException.Reason reason, int pc, String message) {
    return fault(reason, pc, NO_SYSCALL, message, null);
  }

  private QvmException fault(
      QvmException.Reason reason, int pc, int syscall, String message, Throwable cause) {
    if (pc >= 0) lastPc = pc;
    return new QvmException(reason, image.name(), pc, opcodeAt(pc), syscall, message, cause);
  }

  private Opcode opcodeAt(int pc) {
    return pc >= 0 && pc < image.instructionCount() ? Opcode.of(image.opcodes[pc]) : null;
  }
}
