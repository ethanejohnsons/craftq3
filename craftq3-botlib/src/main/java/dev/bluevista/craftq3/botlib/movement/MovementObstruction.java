package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Shared native-observed ground travel obstruction queries; directions retain caller magnitude. */
public final class MovementObstruction {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final Vec3 MINS = new Vec3(-15, -15, -24);
  private final TraceWorld bsp;
  private final IntUnaryOperator areaReachabilityCount;

  public MovementObstruction(TraceWorld bsp, IntUnaryOperator areaReachabilityCount) {
    this.bsp = Objects.requireNonNull(bsp);
    this.areaReachabilityCount = Objects.requireNonNull(areaReachabilityCount);
  }

  /** Uses the verified default sv_step=18 cropped forward hull and optional full downward hull. */
  public MovementResult check(MovementInit input, int sourceArea, Vec3 direction) {
    return check(input, sourceArea, direction, true);
  }

  /** Platform travel omits the bottom query even in areas with no outgoing reaches. */
  public MovementResult check(
      MovementInit input, int sourceArea, Vec3 direction, boolean checkBottom) {
    Objects.requireNonNull(input);
    direction = MovementAbi.vector(direction);
    if ((input.presenceType() != 2 && input.presenceType() != 4)
        || input.entity() < 0
        || input.entity() >= 1024
        || sourceArea < 0) throw new IllegalArgumentException("Invalid movement obstruction input");
    float top = input.presenceType() == 2 ? 32 : 8;
    boolean cropped = Math.abs((float) direction.z()) <= .7f;
    TraceResult ahead =
        bsp.trace(
            new TraceRequest(
                input.origin(),
                offset(input.origin(), direction, 3),
                cropped ? new Vec3(-15, -15, -6) : MINS,
                new Vec3(15, 15, cropped ? top - 10 : top),
                33619969,
                input.entity()));
    if (entityBlocks(ahead)) return blocked(ahead, 0);
    if (!checkBottom || areaReachabilityCount.applyAsInt(sourceArea) != 0)
      return new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO);
    TraceResult below =
        bsp.trace(
            new TraceRequest(
                input.origin(),
                new Vec3(input.origin().x(), input.origin().y(), (float) input.origin().z() - 3),
                MINS,
                new Vec3(15, 15, top),
                65537,
                input.entity()));
    return entityBlocks(below)
        ? blocked(below, 32)
        : new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO);
  }

  private static boolean entityBlocks(TraceResult trace) {
    return !trace.startSolid()
        && trace.hit().isPresent()
        && trace.hit().orElseThrow().entity() != 1022
        && trace.hit().orElseThrow().entity() != 1023;
  }

  private static MovementResult blocked(TraceResult trace, int flags) {
    return new MovementResult(0, 0, 1, trace.hit().orElseThrow().entity(), 0, flags, 0, ZERO, ZERO);
  }

  private static Vec3 offset(Vec3 point, Vec3 direction, float distance) {
    return new Vec3(
        (float) point.x() + (float) direction.x() * distance,
        (float) point.y() + (float) direction.y() * distance,
        (float) point.z() + (float) direction.z() * distance);
  }
}
