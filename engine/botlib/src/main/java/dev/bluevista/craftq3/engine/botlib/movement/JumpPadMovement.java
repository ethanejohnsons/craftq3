package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.ea.BotInput;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;

/** Grounded jump-pad approach; launch physics and airborne steering are separate services. */
public final class JumpPadMovement {
  private final MovementObstruction obstruction;
  private final float airGravity;

  public JumpPadMovement(MovementObstruction obstruction) {
    this(obstruction, BotAirControl.DEFAULT_GRAVITY);
  }

  /** {@code airGravity} is {@code sv_gravity}, which airborne steering projects with. */
  public JumpPadMovement(MovementObstruction obstruction, float airGravity) {
    this.obstruction = Objects.requireNonNull(obstruction);
    this.airGravity = airGravity;
  }

  public GroundReachMovement.Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.JUMP_PAD))
      throw new IllegalArgumentException("Expected jump-pad reachability");
    // Native entry retains the horizontal displacement, including its magnitude, in EA_Move.
    float x = reach.start().x() - input.origin().x();
    float y = reach.start().y() - input.origin().y();
    if (Math.abs(x) > BotInput.MAX_DIRECTION_COMPONENT
        || Math.abs(y) > BotInput.MAX_DIRECTION_COMPONENT)
      throw new IllegalArgumentException("Jump-pad direction outside action bounds");
    var direction = new Vec3f(x, y, 0);
    var blocked = obstruction.check(input, sourceArea, direction);
    var result =
        new MovementResult(
            0,
            0,
            blocked.blocked(),
            blocked.blockEntity(),
            0,
            blocked.flags(),
            0,
            direction,
            Vec3f.ZERO);
    return new GroundReachMovement.Output(result, direction, 400, 0);
  }

  /**
   * Airborne steering uses the reach endpoint without changing route history or launch velocity.
   */
  public GroundReachMovement.Output finish(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.JUMP_PAD))
      throw new IllegalArgumentException("Expected jump-pad reachability");
    var steering = BotAirControl.control(input.origin(), input.velocity(), reach.end(), airGravity);
    // Out of reach of steering: head straight for the reach end, as the original does.
    if (!steering.success()) steering = BotAirControl.straight(input.origin(), reach.end());
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
            Vec3f.ZERO);
    return new GroundReachMovement.Output(
        result, steering.direction(), Math.clamp(steering.speed(), 0, 400), 0);
  }
}
