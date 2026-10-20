package dev.bluevista.craftq3.engine.vm;

/**
 * A contained VM fault. It names the module, the faulting instruction index and opcode, and for
 * syscall faults the zero-based syscall number. It never denotes a native address.
 */
public final class QvmException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** No syscall was involved in the fault. */
  public static final int NO_SYSCALL = -1;

  public enum Reason {
    MEMORY_BOUNDS,
    STACK,
    CONTROL_FLOW,
    ARITHMETIC,
    UNSUPPORTED_OPCODE,
    INSTRUCTION_BUDGET,
    SYSCALL_BUDGET,
    DEADLINE,
    INTERRUPTED,
    HOST_FAILURE,
    FAULTED
  }

  private final Reason reason;
  private final String module;
  private final int pc;
  private final Opcode opcode;
  private final int syscall;

  /** A fault outside any module (host memory access through a bare {@link QvmMemory}). */
  public QvmException(Reason reason, String message) {
    this(reason, "", -1, null, NO_SYSCALL, message, null);
  }

  public QvmException(
      Reason reason,
      String module,
      int pc,
      Opcode opcode,
      int syscall,
      String message,
      Throwable cause) {
    super(describe(module, pc, opcode, syscall) + message, cause);
    this.reason = reason;
    this.module = module;
    this.pc = pc;
    this.opcode = opcode;
    this.syscall = syscall;
  }

  private static String describe(String module, int pc, Opcode opcode, int syscall) {
    StringBuilder text = new StringBuilder(module.isEmpty() ? "QVM" : module);
    if (pc >= 0) {
      text.append(" at instruction ").append(pc);
      if (opcode != null) text.append(" (").append(opcode).append(')');
    }
    if (syscall != NO_SYSCALL) text.append(", syscall ").append(syscall);
    return text.append(": ").toString();
  }

  public Reason reason() {
    return reason;
  }

  /** The module name, or empty for a fault outside any module. */
  public String module() {
    return module;
  }

  /** The faulting instruction index, or -1 when no instruction was executing. */
  public int pc() {
    return pc;
  }

  /** The opcode at {@link #pc()}, or {@code null} if unknown. */
  public Opcode opcode() {
    return opcode;
  }

  /** The zero-based syscall number for a fault raised by or inside a syscall, else -1. */
  public int syscall() {
    return syscall;
  }
}
