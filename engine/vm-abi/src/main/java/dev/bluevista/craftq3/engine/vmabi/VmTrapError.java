package dev.bluevista.craftq3.engine.vmabi;

/**
 * Thrown by the {@code trap_Error} services: the guest ended itself with a message. The dispatcher
 * never degrades it; the interpreter wraps it in a {@code QvmException} (reason {@code
 * HOST_FAILURE}) whose cause is this error, so the host can drop the map or disconnect with the
 * guest's message.
 */
public final class VmTrapError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final VmModule module;
  private final String guestMessage;

  public VmTrapError(VmModule module, String guestMessage) {
    super(module.moduleName() + " error: " + guestMessage);
    this.module = module;
    this.guestMessage = guestMessage;
  }

  public VmModule module() {
    return module;
  }

  /** The text the guest passed to {@code trap_Error}. */
  public String guestMessage() {
    return guestMessage;
  }
}
