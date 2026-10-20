package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;

/** Observed WALKOFFLEDGE entry and airborne completion, without route/history ownership. */
public final class LedgeReachMovement {
  private final MovementObstruction obstruction;
  private final float gravity, maxVelocity, airGravity;

  public LedgeReachMovement(MovementObstruction obstruction) {
    this(obstruction, 800, 320);
  }

  public LedgeReachMovement(MovementObstruction obstruction, float gravity, float maxVelocity) {
    this(obstruction, gravity, maxVelocity, BotAirControl.DEFAULT_GRAVITY);
  }

  /**
   * {@code gravity} and {@code maxVelocity} are {@code phys_gravity} and {@code phys_maxvelocity}
   * for the departure speed; {@code airGravity} is {@code sv_gravity} for airborne steering.
   */
  public LedgeReachMovement(
      MovementObstruction obstruction, float gravity, float maxVelocity, float airGravity) {
    this.obstruction = Objects.requireNonNull(obstruction);
    this.airGravity = airGravity;
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
    Vec3f delta = difference(reach.start(), input.origin());
    var first = obstruction.check(input, sourceArea, normalize(delta).direction());
    var approach = normalize(horizontal(delta));
    Vec3f direction = approach.direction();
    Vec3f span = horizontal(difference(reach.end(), reach.start()));
    float squared = span.x() * span.x() + span.y() * span.y();
    if (!Float.isFinite(squared))
      throw new IllegalArgumentException("Ledge span exceeds float range");
    boolean shortLedge = (float) Math.sqrt(squared) < 20;
    float speed = shortLedge && approach.length() < 64 ? 400 - (64 - approach.length()) * 4 : 400;
    if (approach.length() < 48) {
      direction = normalize(horizontal(difference(reach.end(), input.origin()))).direction();
      if (shortLedge) speed = 100;
      else {
        float drop = reach.start().z() - reach.end().z();
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
    Vec3f delta = difference(reach.end(), input.origin());
    var blocked = obstruction.check(input, sourceArea, delta);
    var approach = normalize(horizontal(delta));
    Vec3f ahead = approach.direction();
    Vec3f target =
        approach.length() <= 16
            ? reach.end()
            : new Vec3f(
                reach.end().x() + 16 * ahead.x(),
                reach.end().y() + 16 * ahead.y(),
                reach.end().z());
    var air = BotAirControl.control(input.origin(), input.velocity(), target, airGravity);
    // Out of reach of steering: head straight for the reach end, as the original does.
    if (!air.success()) air = BotAirControl.straight(input.origin(), reach.end());
    return output(blocked, air.direction(), Math.min(air.speed(), 400));
  }

  private static void validate(MovementInit input, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.WALK_OFF_LEDGE))
      throw new IllegalArgumentException("Non-ledge reach execution");
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
        Vec3f.ZERO,
        Vec3f.ZERO);
  }

  private static GroundReachMovement.Output output(
      MovementResult blocked, Vec3f direction, float speed) {
    return new GroundReachMovement.Output(
        new MovementResult(
            0,
            0,
            blocked.blocked(),
            blocked.blockEntity(),
            0,
            blocked.flags(),
            0,
            direction,
            Vec3f.ZERO),
        direction,
        speed,
        0);
  }

  private static Normalized normalize(Vec3f vector) {
    return Normalized.of(vector);
  }

  private static Vec3f difference(Vec3f target, Vec3f origin) {
    return new Vec3f(target.x() - origin.x(), target.y() - origin.y(), target.z() - origin.z());
  }

  private static Vec3f horizontal(Vec3f value) {
    return new Vec3f(value.x(), value.y(), 0);
  }
}
