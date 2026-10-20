package dev.bluevista.craftq3.engine.vm;

/**
 * The host side of the syscall boundary. A guest {@code CALL} to a negative address {@code -1 - n}
 * invokes {@link #call} with syscall number {@code n}.
 */
@FunctionalInterface
public interface Syscalls {
  /** Number of argument words copied from the guest's outgoing argument area for each call. */
  int MAX_ARGS = 16;

  /**
   * Handles syscall {@code number}. {@code args} holds {@link #MAX_ARGS} words read from the
   * program stack (words beyond the end of memory read as 0). The array is owned by the VM and
   * reused: it is valid only during this call and must not be retained. The handler may re-enter
   * {@code vm} through {@link QvmInstance#vmMain}. Any exception other than a {@link QvmException}
   * faults the VM as a {@link QvmException.Reason#HOST_FAILURE} naming the syscall.
   *
   * @return the value pushed onto the guest operand stack
   */
  int call(QvmInstance vm, int number, int[] args);
}
