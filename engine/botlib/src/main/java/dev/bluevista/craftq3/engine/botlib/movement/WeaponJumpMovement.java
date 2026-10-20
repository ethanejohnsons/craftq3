package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Independently observed rocket/BFG jump commands; damage, ammunition and physics belong to the VM.
 */
public final class WeaponJumpMovement {
  private final MovementSettings settings;

  public WeaponJumpMovement() {
    this(MovementSettings.DEFAULT);
  }

  /** Uses the weapon numbers ({@code weapindex_*}) and {@code sv_gravity} from the settings. */
  public WeaponJumpMovement(MovementSettings settings) {
    this.settings = Objects.requireNonNull(settings);
  }

  public record Move(Vec3f direction, float speed) implements ReachMovementOutput.Move {
    public Move {
      direction = Objects.requireNonNull(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid weapon-jump movement speed");
    }
  }

  public record Output(
      MovementResult result,
      Optional<Move> movement,
      int actionFlags,
      int jumpReach,
      Optional<Vec3f> view,
      OptionalInt weapon)
      implements StatefulReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
      view = Objects.requireNonNull(view);
      Objects.requireNonNull(weapon);
      if (jumpReach < 0) throw new IllegalArgumentException("Negative jump reachability");
    }
  }

  public Output execute(
      MovementInit input,
      int effectiveFlags,
      int sourceArea,
      Reachability reach,
      int lastReach,
      int jumpReach) {
    validate(input, sourceArea, reach, lastReach, jumpReach);
    var approach = horizontal(input.origin(), reach.start());
    Vec3f direction = approach.direction();
    float yaw = yaw(direction);
    float speed = 400 - (400 - Math.min(approach.length(), 80) * 5);
    int actions = 0;
    boolean rocket = reach.is(TravelType.ROCKET_JUMP);
    int selected = rocket ? settings.rocketLauncher() : settings.bfg();
    // Native rocket and BFG approaches use distinct input alignment tests.
    float inputPitchTarget = rocket ? 90 : 0;
    float inputYawTarget = rocket ? yaw : 0;
    if (approach.length() < 5
        && Math.abs(angleDifference(input.viewAngles().x(), inputPitchTarget)) < 5
        && Math.abs(angleDifference(input.viewAngles().y(), inputYawTarget)) < 5) {
      direction = horizontal(input.origin(), reach.end()).direction();
      yaw = yaw(direction);
      speed = 400;
      actions = 1 | 16;
      jumpReach = lastReach;
    }
    Vec3f view = new Vec3f(90, yaw, 0);
    var result = new MovementResult(0, 0, 0, 0, 0, 24, selected, direction, view);
    return new Output(
        result,
        Optional.of(new Move(direction, speed)),
        actions,
        jumpReach,
        Optional.of(view),
        OptionalInt.of(selected));
  }

  public Output finish(
      MovementInit input,
      int effectiveFlags,
      int sourceArea,
      Reachability reach,
      int lastReach,
      int jumpReach) {
    validate(input, sourceArea, reach, lastReach, jumpReach);
    if (jumpReach == 0)
      return new Output(
          MovementResult.CLEAR, Optional.empty(), 0, 0, Optional.empty(), OptionalInt.empty());
    var steering =
        BotAirControl.control(input.origin(), input.velocity(), reach.end(), settings.gravity());
    // Out of reach of steering: head straight for the reach end, as the original does.
    if (!steering.success()) steering = BotAirControl.straight(input.origin(), reach.end());
    var result = new MovementResult(0, 0, 0, 0, 0, 0, 0, steering.direction(), Vec3f.ZERO);
    return new Output(
        result,
        Optional.of(new Move(steering.direction(), Math.clamp(steering.speed(), 0, 400))),
        0,
        jumpReach,
        Optional.empty(),
        OptionalInt.empty());
  }

  private static Normalized horizontal(Vec3f origin, Vec3f target) {
    return Normalized.horizontallyTowards(target, origin);
  }

  private static float yaw(Vec3f direction) {
    float x = direction.x(), y = direction.y();
    if (x == 0 && y == 0) return 0;
    float angle = (float) (Math.atan2(y, x) * 180 / Math.PI);
    return angle < 0 ? angle + 360 : angle;
  }

  private static float angleDifference(float first, float second) {
    float difference = first - second;
    if (difference > 180) difference -= 360;
    else if (difference < -180) difference += 360;
    return difference;
  }

  private static void validate(
      MovementInit input, int sourceArea, Reachability reach, int lastReach, int jumpReach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.ROCKET_JUMP) && !reach.is(TravelType.BFG_JUMP))
      throw new IllegalArgumentException("Non-weapon-jump reach execution");
    if (sourceArea < 0
        || lastReach < 0
        || jumpReach < 0
        || input.entity() < 0
        || input.entity() >= 1024
        || (input.presenceType() != 2 && input.presenceType() != 4))
      throw new IllegalArgumentException("Invalid weapon-jump movement state");
  }
}
