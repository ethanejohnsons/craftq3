package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.BspTrace;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Shared native-observed ground travel obstruction queries ({@code BotCheckBlocked}); directions
 * retain caller magnitude. The forward hull is raised by the {@code sv_step} step height.
 */
public final class MovementObstruction {
  /** The original {@code sv_step} default. */
  public static final float DEFAULT_STEP = 18;

  private static final Vec3f MINS = new Vec3f(-15, -15, -24);
  private final BotCollision bsp;
  private final IntUnaryOperator areaReachabilityCount;
  private final float step;

  public MovementObstruction(BotCollision bsp, IntUnaryOperator areaReachabilityCount) {
    this(bsp, areaReachabilityCount, DEFAULT_STEP);
  }

  public MovementObstruction(
      BotCollision bsp, IntUnaryOperator areaReachabilityCount, float stepHeight) {
    this.bsp = Objects.requireNonNull(bsp);
    this.areaReachabilityCount = Objects.requireNonNull(areaReachabilityCount);
    if (!Float.isFinite(stepHeight) || stepHeight < 0 || stepHeight > 100_000)
      throw new IllegalArgumentException("Invalid movement step height");
    this.step = stepHeight;
  }

  /** The cropped forward hull with the optional full downward hull. */
  public MovementResult check(MovementInit input, int sourceArea, Vec3f direction) {
    return check(input, sourceArea, direction, true);
  }

  /** Platform travel omits the bottom query even in areas with no outgoing reaches. */
  public MovementResult check(
      MovementInit input, int sourceArea, Vec3f direction, boolean checkBottom) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(direction);
    if ((input.presenceType() != 2 && input.presenceType() != 4)
        || input.entity() < 0
        || input.entity() >= 1024
        || sourceArea < 0) throw new IllegalArgumentException("Invalid movement obstruction input");
    float top = input.presenceType() == 2 ? 32 : 8;
    boolean cropped = Math.abs(direction.z()) <= .7f;
    BspTrace ahead =
        bsp.trace(
            input.origin(),
            cropped ? new Vec3f(-15, -15, MINS.z() + step) : MINS,
            new Vec3f(15, 15, cropped ? top - 10 : top),
            offset(input.origin(), direction, 3),
            input.entity(),
            Contents.PLAYER_SOLID_BODY);
    if (entityBlocks(ahead)) return blocked(ahead, 0);
    if (!checkBottom || areaReachabilityCount.applyAsInt(sourceArea) != 0)
      return MovementResult.CLEAR;
    BspTrace below =
        bsp.trace(
            input.origin(),
            MINS,
            new Vec3f(15, 15, top),
            new Vec3f(input.origin().x(), input.origin().y(), input.origin().z() - 3),
            input.entity(),
            Contents.PLAYER_SOLID);
    return entityBlocks(below)
        ? blocked(below, MovementResult.ON_TOP_OF_OBSTACLE)
        : MovementResult.CLEAR;
  }

  private static boolean entityBlocks(BspTrace trace) {
    return !trace.startSolid() && trace.hitEntity();
  }

  private static MovementResult blocked(BspTrace trace, int flags) {
    return new MovementResult(0, 0, 1, trace.entity(), 0, flags, 0, Vec3f.ZERO, Vec3f.ZERO);
  }

  private static Vec3f offset(Vec3f point, Vec3f direction, float distance) {
    return new Vec3f(
        point.x() + direction.x() * distance,
        point.y() + direction.y() * distance,
        point.z() + direction.z() * distance);
  }
}
