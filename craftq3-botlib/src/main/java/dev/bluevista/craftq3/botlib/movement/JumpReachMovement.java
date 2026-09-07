package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** Measured type-5 approach, run/jump commands and cached airborne completion. */
public final class JumpReachMovement {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final RunStart runStart;
  private final ToIntFunction<Vec3> pointArea;

  @FunctionalInterface
  public interface RunStart {
    Vec3 calculate(Vec3 start, Vec3 end);
  }

  public record Move(Vec3 direction, float speed) implements ReachMovementOutput.Move {}

  public record Output(
      MovementResult result, Optional<Move> movement, int actionFlags, int jumpReach)
      implements StatefulReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
    }
  }

  public JumpReachMovement(RunStart runStart, ToIntFunction<Vec3> pointArea) {
    this.runStart = Objects.requireNonNull(runStart);
    this.pointArea = Objects.requireNonNull(pointArea);
  }

  public Output execute(
      MovementInit input,
      int effectiveFlags,
      int reachArea,
      Reachability reach,
      int lastReach,
      int jumpReach) {
    validate(input, reach, lastReach, jumpReach);
    if (reachArea < 0) throw new IllegalArgumentException("Negative stored reach area");
    Vec3 start = MovementAbi.vector(reach.start());
    Vec3 run = MovementAbi.vector(runStart.calculate(start, reach.end()));
    var backwards = direction(run, start);
    Vec3 approach = start;
    for (int step = 10; step <= 80; step += 10) {
      Vec3 sample =
          new Vec3(
              (float) start.x() + (float) backwards.vector().x() * step,
              (float) start.y() + (float) backwards.vector().y() * step,
              (float) start.z() + 2);
      if (pointArea.applyAsInt(sample) != reachArea) break;
      approach = sample;
    }
    var fromStart = direction(input.origin(), start);
    var fromApproach = direction(input.origin(), approach);
    float dot =
        (float) fromStart.vector().x() * (float) fromApproach.vector().x()
            + (float) fromStart.vector().y() * (float) fromApproach.vector().y();
    // The measured boundary includes float(-.8), which lies just below the double constant.
    if (dot < -.8 || fromApproach.distance() < 5) {
      Vec3 movement = direction(reach.end(), input.origin()).vector();
      int action = fromStart.distance() < 24 ? 16 : fromStart.distance() < 32 ? 32768 : 0;
      return output(movement, 400, action, lastReach);
    }
    var movement = direction(approach, input.origin());
    return output(
        movement.vector(), 400 - (400 - Math.min(movement.distance(), 80) * 5), 0, jumpReach);
  }

  public Output finish(
      MovementInit input,
      int effectiveFlags,
      int reachArea,
      Reachability reach,
      int lastReach,
      int jumpReach) {
    validate(input, reach, lastReach, jumpReach);
    if (reachArea < 0) throw new IllegalArgumentException("Negative stored reach area");
    if (jumpReach == 0) return new Output(result(ZERO), Optional.empty(), 0, jumpReach);
    var target = direction(reach.end(), input.origin());
    var heading = direction(reach.end(), reach.start());
    float dot =
        (float) target.vector().x() * (float) heading.vector().x()
            + (float) target.vector().y() * (float) heading.vector().y();
    if (target.distance() < 24 && dot < -.5)
      return new Output(result(ZERO), Optional.empty(), 0, jumpReach);
    return output(target.vector(), 400, 0, jumpReach);
  }

  private static Output output(Vec3 direction, float speed, int action, int jumpReach) {
    return new Output(
        result(direction), Optional.of(new Move(direction, speed)), action, jumpReach);
  }

  private static MovementResult result(Vec3 direction) {
    return new MovementResult(0, 0, 0, 0, 0, 0, 0, direction, ZERO);
  }

  private static void validate(
      MovementInit input, Reachability reach, int lastReach, int jumpReach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 5)
      throw new UnsupportedOperationException("Jump executor requires travel type 5");
    if (lastReach < 0 || jumpReach < 0)
      throw new IllegalArgumentException("Negative jump reachability history");
  }

  private record Direction(Vec3 vector, float distance) {}

  private static Direction direction(Vec3 target, Vec3 origin) {
    float x = (float) target.x() - (float) origin.x();
    float y = (float) target.y() - (float) origin.y();
    float squared = x * x + y * y;
    if (!Float.isFinite(squared))
      throw new IllegalArgumentException("Jump direction exceeds float range");
    if (squared == 0) return new Direction(new Vec3(x, y, 0), 0);
    float inverse = 1 / (float) Math.sqrt(squared);
    return new Direction(new Vec3(x * inverse, y * inverse, 0), squared * inverse);
  }
}
