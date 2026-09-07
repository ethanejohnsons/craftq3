package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Independently observed rocket/BFG jump commands; damage, ammunition and physics belong to the VM.
 */
public final class WeaponJumpMovement {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final MovementResult CLEAR = new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO);

  public record Move(Vec3 direction, float speed) implements ReachMovementOutput.Move {
    public Move {
      direction = MovementAbi.vector(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid weapon-jump movement speed");
    }
  }

  public record Output(
      MovementResult result,
      Optional<Move> movement,
      int actionFlags,
      int jumpReach,
      Optional<Vec3> view,
      OptionalInt weapon)
      implements StatefulReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
      view = Objects.requireNonNull(view).map(MovementAbi::vector);
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
    Vec3 direction = approach.direction();
    float yaw = yaw(direction);
    float speed = 400 - (400 - Math.min(approach.distance(), 80) * 5);
    int actions = 0;
    int selected = reach.baseTravelType() == 12 ? 5 : 9;
    // Native rocket and BFG approaches use distinct input alignment tests.
    float inputPitchTarget = reach.baseTravelType() == 12 ? 90 : 0;
    float inputYawTarget = reach.baseTravelType() == 12 ? yaw : 0;
    if (approach.distance() < 5
        && Math.abs(angleDifference((float) input.viewAngles().x(), inputPitchTarget)) < 5
        && Math.abs(angleDifference((float) input.viewAngles().y(), inputYawTarget)) < 5) {
      direction = horizontal(input.origin(), reach.end()).direction();
      yaw = yaw(direction);
      speed = 400;
      actions = 1 | 16;
      jumpReach = lastReach;
    }
    Vec3 view = new Vec3(90, yaw, 0);
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
      return new Output(CLEAR, Optional.empty(), 0, 0, Optional.empty(), OptionalInt.empty());
    var steering = BotAirControl.control(input.origin(), input.velocity(), reach.end());
    if (!steering.success())
      throw new UnsupportedOperationException("Unverified failed weapon-jump air control");
    var result = new MovementResult(0, 0, 0, 0, 0, 0, 0, steering.direction(), ZERO);
    return new Output(
        result,
        Optional.of(new Move(steering.direction(), Math.clamp(steering.speed(), 0, 400))),
        0,
        jumpReach,
        Optional.empty(),
        OptionalInt.empty());
  }

  private record Direction(Vec3 direction, float distance) {}

  private static Direction horizontal(Vec3 origin, Vec3 target) {
    float x = (float) target.x() - (float) origin.x();
    float y = (float) target.y() - (float) origin.y();
    float squared = x * x + y * y;
    float root = (float) Math.sqrt(squared);
    if (!Float.isFinite(root))
      throw new IllegalArgumentException("Weapon-jump direction exceeds float range");
    float inverse = root == 0 ? 0 : 1 / root;
    return new Direction(new Vec3(x * inverse, y * inverse, 0), squared * inverse);
  }

  private static float yaw(Vec3 direction) {
    float x = (float) direction.x(), y = (float) direction.y();
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
    if (reach.baseTravelType() != 12 && reach.baseTravelType() != 13)
      throw new UnsupportedOperationException("Non-weapon-jump reach execution");
    if (sourceArea < 0
        || lastReach < 0
        || jumpReach < 0
        || input.entity() < 0
        || input.entity() >= 1024
        || (input.presenceType() != 2 && input.presenceType() != 4))
      throw new IllegalArgumentException("Invalid weapon-jump movement state");
  }
}
