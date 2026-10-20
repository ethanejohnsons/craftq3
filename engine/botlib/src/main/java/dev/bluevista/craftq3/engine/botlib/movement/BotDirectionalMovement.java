package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.Optional;

/**
 * Directional engine movement decisions over explicit, bounded geometry and prediction services.
 */
public final class BotDirectionalMovement {
  public interface Services {
    boolean swimming(Vec3f origin);

    boolean onGround(Vec3f origin, int presence, int entity);

    boolean barrierJump(MovementInit input, Vec3f direction, float speed);

    float gapDistance(Vec3f origin, Vec3f direction, int entity);

    Optional<AasMovementPredictor.Prediction> predict(AasMovementPredictor.Request request);
  }

  public record Motion(Vec3f direction, float speed) {
    public Motion {
      direction = Objects.requireNonNull(direction);
    }
  }

  /** Caller applies flags even on refusal; successful actions precede the optional move. */
  public record Decision(
      boolean accepted, int movementFlags, boolean crouch, boolean jump, Optional<Motion> motion) {
    public Decision {
      motion = Objects.requireNonNull(motion);
    }
  }

  private final Services services;

  public BotDirectionalMovement(Services services) {
    this.services = Objects.requireNonNull(services);
  }

  public Decision move(
      MovementInit input, int movementFlags, Vec3f direction, float speed, int type) {
    Objects.requireNonNull(input);
    direction = Objects.requireNonNull(direction);
    if (!Float.isFinite(speed) || Math.abs(speed) > 1_000_000)
      throw new IllegalArgumentException("Invalid directional movement speed");
    if (services.swimming(input.origin()))
      return motion(movementFlags, normalized(direction), speed, false, false);
    if (services.onGround(input.origin(), input.presenceType(), input.entity())) movementFlags |= 2;
    if ((movementFlags & 2) == 0) {
      if ((movementFlags & 1) != 0 && input.velocity().z() < 50)
        return motion(movementFlags, direction, speed, false, false);
      return new Decision(true, movementFlags, false, false, Optional.empty());
    }
    movementFlags &= ~1;
    if (services.barrierJump(input, direction, speed))
      return motion(movementFlags | 1, MovementObstacles.horizontal(direction), speed, false, true);
    direction = MovementObstacles.horizontal(direction);
    boolean jump = (type & 4) != 0;
    boolean crouch = (type & 2) != 0;
    int presence = !jump && crouch ? 4 : 2;
    if (!jump && services.gapDistance(input.origin(), direction, input.entity()) > 0) jump = true;
    var origin = new Vec3f(input.origin().x(), input.origin().y(), input.origin().z() + .5f);
    var command = new Vec3f(direction.x() * speed, direction.y() * speed, jump ? 400 : 0);
    var request =
        new AasMovementPredictor.Request(
            input.entity(),
            origin,
            presence,
            true,
            input.velocity(),
            command,
            jump ? 1 : 2,
            jump ? 30 : 2,
            .1f,
            jump ? 61 : 60);
    var prediction = Objects.requireNonNull(services.predict(request));
    // Native failure may leave its stack output undefined; Java never acts on absent prediction.
    if (prediction.isEmpty()) return refused(movementFlags);
    var result = prediction.orElseThrow();
    if ((result.stopEvent() & (8 | 16 | 32)) != 0 || jump && result.frames() >= 30)
      return refused(movementFlags);
    if ((result.stopEvent() & 1) != 0
        && (services.gapDistance(result.endPosition(), Vec3f.ZERO, input.entity()) > 0
            || services.gapDistance(result.endPosition(), direction, input.entity()) > 0))
      return refused(movementFlags);
    float x = result.endPosition().x() - input.origin().x();
    float y = result.endPosition().y() - input.origin().y();
    float travelled = (float) Math.sqrt(x * x + y * y);
    if (travelled < (speed * input.thinkTime()) * .5) return refused(movementFlags);
    return motion(movementFlags, direction, speed, crouch, jump);
  }

  private static Vec3f normalized(Vec3f vector) {
    float x = vector.x(), y = vector.y(), z = vector.z();
    float length = (float) Math.sqrt(x * x + y * y + z * z);
    if (length != 0) {
      float inverse = 1 / length;
      x *= inverse;
      y *= inverse;
      z *= inverse;
    }
    return new Vec3f(x, y, z);
  }

  private static Decision motion(
      int flags, Vec3f direction, float speed, boolean crouch, boolean jump) {
    return new Decision(true, flags, crouch, jump, Optional.of(new Motion(direction, speed)));
  }

  private static Decision refused(int flags) {
    return new Decision(false, flags, false, false, Optional.empty());
  }
}
