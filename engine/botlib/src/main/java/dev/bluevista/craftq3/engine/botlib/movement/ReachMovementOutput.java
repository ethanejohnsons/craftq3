package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
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
  default Optional<Vec3f> view() {
    return Optional.empty();
  }

  /** An explicit EA_SelectWeapon request, independent of result flags and movement. */
  default OptionalInt weapon() {
    return OptionalInt.empty();
  }

  /** Client commands to issue through EA_Command, in order (the off-hand grapple). */
  default List<String> commands() {
    return List.of();
  }

  interface Move {
    Vec3f direction();

    float speed();
  }
}
