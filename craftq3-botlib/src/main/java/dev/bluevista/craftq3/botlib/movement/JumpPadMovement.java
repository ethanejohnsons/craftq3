package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.botlib.ea.BotInput;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;

/** Grounded jump-pad approach; launch physics and airborne steering are separate services. */
public final class JumpPadMovement {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final MovementObstruction obstruction;

  public JumpPadMovement(MovementObstruction obstruction) {
    this.obstruction = Objects.requireNonNull(obstruction);
  }

  public GroundReachMovement.Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 18)
      throw new IllegalArgumentException("Expected jump-pad reachability");
    // Native entry retains the horizontal displacement, including its magnitude, in EA_Move.
    float x = (float) reach.start().x() - (float) input.origin().x();
    float y = (float) reach.start().y() - (float) input.origin().y();
    if (Math.abs(x) > BotInput.MAX_DIRECTION_COMPONENT
        || Math.abs(y) > BotInput.MAX_DIRECTION_COMPONENT)
      throw new IllegalArgumentException("Jump-pad direction outside action bounds");
    var direction = new Vec3(x, y, 0);
    var blocked = obstruction.check(input, sourceArea, direction);
    var result =
        new MovementResult(
            0, 0, blocked.blocked(), blocked.blockEntity(), 0, blocked.flags(), 0, direction, ZERO);
    return new GroundReachMovement.Output(result, direction, 400, 0);
  }

  /**
   * Airborne steering uses the reach endpoint without changing route history or launch velocity.
   */
  public GroundReachMovement.Output finish(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 18)
      throw new IllegalArgumentException("Expected jump-pad reachability");
    var steering = BotAirControl.control(input.origin(), input.velocity(), reach.end());
    if (!steering.success())
      throw new UnsupportedOperationException("Unverified failed jump-pad air control");
    var blocked = obstruction.check(input, sourceArea, steering.direction());
    var result =
        new MovementResult(
            0,
            0,
            blocked.blocked(),
            blocked.blockEntity(),
            0,
            blocked.flags(),
            0,
            steering.direction(),
            ZERO);
    return new GroundReachMovement.Output(
        result, steering.direction(), Math.clamp(steering.speed(), 0, 400), 0);
  }
}
