package dev.bluevista.craftq3.vm;

/** A contained VM fault. It never denotes a native address or an executable host instruction. */
public final class QvmException extends RuntimeException {
  private static final long serialVersionUID = 1L;

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
  private final int instruction;

  public QvmException(Reason reason, String message) {
    this(reason, "", -1, message, null);
  }

  public QvmException(
      Reason reason, String module, int instruction, String message, Throwable cause) {
    super(
        (module.isEmpty() ? "QVM" : module)
            + (instruction < 0 ? "" : " at instruction " + instruction)
            + ": "
            + message,
        cause);
    this.reason = reason;
    this.module = module;
    this.instruction = instruction;
  }

  public Reason reason() {
    return reason;
  }

  public String module() {
    return module;
  }

  public int instruction() {
    return instruction;
  }
}
