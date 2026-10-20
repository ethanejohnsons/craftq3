package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** Measured type-5 approach, run/jump commands and cached airborne completion. */
public final class JumpReachMovement {
  private final RunStart runStart;
  private final ToIntFunction<Vec3f> pointArea;

  @FunctionalInterface
  public interface RunStart {
    Vec3f calculate(Vec3f start, Vec3f end);
  }

  public record Move(Vec3f direction, float speed) implements ReachMovementOutput.Move {}

  public record Output(
      MovementResult result, Optional<Move> movement, int actionFlags, int jumpReach)
      implements StatefulReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
    }
  }

  public JumpReachMovement(RunStart runStart, ToIntFunction<Vec3f> pointArea) {
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
    Vec3f start = Objects.requireNonNull(reach.start());
    Vec3f run = Objects.requireNonNull(runStart.calculate(start, reach.end()));
    var backwards = direction(run, start);
    Vec3f approach = start;
    for (int step = 10; step <= 80; step += 10) {
      Vec3f sample =
          new Vec3f(
              start.x() + backwards.direction().x() * step,
              start.y() + backwards.direction().y() * step,
              start.z() + 2);
      if (pointArea.applyAsInt(sample) != reachArea) break;
      approach = sample;
    }
    var fromStart = direction(input.origin(), start);
    var fromApproach = direction(input.origin(), approach);
    float dot =
        fromStart.direction().x() * fromApproach.direction().x()
            + fromStart.direction().y() * fromApproach.direction().y();
    // The measured boundary includes float(-.8), which lies just below the double constant.
    if (dot < -.8 || fromApproach.length() < 5) {
      Vec3f movement = direction(reach.end(), input.origin()).direction();
      int action = fromStart.length() < 24 ? 16 : fromStart.length() < 32 ? 32768 : 0;
      return output(movement, 400, action, lastReach);
    }
    var movement = direction(approach, input.origin());
    return output(
        movement.direction(), 400 - (400 - Math.min(movement.length(), 80) * 5), 0, jumpReach);
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
    if (jumpReach == 0) return new Output(result(Vec3f.ZERO), Optional.empty(), 0, jumpReach);
    var target = direction(reach.end(), input.origin());
    var heading = direction(reach.end(), reach.start());
    float dot =
        target.direction().x() * heading.direction().x()
            + target.direction().y() * heading.direction().y();
    if (target.length() < 24 && dot < -.5)
      return new Output(result(Vec3f.ZERO), Optional.empty(), 0, jumpReach);
    return output(target.direction(), 400, 0, jumpReach);
  }

  private static Output output(Vec3f direction, float speed, int action, int jumpReach) {
    return new Output(
        result(direction), Optional.of(new Move(direction, speed)), action, jumpReach);
  }

  private static MovementResult result(Vec3f direction) {
    return new MovementResult(0, 0, 0, 0, 0, 0, 0, direction, Vec3f.ZERO);
  }

  private static void validate(
      MovementInit input, Reachability reach, int lastReach, int jumpReach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.JUMP))
      throw new UnsupportedOperationException("Jump executor requires travel type 5");
    if (lastReach < 0 || jumpReach < 0)
      throw new IllegalArgumentException("Negative jump reachability history");
  }

  private static Normalized direction(Vec3f target, Vec3f origin) {
    return Normalized.horizontallyTowards(target, origin);
  }
}
