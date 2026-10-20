package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Native-observed direct elevator commands. The guest remains responsible for physics. */
public final class ElevatorMovement {
  private final MoverQueries movers;
  private final IntFunction<MoverQueries.ModelBounds> modelBounds;

  private record Geometry(Vec3f center, float top) {}

  private final MovementObstruction obstruction;
  private final BarrierCheck barrier;

  @FunctionalInterface
  public interface BarrierCheck {
    boolean check(MovementInit input, Vec3f direction, float speed);
  }

  public record Move(Vec3f direction, float speed) implements ReachMovementOutput.Move {
    public Move {
      direction = Objects.requireNonNull(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid platform movement speed");
    }
  }

  public record Output(
      MovementResult result,
      Optional<ElevatorMovement.Move> movement,
      int actionFlags,
      int movementFlags,
      boolean clearReachDeadline)
      implements FlaggedReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
    }

    public Output(
        MovementResult result,
        Optional<ElevatorMovement.Move> movement,
        int actionFlags,
        int movementFlags) {
      this(result, movement, actionFlags, movementFlags, false);
    }
  }

  public ElevatorMovement(
      MoverQueries movers,
      IntFunction<MoverQueries.ModelBounds> modelBounds,
      MovementObstruction obstruction,
      BarrierCheck barrier) {
    this.movers = Objects.requireNonNull(movers);
    this.modelBounds = Objects.requireNonNull(modelBounds);
    this.obstruction = Objects.requireNonNull(obstruction);
    this.barrier = Objects.requireNonNull(barrier);
  }

  /** Returns only direct-travel effects; it does not update route history or simulate physics. */
  public Output execute(MovementInit input, int flags, int sourceArea, Reachability reach) {
    validate(input, reach);
    if (movers.onMover(input.origin(), input.entity(), reach)) {
      if (Math.abs(input.origin().z() - reach.end().z()) < 32) {
        Vec3f direction = Normalized.of(delta(reach.end(), input.origin(), true)).direction();
        return barrier(
            input,
            flags,
            result(direction, 0, 0),
            Optional.of(new Move(direction, 400)),
            direction,
            100);
      }
      return center(input, flags, geometry(input, reach).center());
    }

    boolean swimming = (flags & 4) != 0;
    Vec3f endDelta = delta(reach.end(), input.origin(), false);
    float endDistance = length(endDelta);
    if (endDistance < 64) {
      Optional<Move> movement = move(endDelta, ramp(endDistance));
      if (swimming) return new Output(result(endDelta, 0, 2), movement, 0, flags, true);
      var output = barrier(input, flags, result(endDelta, 0, 0), movement, endDelta, 50);
      return new Output(
          output.result(), output.movement(), output.actionFlags(), output.movementFlags(), true);
    }

    Normalized approach = Normalized.of(delta(reach.start(), input.origin(), !swimming));
    int type = 0, resultFlags = swimming ? 2 : 0;
    if (geometry(input, reach).top() >= reach.start().z()) {
      type = 1;
      resultFlags |= 4;
    } else {
      Vec3f boarding = geometry(input, reach).center();
      Normalized center = Normalized.of(delta(boarding, input.origin(), !swimming));
      float dot =
          approach.direction().x() * center.direction().x()
              + approach.direction().y() * center.direction().y()
              + approach.direction().z() * center.direction().z();
      if (approach.length() < 20 || center.length() < approach.length() || dot < 0) {
        approach = center;
      }
    }
    MovementResult blocked = obstruction.check(input, sourceArea, approach.direction(), false);
    var result =
        new MovementResult(
            0,
            type,
            blocked.blocked(),
            blocked.blockEntity(),
            0,
            resultFlags | blocked.flags(),
            0,
            approach.direction(),
            Vec3f.ZERO);
    if (swimming) return new Output(result, Optional.empty(), 0, flags);
    return barrier(
        input,
        flags,
        result,
        type == 0
            ? Optional.of(
                new Move(approach.direction(), 400 - (400 - Math.min(approach.length(), 60) * 6)))
            : move(approach.direction(), ramp(approach.length())),
        approach.direction(),
        50);
  }

  /** Completion retains result fields and state, while always issuing one speed-300 EA move. */
  public Output finish(MovementInit input, int flags, int sourceArea, Reachability reach) {
    var geometry = geometry(input, reach);
    Vec3f target =
        Math.abs(input.origin().z() - reach.start().z())
                < Math.abs(input.origin().z() - reach.end().z())
            ? geometry.center()
            : reach.end();
    return new Output(
        result(Vec3f.ZERO, 0, 0),
        Optional.of(new Move(Normalized.of(delta(target, input.origin(), false)).direction(), 300)),
        0,
        flags);
  }

  private Output center(MovementInit input, int flags, Vec3f center) {
    Normalized normalized = Normalized.of(delta(center, input.origin(), true));
    if (normalized.length() <= 10)
      return new Output(result(Vec3f.ZERO, 0, 0), Optional.empty(), 0, flags);
    float speed = 400 - (400 - Math.min(100, normalized.length()) * 4);
    return new Output(
        result(normalized.direction(), 0, 0),
        Optional.of(new Move(normalized.direction(), speed)),
        0,
        flags);
  }

  private Output barrier(
      MovementInit input,
      int flags,
      MovementResult result,
      Optional<Move> movement,
      Vec3f direction,
      float speed) {
    if (barrier.check(input, direction, speed)) {
      Vec3f horizontal = Normalized.of(new Vec3f(direction.x(), direction.y(), 0)).direction();
      return new Output(result, Optional.of(new Move(horizontal, speed)), 16, flags | 1);
    }
    return new Output(result, movement, 0, flags);
  }

  // Separate native operations request bounds lazily; do not prefetch or combine these calls.
  private Geometry geometry(MovementInit input, Reachability reach) {
    validate(input, reach);
    int model = reach.face() & 65535;
    var offset =
        movers
            .originForModel(model)
            .orElseThrow(
                () -> new UnsupportedOperationException("Missing elevator mover model " + model));
    var b = Objects.requireNonNull(modelBounds.apply(model));
    Vec3f center =
        new Vec3f(
            (b.min().x() + b.max().x()) * .5f + offset.x(),
            (b.min().y() + b.max().y()) * .5f + offset.y(),
            reach.start().z());
    float top = b.max().z() + offset.z();
    if (!Float.isFinite(top))
      throw new IllegalArgumentException("Elevator height exceeds float range");
    return new Geometry(center, top);
  }

  private static void validate(MovementInit input, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.ELEVATOR))
      throw new IllegalArgumentException("Non-elevator reach execution");
  }

  private static Optional<Move> move(Vec3f direction, float speed) {
    return speed > 5 ? Optional.of(new Move(direction, speed)) : Optional.empty();
  }

  private static float ramp(float distance) {
    return 360 - (360 - Math.min(distance, 60) * 6);
  }

  private static MovementResult result(Vec3f direction, int type, int flags) {
    return new MovementResult(0, type, 0, 0, 0, flags, 0, direction, Vec3f.ZERO);
  }

  private static float length(Vec3f delta) {
    float squared = delta.lengthSquared();
    if (!Float.isFinite(squared))
      throw new IllegalArgumentException("Platform vector exceeds float range");
    return (float) Math.sqrt(squared);
  }

  private static Vec3f delta(Vec3f target, Vec3f origin, boolean horizontal) {
    return new Vec3f(
        target.x() - origin.x(), target.y() - origin.y(), horizontal ? 0 : target.z() - origin.z());
  }
}
