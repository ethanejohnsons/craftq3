package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;

/**
 * LADDER travel ({@code BotTravel_Ladder}): look up or down the ladder towards the reach end, with
 * the view pitch exaggerated three times, and climb by moving forward with a zero move vector. Used
 * both while standing and while hanging on the ladder.
 */
public final class LadderMovement {
  public record Move(Vec3f direction, float speed) implements ReachMovementOutput.Move {}

  public record Output(
      MovementResult result, Optional<LadderMovement.Move> movement, int actionFlags)
      implements ReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
    }
  }

  public Output execute(MovementInit input, int flags, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.LADDER))
      throw new IllegalArgumentException("Non-ladder reach execution");
    Vec3f direction = reach.end().subtract(input.origin()).normalize();
    Vec3f view = ViewAngles.of(new Vec3f(direction.x(), direction.y(), 3 * direction.z()));
    var result =
        new MovementResult(0, 0, 0, 0, 0, MovementResult.MOVEMENT_VIEW, 0, direction, view);
    return new Output(result, Optional.of(new Move(Vec3f.ZERO, 0)), ActionFlags.MOVE_FORWARD);
  }
}
