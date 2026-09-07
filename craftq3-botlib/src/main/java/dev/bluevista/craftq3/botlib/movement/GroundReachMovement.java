package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

/**
 * Native-observed command generation for WALK and CROUCH reachabilities, without state mutation.
 */
public final class GroundReachMovement {
  @FunctionalInterface
  public interface GapDistance {
    float distance(Vec3 origin, Vec3 direction, int entity);
  }

  /** Reach execution leaves travelType zero; movement within a goal area sets it to WALK (2). */
  public record Output(MovementResult result, Vec3 direction, float speed, int actionFlags)
      implements ReachMovementOutput, ReachMovementOutput.Move {
    public Output {
      Objects.requireNonNull(result);
      direction = MovementAbi.vector(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid ground travel speed");
    }

    @Override
    public Optional<Output> movement() {
      return Optional.of(this);
    }
  }

  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final MovementObstruction obstruction;
  private final IntUnaryOperator areaPresence;
  private final GapDistance gaps;

  public GroundReachMovement(
      TraceWorld bsp,
      IntUnaryOperator areaPresence,
      IntUnaryOperator areaReachabilityCount,
      GapDistance gaps) {
    this.obstruction = new MovementObstruction(bsp, areaReachabilityCount);
    this.areaPresence = Objects.requireNonNull(areaPresence);
    this.gaps = Objects.requireNonNull(gaps);
  }

  /** Effective flags are retained host movement flags, not the raw initialization flags. */
  public Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, sourceArea);
    Objects.requireNonNull(reach);
    int kind = reach.baseTravelType();
    if (kind != 2 && kind != 3)
      throw new UnsupportedOperationException("Ground reach travel kind " + kind);
    Direction approach = horizontal(input.origin(), kind == 2 ? reach.start() : reach.end());
    MovementResult obstruction = this.obstruction.check(input, sourceArea, approach.vector());
    Vec3 direction = approach.vector();
    float speed = 400;
    int actions = 0;
    if (kind == 3) actions |= ActionFlags.CROUCH;
    else {
      // The obstruction query uses the approach direction even when the final command turns.
      int targetPresence = areaPresence.applyAsInt(reach.area());
      Direction target =
          approach.distance() < 10 ? horizontal(input.origin(), reach.end()) : approach;
      direction = target.vector();
      if (target.distance() < 20 && (targetPresence & 2) == 0) actions |= ActionFlags.CROUCH;
      float gap = gaps.distance(input.origin(), direction, input.entity());
      if (!Float.isFinite(gap)) throw new IllegalArgumentException("Nonfinite ground gap distance");
      if (gap > 0) speed = Math.min(speed, 40 + 2 * gap);
      if ((effectiveFlags & 512) != 0) {
        speed *= .5f;
        actions |= ActionFlags.WALK;
      }
    }
    return new Output(
        new MovementResult(
            0,
            0,
            obstruction.blocked(),
            obstruction.blockEntity(),
            0,
            obstruction.flags(),
            0,
            direction,
            ZERO),
        direction,
        speed,
        actions);
  }

  /**
   * Dry movement within the goal area always emits a move command, including zero speed at rest. It
   * ignores slow-walk and crouch actions. The distinct swimming/view branch is unsupported.
   */
  public Output moveInGoalArea(
      MovementInit input, int effectiveFlags, int sourceArea, Vec3 goalOrigin) {
    validate(input, sourceArea);
    if ((effectiveFlags & 4) != 0)
      throw new UnsupportedOperationException("Swimming within a goal area");
    Direction target = horizontal(input.origin(), goalOrigin);
    MovementResult obstruction = this.obstruction.check(input, sourceArea, target.vector());
    float speed = target.distance() >= 100 ? 400 : 400 - (100 - target.distance()) * 4;
    if (speed < 10) speed = 0;
    var result =
        new MovementResult(
            0,
            0,
            obstruction.blocked(),
            obstruction.blockEntity(),
            2,
            obstruction.flags(),
            0,
            target.vector(),
            ZERO);
    return new Output(result, target.vector(), speed, 0);
  }

  private static void validate(MovementInit input, int sourceArea) {
    Objects.requireNonNull(input);
    if (input.presenceType() != 2 && input.presenceType() != 4)
      throw new IllegalArgumentException("Unknown ground movement presence");
    if (input.entity() < 0 || input.entity() >= 1024)
      throw new IllegalArgumentException("Invalid ground movement entity");
    if (sourceArea < 0) throw new IllegalArgumentException("Invalid source movement area");
  }

  private record Direction(Vec3 vector, float distance) {}

  private static Direction horizontal(Vec3 from, Vec3 to) {
    to = MovementAbi.vector(to);
    float x = (float) to.x() - (float) from.x(), y = (float) to.y() - (float) from.y();
    float squared = x * x + y * y;
    float root = (float) Math.sqrt(squared);
    if (!Float.isFinite(root))
      throw new IllegalArgumentException("Ground direction exceeds float range");
    float inverse = root == 0 ? 0 : 1 / root;
    // The native normalization return value is rounded separately from the square root.
    return new Direction(new Vec3(x * inverse, y * inverse, 0), squared * inverse);
  }
}
