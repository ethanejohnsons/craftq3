package dev.bluevista.craftq3.engine.session;

/**
 * Thrown by {@link EngineSession#frame} under {@link SessionOptions#failOnDegradation()} when a VM
 * syscall degraded (or, under {@code vm_strictSyscalls 1}, faulted the VM) since the previous
 * check. The session itself stays usable: the error was already handled as usual ({@code ERR_DROP}
 * under strict syscalls, counted otherwise); the exception only fails a scripted run.
 */
public final class SessionDegradedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public SessionDegradedException(String message) {
    super(message);
  }
}
