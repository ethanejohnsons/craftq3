package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;

/** Independently observed BARRIERJUMP entry/completion commands; the VM owns jumping physics. */
public final class BarrierReachMovement {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final MovementResult CLEAR = new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO);
  private final MovementObstruction obstruction;

  public record Move(Vec3 direction, float speed) implements ReachMovementOutput.Move {
    public Move {
      direction = MovementAbi.vector(direction);
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
    Vec3 delta = horizontalDelta(reach.start(), input.origin());
    float x = (float) delta.x(), y = (float) delta.y();
    float squared = x * x + y * y;
    float length = (float) Math.sqrt(squared);
    if (!Float.isFinite(length))
      throw new IllegalArgumentException("Barrier approach exceeds float range");
    float inverse = length == 0 ? 0 : 1 / length;
    float distance = squared * inverse;
    Vec3 direction = new Vec3(x * inverse, y * inverse, 0);
    MovementResult result = checked(input, sourceArea, direction);
    if (distance < 9) return new Output(result, Optional.empty(), 16);
    float speed = 360 - (360 - Math.min(distance, 60) * 6);
    return new Output(result, Optional.of(new Move(direction, speed)), 0);
  }

  public Output finish(MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    validate(input, reach);
    if ((float) input.velocity().z() >= 250) return new Output(CLEAR, Optional.empty(), 0);
    Vec3 direction = horizontalDelta(reach.end(), input.origin());
    return new Output(
        checked(input, sourceArea, direction), Optional.of(new Move(direction, 400)), 0);
  }

  private MovementResult checked(MovementInit input, int sourceArea, Vec3 direction) {
    MovementResult blocked = obstruction.check(input, sourceArea, direction);
    return new MovementResult(
        0, 0, blocked.blocked(), blocked.blockEntity(), 0, blocked.flags(), 0, direction, ZERO);
  }

  private static Vec3 horizontalDelta(Vec3 target, Vec3 origin) {
    return MovementAbi.vector(
        new Vec3(
            (float) target.x() - (float) origin.x(), (float) target.y() - (float) origin.y(), 0));
  }

  private static void validate(MovementInit input, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 4)
      throw new UnsupportedOperationException("Non-barrier reach execution");
  }
}
