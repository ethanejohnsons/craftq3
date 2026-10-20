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
import java.util.Optional;

/**
 * One running VM: its data segment and execution state over a shared {@link QvmImage}. Used by a
 * single owner thread ({@link #owner()}). Syscall handlers may re-enter {@link #vmMain}
 * synchronously; a fault leaves the instance faulted until {@link #reset()}.
 *
 * <p>Execution checks every guest access against the data segment, keeps procedure frames intact
 * (frame sizes, saved return addresses, one returned operand) and confines jumps to the current
 * procedure. Instruction budgets are charged per straight-line run at each control transfer, not
 * per instruction, so a budget may be exceeded by at most one run before it faults.
 *
 * <p>The image runs in the interpreter or as JVM bytecode compiled from it ({@link ExecutionMode});
 * both have the same observable behaviour, down to fault reasons, instruction counts and the points
 * at which budgets are checked.
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

  /**
   * How an instance executes its image. Every mode has the same observable semantics (results,
   * faults, budgets, statistics); see {@code docs/formats/QVM.md}.
   */
  public enum ExecutionMode {
    /** The reference interpreter only. */
    INTERPRET,
    /**
     * Compiled to JVM bytecode; construction fails if the image cannot be compiled at all.
     * Procedures the compiler does not handle still run in the interpreter.
     */
    COMPILE,
    /** Compiled when possible, otherwise the whole image is interpreted (logged once per image). */
    AUTO
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

  /** The return address of an invocation's root procedure. */
  static final int VMMAIN_RETURN = -1;

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
  // Read by compiled code (package-private, never reassigned).
  final int[] operandStack;
  private final int[] frames;
  private final int[][] argumentBuffers;
  private final int stackBottom;
  private final QvmCode code;
  private VmTracer tracer;

  // Execution state shared with nested invocations; the dispatch loop and compiled code work on
  // locals and publish them here only around syscalls.
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
  private long deoptimizations;

  private long budgetEnd;
  private long syscallEnd;
  private long deadline;
  private long checkpoint;

  public QvmInstance(QvmImage image, Syscalls syscalls) {
    this(image, syscalls, Limits.defaults(), Clock.system());
  }

  /** An instance in {@link ExecutionMode#AUTO}. */
  public QvmInstance(QvmImage image, Syscalls syscalls, Limits limits, Clock clock) {
    this(image, syscalls, limits, clock, ExecutionMode.AUTO);
  }

  /**
   * @throws IllegalStateException in {@link ExecutionMode#COMPILE} when the image cannot be
   *     compiled
   */
  public QvmInstance(
      QvmImage image, Syscalls syscalls, Limits limits, Clock clock, ExecutionMode mode) {
    this.image = Objects.requireNonNull(image);
    this.handler = Objects.requireNonNull(syscalls);
    this.limits = Objects.requireNonNull(limits);
    this.clock = Objects.requireNonNull(clock);
    memory = new QvmMemory(image.memorySize());
    owner = new ThreadOwner("QVM " + image.name());
    operandStack = new int[limits.operandStackSize()];
    frames = new int[FRAME_WORDS * limits.maxCallDepth()];
    argumentBuffers = new int[limits.maxInvocationDepth()][];
    stackBottom = image.stackBottom();
    code =
        switch (Objects.requireNonNull(mode)) {
          case INTERPRET -> null;
          case COMPILE -> image.compiled(true);
          case AUTO -> image.compiled(false);
        };
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

  /**
   * {@link ExecutionMode#COMPILE} when this instance runs compiled code (procedures the compiler
   * did not handle are interpreted), else {@link ExecutionMode#INTERPRET}. While a tracer is
   * installed every invocation is interpreted.
   */
  public ExecutionMode executionMode() {
    return code != null ? ExecutionMode.COMPILE : ExecutionMode.INTERPRET;
  }

  /** What the compiler made of the image, when this instance runs compiled code. */
  public Optional<QvmCompileReport> compileReport() {
    return code == null ? Optional.empty() : Optional.of(code.report());
  }

  /**
   * Computed jumps in compiled code to a target the compiler had not anticipated; each finished its
   * procedure activation in the interpreter. Diagnostics only (version 1 modules).
   */
  public long deoptimizations() {
    return deoptimizations;
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
    deoptimizations = 0;
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
    } catch (StackOverflowError overflow) {
      // Compiled code uses one host frame per guest call: deep recursion can exhaust the thread.
      faulted = true;
      throw new QvmException(
          STACK,
          image.name(),
          lastPc,
          opcodeAt(lastPc),
          NO_SYSCALL,
          "Host thread stack exhausted",
          overflow);
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
   * Runs one invocation: the procedure at instruction 0 with its incoming frame at {@code frame},
   * as compiled code when the image was compiled and no tracer is installed.
   */
  private int execute(int frame) {
    final int depth = callDepth;
    final int sp = operandTop;
    if (depth + 1 > limits.maxCallDepth()) throw fault(STACK, 0, "Call depth limit exceeded");
    int frameSize = image.operands[0];
    int ps = frame - frameSize;
    if (ps < stackBottom) throw fault(STACK, 0, "Program stack exhausted");
    if (depth + 1 > maxCallDepth) maxCallDepth = depth + 1;
    VmTracer tracer = this.tracer;
    if (tracer == null && code != null) return code.invoke(0, this, ps, VMMAIN_RETURN, depth, sp);
    if (tracer != null) tracer.instruction(0, Opcode.ENTER, frameSize, sp, ps);
    return interpret(0, 1, ps, VMMAIN_RETURN, depth, sp, 0);
  }

  /**
   * The dispatch loop: runs procedure {@code entry} from instruction {@code start} until that
   * procedure returns. The current procedure lives in locals ({@code entry}, {@code ret}, {@code
   * base}, {@code frameSize}); callers are saved as triples in {@link #frames}. Callees the image's
   * compiled code covers run compiled (never while tracing).
   *
   * @param ret the procedure's return address ({@link #VMMAIN_RETURN} for an invocation's root)
   * @param depth the procedure's call depth
   * @param sp the operand stack index of the procedure's first operand
   * @param runStart the first instruction of the current straight-line run (not yet charged)
   */
  private int interpret(int entry, int start, int ps, int ret, int depth, int sp, int runStart) {
    final int[] ops = image.opcodes;
    final int[] imm = image.operands;
    final int[] procedures = image.procedures;
    final byte[] flags = image.flags;
    final boolean jumpTable = image.version() == 2;
    final int count = image.instructionCount();
    final byte[] mem = memory.data;
    final int mask = mem.length - 1;
    final int size = mem.length;
    final int stackBottom = this.stackBottom;
    final int[] stack = operandStack;
    final int[] saved = frames;
    final int maxDepth = limits.maxCallDepth();
    final VmTracer tracer = this.tracer;
    final QvmCode code = tracer == null ? this.code : null;
    final int rootDepth = depth;

    int base = sp;
    int frameSize = imm[entry];
    int pc = start;
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
              if (ret == VMMAIN_RETURN) lastPc = at;
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
              if (code != null && code.compiled(target)) {
                if (depth + 2 > maxCallDepth) maxCallDepth = depth + 2;
                charge(pc - runStart + 1, at);
                int result = code.invoke(target, this, calleeStack, pc, depth + 1, sp);
                stack[sp++] = result;
                runStart = pc;
                continue;
              }
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
  static int truncate(float value) {
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

  QvmException memoryFault(int pc, int address, int length) {
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

  // ---- entry points for compiled code (QvmCompiler); each mirrors an interpreter step ----------

  /** The data segment's bytes, loaded once per compiled procedure activation. */
  byte[] data() {
    return memory.data;
  }

  /**
   * A straight-line run of {@code executed} instructions ending at {@code pc} transfers control.
   */
  void jitCharge(int executed, int pc) {
    if ((instructions += executed) >= checkpoint) checkpointReached(pc);
  }

  /**
   * A {@code CALL} at {@code pc} to a procedure with a {@code calleeSize}-byte frame: checks the
   * program stack and call depth, saves the return address and charges the run (including the
   * callee's {@code ENTER}). Returns the callee's program stack pointer.
   */
  int jitEnter(int ps, int depth, int calleeSize, int executed, int pc) {
    int calleeStack = ps - calleeSize;
    if (calleeStack < stackBottom) throw fault(STACK, pc, "Program stack exhausted");
    if (depth + 2 > limits.maxCallDepth()) throw fault(STACK, pc, "Call depth limit exceeded");
    INT.set(memory.data, ps, pc + 1);
    if (depth + 2 > maxCallDepth) maxCallDepth = depth + 2;
    charge(executed, pc);
    return calleeStack;
  }

  /** A {@code CALL} at {@code pc} of syscall {@code number}. */
  int jitSyscall(int number, int ps, int depth, int top, int executed, int pc) {
    charge(executed, pc);
    programStack = ps;
    operandTop = top;
    callDepth = depth + 1;
    return syscall(pc, number, ps);
  }

  /** A {@code CALL} at {@code pc} whose target was computed. */
  int jitCall(int target, int ps, int depth, int top, int executed, int pc) {
    if (target < 0) return jitSyscall(-1 - target, ps, depth, top, executed, pc);
    if (target >= image.instructionCount() || image.opcodes[target] != Op.ENTER)
      throw fault(CONTROL_FLOW, pc, "CALL target " + target + " is not a procedure");
    int calleeStack = jitEnter(ps, depth, image.operands[target], executed + 1, pc);
    return code.invoke(target, this, calleeStack, pc + 1, depth + 1, top);
  }

  /**
   * A {@code LEAVE} at {@code pc} whose frame size and operand count are statically correct: checks
   * the saved return address at {@code caller}, then charges the run.
   */
  void jitLeave(int caller, int ret, int executed, int pc) {
    if ((int) INT.get(memory.data, caller) != ret)
      throw fault(CONTROL_FLOW, pc, "Guest modified a saved return address");
    charge(executed, pc);
    if (ret == VMMAIN_RETURN) lastPc = pc;
  }

  /** A whole activation of procedure {@code entry} run by the interpreter. */
  int jitInterpret(int ps, int ret, int depth, int base, int entry) {
    return interpret(
        entry, entry + 1, ps, ret, depth, base, ret == VMMAIN_RETURN ? entry : entry + 1);
  }

  /**
   * A computed {@code JUMP} at {@code pc} in procedure {@code entry} to a target the compiled code
   * does not cover: faults like the interpreter, or (version 1) charges the run and finishes the
   * activation in the interpreter from {@code target}.
   */
  int jitJump(int target, int entry, int ps, int ret, int depth, int base, int executed, int pc) {
    if (target < 0
        || target >= image.instructionCount()
        || image.procedures[target] != entry
        || target == entry)
      throw fault(CONTROL_FLOW, pc, "Jump to " + target + " leaves the procedure");
    if (image.version() == 2)
      throw fault(CONTROL_FLOW, pc, "Indirect target " + target + " not in v2 jump table");
    charge(executed, pc);
    deoptimizations++;
    return interpret(entry, target, ps, ret, depth, base, target);
  }

  /** A {@code BLOCK_COPY} at {@code pc}. */
  void jitBlockCopy(int destination, int source, int length, int executed, int pc) {
    byte[] mem = memory.data;
    int mask = mem.length - 1;
    source &= mask;
    destination &= mask;
    charge(executed + (length + 3L) / 4, pc);
    if (length > mem.length - source) throw memoryFault(pc, source, length);
    if (length > mem.length - destination) throw memoryFault(pc, destination, length);
    System.arraycopy(mem, source, mem, destination, length);
  }

  /** Fault kinds the compiler knows statically, with the interpreter's reasons and messages. */
  static final int FAULT_UNSUPPORTED = 0;

  static final int FAULT_FELL_INTO_ENTER = 1;
  static final int FAULT_RAN_PAST_END = 2;
  static final int FAULT_LEAVE_FRAME = 3;
  static final int FAULT_LEAVE_OPERANDS = 4;
  static final int FAULT_UNDERFLOW = 5;
  static final int FAULT_CALL_TARGET = 6;
  static final int FAULT_JUMP_TARGET = 7;
  static final int FAULT_JUMP_TABLE = 8;
  static final int FAULT_ARG = 9;
  static final int FAULT_LOCAL = 10;
  static final int FAULT_DIVISION = 11;

  /** The fault {@code kind} at {@code pc}; {@code value} is the target for the target kinds. */
  QvmException jitFault(int kind, int pc, int value) {
    return switch (kind) {
      case FAULT_UNSUPPORTED ->
          fault(
              UNSUPPORTED_OPCODE, pc, "Unsupported executed opcode " + OPCODES[image.opcodes[pc]]);
      case FAULT_FELL_INTO_ENTER -> fault(CONTROL_FLOW, pc, "Fell into ENTER without CALL");
      case FAULT_RAN_PAST_END -> fault(CONTROL_FLOW, pc, "Execution ran past the last instruction");
      case FAULT_LEAVE_FRAME -> fault(STACK, pc, "Mismatched procedure frame on LEAVE");
      case FAULT_LEAVE_OPERANDS -> fault(STACK, pc, "Procedure must return exactly one operand");
      case FAULT_UNDERFLOW -> fault(STACK, pc, "Operand stack underflow");
      case FAULT_CALL_TARGET ->
          fault(CONTROL_FLOW, pc, "CALL target " + value + " is not a procedure");
      case FAULT_JUMP_TARGET ->
          fault(CONTROL_FLOW, pc, "Jump to " + value + " leaves the procedure");
      case FAULT_JUMP_TABLE ->
          fault(CONTROL_FLOW, pc, "Indirect target " + value + " not in v2 jump table");
      case FAULT_ARG -> fault(STACK, pc, "ARG writes outside outgoing argument area");
      case FAULT_LOCAL -> fault(MEMORY_BOUNDS, pc, "Local address exceeds VM memory");
      case FAULT_DIVISION -> fault(ARITHMETIC, pc, "Integer division by zero");
      default -> throw new IllegalArgumentException("Unknown fault kind " + kind);
    };
  }
}
