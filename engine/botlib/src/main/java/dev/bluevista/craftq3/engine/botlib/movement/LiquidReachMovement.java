package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/** Native-observed swim and water-jump commands; the original VM owns liquid physics. */
public final class LiquidReachMovement {
  private final MovementObstruction obstruction;
  private final DoubleSupplier random;

  public record Move(Vec3f direction, float speed) implements ReachMovementOutput.Move {
    public Move {
      direction = Objects.requireNonNull(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid liquid movement speed");
    }
  }

  /** Water jumping issues directional actions while preserving any prior EA_Move command. */
  public record Output(MovementResult result, Optional<Move> movement, int actionFlags)
      implements ReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
    }
  }

  /** The borrowed random source supplies one value in [0,1] for each water-jump entry. */
  public LiquidReachMovement(MovementObstruction obstruction, DoubleSupplier random) {
    this.obstruction = Objects.requireNonNull(obstruction);
    this.random = Objects.requireNonNull(random);
  }

  public Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, sourceArea, reach);
    if (reach.is(TravelType.SWIM)) {
      Vec3f direction = normalize(delta(reach.start(), input.origin()));
      MovementResult blocked = obstruction.check(input, sourceArea, direction);
      var result =
          new MovementResult(
              0,
              0,
              blocked.blocked(),
              blocked.blockEntity(),
              0,
              blocked.flags() | 2,
              0,
              direction,
              angles(direction));
      return new Output(result, Optional.of(new Move(direction, 400)), 0);
    }
    Vec3f delta = delta(reach.end(), input.origin());
    double sample = random.getAsDouble();
    if (!Double.isFinite(sample) || sample < 0 || sample > 1)
      throw new IllegalArgumentException("Liquid random sample outside [0,1]");
    float r = (float) sample;
    float z = (float) ((double) delta.z() + 15 + 40 * (2 * (double) r - 1));
    Vec3f direction = normalize(new Vec3f(delta.x(), delta.y(), z));
    float x = delta.x(), y = delta.y();
    float squared = x * x + y * y;
    float root = (float) Math.sqrt(squared);
    float horizontalDistance = root == 0 ? 0 : squared * (1 / root);
    var result = new MovementResult(0, 0, 0, 0, 0, 1, 0, direction, angles(direction));
    return new Output(result, Optional.empty(), 512 | (horizontalDistance < 40 ? 32 : 0));
  }

  /** Verified same-area swimming approach; slowing uses full three-dimensional goal distance. */
  public Output moveInGoalArea(MovementInit input, int effectiveFlags, int sourceArea, Vec3f goal) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(goal);
    if ((effectiveFlags & 4) == 0)
      throw new IllegalArgumentException("Liquid goal approach requires swimming state");
    Vec3f delta = delta(goal, input.origin());
    Vec3f direction = normalize(delta);
    float x = delta.x(), y = delta.y(), z = delta.z();
    float squared = x * x + y * y + z * z;
    float root = (float) Math.sqrt(squared);
    float distance = root == 0 ? 0 : squared * (1 / root);
    float speed = distance >= 100 ? 400 : 400 - (100 - distance) * 4;
    if (speed < 10) speed = 0;
    MovementResult blocked = obstruction.check(input, sourceArea, direction);
    var result =
        new MovementResult(
            0,
            0,
            blocked.blocked(),
            blocked.blockEntity(),
            8,
            blocked.flags() | 2,
            0,
            direction,
            angles(direction));
    return new Output(result, Optional.of(new Move(direction, speed)), 0);
  }

  /**
   * Native water-jump completion leaves the accumulated command untouched and clears the result.
   */
  public Output finish(MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, sourceArea, reach);
    if (!reach.is(TravelType.WATER_JUMP))
      throw new IllegalArgumentException("Airborne swim travel uses swim entry, not a finish");
    return new Output(MovementResult.CLEAR, Optional.empty(), 0);
  }

  private static Vec3f delta(Vec3f target, Vec3f origin) {
    return new Vec3f(target.x() - origin.x(), target.y() - origin.y(), target.z() - origin.z());
  }

  private static Vec3f normalize(Vec3f delta) {
    float x = delta.x(), y = delta.y(), z = delta.z();
    float squared = x * x + y * y + z * z;
    float root = (float) Math.sqrt(squared);
    if (!Float.isFinite(root))
      throw new IllegalArgumentException("Liquid direction exceeds float range");
    float inverse = root == 0 ? 0 : 1 / root;
    return new Vec3f(x * inverse, y * inverse, z * inverse);
  }

  private static Vec3f angles(Vec3f direction) {
    return ViewAngles.of(direction);
  }

  private static void validate(MovementInit input, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.SWIM) && !reach.is(TravelType.WATER_JUMP))
      throw new IllegalArgumentException("Non-liquid reach execution");
    if (input.entity() < 0
        || input.entity() >= 1024
        || sourceArea < 0
        || (input.presenceType() != 2 && input.presenceType() != 4))
      throw new IllegalArgumentException("Invalid liquid movement input");
  }
}
