package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * The original {@code bot_moveresult_t}: failure, result type, blocking state, travel type, result
 * flags, movement weapon, movement direction and ideal view angles. The game host owns its guest
 * memory layout.
 */
public record MovementResult(
    int failure,
    int type,
    int blocked,
    int blockEntity,
    int travelType,
    int flags,
    int weapon,
    Vec3f direction,
    Vec3f idealViewAngles) {
  // Result types (RESULTTYPE_*).
  public static final int ELEVATOR_UP = 1;
  public static final int WAIT_FOR_FUNC_BOBBING = 2;
  public static final int BAD_GRAPPLE_PATH = 4;
  public static final int IN_SOLID_AREA = 8;

  // Result flags (MOVERESULT_*).
  public static final int MOVEMENT_VIEW = 1;
  public static final int SWIM_VIEW = 2;
  public static final int WAITING = 4;
  public static final int MOVEMENT_VIEW_SET = 8;
  public static final int MOVEMENT_WEAPON = 16;
  public static final int ON_TOP_OF_OBSTACLE = 32;
  public static final int ON_TOP_FUNC_BOB = 64;
  public static final int ON_TOP_FUNC_PLAT = 128;
  public static final int BLOCKED_BY_AVOID_SPOT = 256;

  /** A cleared result: no failure, no movement. */
  public static final MovementResult CLEAR =
      new MovementResult(0, 0, 0, 0, 0, 0, 0, Vec3f.ZERO, Vec3f.ZERO);

  public MovementResult {
    Objects.requireNonNull(direction);
    Objects.requireNonNull(idealViewAngles);
  }

  /** A failed result with {@code flags} and nothing else set. */
  public static MovementResult failed(int flags) {
    return new MovementResult(1, 0, 0, 0, 0, flags, 0, Vec3f.ZERO, Vec3f.ZERO);
  }

  public MovementResult withFlags(int extra) {
    return new MovementResult(
        failure,
        type,
        blocked,
        blockEntity,
        travelType,
        flags | extra,
        weapon,
        direction,
        idealViewAngles);
  }
}
