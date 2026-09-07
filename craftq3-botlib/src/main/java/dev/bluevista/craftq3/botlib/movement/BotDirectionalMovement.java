package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;

/**
 * Directional engine movement decisions over explicit, bounded geometry and prediction services.
 */
public final class BotDirectionalMovement {
  public interface Services {
    boolean swimming(Vec3 origin);

    boolean onGround(Vec3 origin, int presence, int entity);

    boolean barrierJump(MovementInit input, Vec3 direction, float speed);

    float gapDistance(Vec3 origin, Vec3 direction, int entity);

    Optional<AasMovementPredictor.Prediction> predict(AasMovementPredictor.Request request);
  }

  public record Motion(Vec3 direction, float speed) {
    public Motion {
      direction = MovementAbi.vector(direction);
    }
  }

  /** Caller applies flags even on refusal; successful actions precede the optional move. */
  public record Decision(
      boolean accepted, int movementFlags, boolean crouch, boolean jump, Optional<Motion> motion) {
    public Decision {
      motion = Objects.requireNonNull(motion);
    }
  }

  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final Services services;

  public BotDirectionalMovement(Services services) {
    this.services = Objects.requireNonNull(services);
  }

  public Decision move(
      MovementInit input, int movementFlags, Vec3 direction, float speed, int type) {
    Objects.requireNonNull(input);
    direction = MovementAbi.vector(direction);
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
    var origin = new Vec3(input.origin().x(), input.origin().y(), (float) input.origin().z() + .5f);
    var command =
        new Vec3((float) direction.x() * speed, (float) direction.y() * speed, jump ? 400 : 0);
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
        && (services.gapDistance(result.endPosition(), ZERO, input.entity()) > 0
            || services.gapDistance(result.endPosition(), direction, input.entity()) > 0))
      return refused(movementFlags);
    float x = (float) result.endPosition().x() - (float) input.origin().x();
    float y = (float) result.endPosition().y() - (float) input.origin().y();
    float travelled = (float) Math.sqrt(x * x + y * y);
    if (travelled < (speed * input.thinkTime()) * .5) return refused(movementFlags);
    return motion(movementFlags, direction, speed, crouch, jump);
  }

  private static Vec3 normalized(Vec3 vector) {
    float x = (float) vector.x(), y = (float) vector.y(), z = (float) vector.z();
    float length = (float) Math.sqrt(x * x + y * y + z * z);
    if (length != 0) {
      float inverse = 1 / length;
      x *= inverse;
      y *= inverse;
      z *= inverse;
    }
    return new Vec3(x, y, z);
  }

  private static Decision motion(
      int flags, Vec3 direction, float speed, boolean crouch, boolean jump) {
    return new Decision(true, flags, crouch, jump, Optional.of(new Motion(direction, speed)));
  }

  private static Decision refused(int flags) {
    return new Decision(false, flags, false, false, Optional.empty());
  }
}
