package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A reach result with independent movement and elementary actions. An absent movement preserves
 * previous EA_Move direction/speed; it is not a zero-speed movement request.
 */
public interface ReachMovementOutput {
  MovementResult result();

  Optional<? extends Move> movement();

  /** Native elementary-action bits; jump must use the elementary jump service's state rules. */
  int actionFlags();

  /** An explicit EA_View request, independent of result flags and movement. */
  default Optional<Vec3> view() {
    return Optional.empty();
  }

  /** An explicit EA_SelectWeapon request, independent of result flags and movement. */
  default OptionalInt weapon() {
    return OptionalInt.empty();
  }

  interface Move {
    Vec3 direction();

    float speed();
  }
}
