package dev.bluevista.craftq3.botlib.movement;

/** A direct travel result with an explicitly observed replacement for runtime movement flags. */
public interface FlaggedReachMovementOutput extends ReachMovementOutput {
  int movementFlags();

  /** An explicit direct-travel request to invalidate the cached reach deadline. */
  default boolean clearReachDeadline() {
    return false;
  }
}
