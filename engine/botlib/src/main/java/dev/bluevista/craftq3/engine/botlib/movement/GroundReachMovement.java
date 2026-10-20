package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

/**
 * Native-observed command generation for WALK and CROUCH reachabilities, without state mutation.
 */
public final class GroundReachMovement {
  @FunctionalInterface
  public interface GapDistance {
    float distance(Vec3f origin, Vec3f direction, int entity);
  }

  /** Reach execution leaves travelType zero; movement within a goal area sets it to WALK (2). */
  public record Output(MovementResult result, Vec3f direction, float speed, int actionFlags)
      implements ReachMovementOutput, ReachMovementOutput.Move {
    public Output {
      Objects.requireNonNull(result);
      direction = Objects.requireNonNull(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid ground travel speed");
    }

    @Override
    public Optional<Output> movement() {
      return Optional.of(this);
    }
  }

  private final MovementObstruction obstruction;
  private final IntUnaryOperator areaPresence;
  private final GapDistance gaps;

  public GroundReachMovement(
      BotCollision bsp,
      IntUnaryOperator areaPresence,
      IntUnaryOperator areaReachabilityCount,
      GapDistance gaps) {
    this(new MovementObstruction(bsp, areaReachabilityCount), areaPresence, gaps);
  }

  public GroundReachMovement(
      MovementObstruction obstruction, IntUnaryOperator areaPresence, GapDistance gaps) {
    this.obstruction = Objects.requireNonNull(obstruction);
    this.areaPresence = Objects.requireNonNull(areaPresence);
    this.gaps = Objects.requireNonNull(gaps);
  }

  /** Effective flags are retained host movement flags, not the raw initialization flags. */
  public Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, sourceArea);
    Objects.requireNonNull(reach);
    boolean walk = reach.is(TravelType.WALK);
    if (!walk && !reach.is(TravelType.CROUCH))
      throw new IllegalArgumentException("Ground reach travel kind " + reach.baseTravelType());
    Normalized approach = horizontal(input.origin(), walk ? reach.start() : reach.end());
    MovementResult obstruction = this.obstruction.check(input, sourceArea, approach.direction());
    Vec3f direction = approach.direction();
    float speed = 400;
    int actions = 0;
    if (!walk) actions |= ActionFlags.CROUCH;
    else {
      // The obstruction query uses the approach direction even when the final command turns.
      int targetPresence = areaPresence.applyAsInt(reach.area());
      Normalized target =
          approach.length() < 10 ? horizontal(input.origin(), reach.end()) : approach;
      direction = target.direction();
      if (target.length() < 20 && (targetPresence & 2) == 0) actions |= ActionFlags.CROUCH;
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
            Vec3f.ZERO),
        direction,
        speed,
        actions);
  }

  /**
   * Dry movement within the goal area always emits a move command, including zero speed at rest. It
   * ignores slow-walk and crouch actions. The distinct swimming/view branch is unsupported.
   */
  public Output moveInGoalArea(
      MovementInit input, int effectiveFlags, int sourceArea, Vec3f goalOrigin) {
    validate(input, sourceArea);
    if ((effectiveFlags & 4) != 0)
      throw new IllegalArgumentException("Swimming within a goal area uses liquid travel");
    Normalized target = horizontal(input.origin(), goalOrigin);
    MovementResult obstruction = this.obstruction.check(input, sourceArea, target.direction());
    float speed = target.length() >= 100 ? 400 : 400 - (100 - target.length()) * 4;
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
            target.direction(),
            Vec3f.ZERO);
    return new Output(result, target.direction(), speed, 0);
  }

  private static void validate(MovementInit input, int sourceArea) {
    Objects.requireNonNull(input);
    if (input.presenceType() != 2 && input.presenceType() != 4)
      throw new IllegalArgumentException("Unknown ground movement presence");
    if (input.entity() < 0 || input.entity() >= 1024)
      throw new IllegalArgumentException("Invalid ground movement entity");
    if (sourceArea < 0) throw new IllegalArgumentException("Invalid source movement area");
  }

  private static Normalized horizontal(Vec3f from, Vec3f to) {
    return Normalized.horizontallyTowards(to, from);
  }
}
