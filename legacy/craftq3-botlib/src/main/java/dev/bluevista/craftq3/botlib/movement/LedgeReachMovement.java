package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;

/** Observed WALKOFFLEDGE entry and airborne completion, without route/history ownership. */
public final class LedgeReachMovement {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final MovementObstruction obstruction;
  private final float gravity, maxVelocity;

  public LedgeReachMovement(MovementObstruction obstruction) {
    this(obstruction, 800, 320);
  }

  public LedgeReachMovement(MovementObstruction obstruction, float gravity, float maxVelocity) {
    this.obstruction = Objects.requireNonNull(obstruction);
    if (!Float.isFinite(gravity)
        || gravity <= 0
        || gravity > 100_000
        || !Float.isFinite(maxVelocity)
        || maxVelocity < 0
        || maxVelocity > 100_000)
      throw new IllegalArgumentException("Invalid ledge physics settings");
    this.gravity = gravity;
    this.maxVelocity = maxVelocity;
  }

  public GroundReachMovement.Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, reach);
    Vec3 delta = difference(reach.start(), input.origin());
    var first = obstruction.check(input, sourceArea, normalize(delta).vector());
    var approach = normalize(horizontal(delta));
    Vec3 direction = approach.vector();
    Vec3 span = horizontal(difference(reach.end(), reach.start()));
    float squared = (float) span.x() * (float) span.x() + (float) span.y() * (float) span.y();
    if (!Float.isFinite(squared))
      throw new IllegalArgumentException("Ledge span exceeds float range");
    boolean shortLedge = (float) Math.sqrt(squared) < 20;
    float speed =
        shortLedge && approach.distance() < 64 ? 400 - (64 - approach.distance()) * 4 : 400;
    if (approach.distance() < 48) {
      direction = normalize(horizontal(difference(reach.end(), input.origin()))).vector();
      if (shortLedge) speed = 100;
      else {
        float drop = (float) reach.start().z() - (float) reach.end().z();
        if (drop > 0) {
          float time = (float) Math.sqrt(2.0 * drop / gravity);
          float required = (float) (Math.sqrt(squared) / time);
          if (Float.isFinite(required) && required <= maxVelocity) speed = Math.min(required, 400);
        }
      }
    }
    var second = obstruction.check(input, sourceArea, direction);
    return output(merge(first, second), direction, speed);
  }

  public GroundReachMovement.Output finish(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, reach);
    Vec3 delta = difference(reach.end(), input.origin());
    var blocked = obstruction.check(input, sourceArea, delta);
    var approach = normalize(horizontal(delta));
    Vec3 ahead = approach.vector();
    Vec3 target =
        approach.distance() <= 16
            ? reach.end()
            : new Vec3(
                (float) reach.end().x() + 16 * (float) ahead.x(),
                (float) reach.end().y() + 16 * (float) ahead.y(),
                reach.end().z());
    var air = BotAirControl.control(input.origin(), input.velocity(), target);
    return output(blocked, air.direction(), Math.min(air.speed(), 400));
  }

  private static void validate(MovementInit input, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 7)
      throw new UnsupportedOperationException("Non-ledge reach execution");
  }

  private static MovementResult merge(MovementResult first, MovementResult second) {
    return new MovementResult(
        0,
        0,
        first.blocked() | second.blocked(),
        second.blocked() != 0 ? second.blockEntity() : first.blockEntity(),
        0,
        first.flags() | second.flags(),
        0,
        ZERO,
        ZERO);
  }

  private static GroundReachMovement.Output output(
      MovementResult blocked, Vec3 direction, float speed) {
    return new GroundReachMovement.Output(
        new MovementResult(
            0, 0, blocked.blocked(), blocked.blockEntity(), 0, blocked.flags(), 0, direction, ZERO),
        direction,
        speed,
        0);
  }

  private record Direction(Vec3 vector, float distance) {}

  private static Direction normalize(Vec3 vector) {
    float x = (float) vector.x(), y = (float) vector.y(), z = (float) vector.z();
    float squared = x * x + y * y + z * z;
    float root = (float) Math.sqrt(squared);
    if (!Float.isFinite(root))
      throw new IllegalArgumentException("Ledge direction exceeds float range");
    float inverse = root == 0 ? 0 : 1 / root;
    return new Direction(new Vec3(x * inverse, y * inverse, z * inverse), squared * inverse);
  }

  private static Vec3 difference(Vec3 target, Vec3 origin) {
    return new Vec3(
        (float) target.x() - (float) origin.x(),
        (float) target.y() - (float) origin.y(),
        (float) target.z() - (float) origin.z());
  }

  private static Vec3 horizontal(Vec3 value) {
    return new Vec3(value.x(), value.y(), 0);
  }
}
