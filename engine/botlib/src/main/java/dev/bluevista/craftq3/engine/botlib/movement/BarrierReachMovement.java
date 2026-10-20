package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;

/** Independently observed BARRIERJUMP entry/completion commands; the VM owns jumping physics. */
public final class BarrierReachMovement {
  private final MovementObstruction obstruction;

  public record Move(Vec3f direction, float speed) implements ReachMovementOutput.Move {
    public Move {
      direction = Objects.requireNonNull(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid barrier movement speed");
    }
  }

  /** An absent move preserves earlier EA_Move data even when a separate jump action is present. */
  public record Output(MovementResult result, Optional<Move> movement, int actionFlags)
      implements ReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
    }
  }

  public BarrierReachMovement(MovementObstruction obstruction) {
    this.obstruction = Objects.requireNonNull(obstruction);
  }

  public Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, reach);
    Vec3f delta = horizontalDelta(reach.start(), input.origin());
    float x = delta.x(), y = delta.y();
    float squared = x * x + y * y;
    float length = (float) Math.sqrt(squared);
    if (!Float.isFinite(length))
      throw new IllegalArgumentException("Barrier approach exceeds float range");
    float inverse = length == 0 ? 0 : 1 / length;
    float distance = squared * inverse;
    Vec3f direction = new Vec3f(x * inverse, y * inverse, 0);
    MovementResult result = checked(input, sourceArea, direction);
    if (distance < 9) return new Output(result, Optional.empty(), 16);
    float speed = 360 - (360 - Math.min(distance, 60) * 6);
    return new Output(result, Optional.of(new Move(direction, speed)), 0);
  }

  public Output finish(MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, reach);
    if (input.velocity().z() >= 250) return new Output(MovementResult.CLEAR, Optional.empty(), 0);
    Vec3f direction = horizontalDelta(reach.end(), input.origin());
    return new Output(
        checked(input, sourceArea, direction), Optional.of(new Move(direction, 400)), 0);
  }

  private MovementResult checked(MovementInit input, int sourceArea, Vec3f direction) {
    MovementResult blocked = obstruction.check(input, sourceArea, direction);
    return new MovementResult(
        0,
        0,
        blocked.blocked(),
        blocked.blockEntity(),
        0,
        blocked.flags(),
        0,
        direction,
        Vec3f.ZERO);
  }

  private static Vec3f horizontalDelta(Vec3f target, Vec3f origin) {
    return Objects.requireNonNull(new Vec3f(target.x() - origin.x(), target.y() - origin.y(), 0));
  }

  private static void validate(MovementInit input, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.BARRIER_JUMP))
      throw new IllegalArgumentException("Non-barrier reach execution");
  }
}
