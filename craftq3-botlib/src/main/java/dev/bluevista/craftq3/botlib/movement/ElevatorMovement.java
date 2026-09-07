package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Native-observed direct elevator commands. The guest remains responsible for physics. */
public final class ElevatorMovement {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final MoverQueries movers;
  private final IntFunction<MoverQueries.ModelBounds> modelBounds;

  private record Geometry(Vec3 center, float top) {}

  private final MovementObstruction obstruction;
  private final BarrierCheck barrier;

  @FunctionalInterface
  public interface BarrierCheck {
    boolean check(MovementInit input, Vec3 direction, float speed);
  }

  public record Move(Vec3 direction, float speed) implements ReachMovementOutput.Move {
    public Move {
      direction = MovementAbi.vector(direction);
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
      if (Math.abs((float) input.origin().z() - (float) reach.end().z()) < 32) {
        Vec3 direction = normalize(delta(reach.end(), input.origin(), true)).direction;
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
    Vec3 endDelta = delta(reach.end(), input.origin(), false);
    float endDistance = length(endDelta);
    if (endDistance < 64) {
      Optional<Move> movement = move(endDelta, ramp(endDistance));
      if (swimming) return new Output(result(endDelta, 0, 2), movement, 0, flags, true);
      var output = barrier(input, flags, result(endDelta, 0, 0), movement, endDelta, 50);
      return new Output(
          output.result(), output.movement(), output.actionFlags(), output.movementFlags(), true);
    }

    Normal approach = normalize(delta(reach.start(), input.origin(), !swimming));
    int type = 0, resultFlags = swimming ? 2 : 0;
    if (geometry(input, reach).top() >= (float) reach.start().z()) {
      type = 1;
      resultFlags |= 4;
    } else {
      Vec3 boarding = geometry(input, reach).center();
      Normal center = normalize(delta(boarding, input.origin(), !swimming));
      float dot =
          (float) approach.direction.x() * (float) center.direction.x()
              + (float) approach.direction.y() * (float) center.direction.y()
              + (float) approach.direction.z() * (float) center.direction.z();
      if (approach.distance < 20 || center.distance < approach.distance || dot < 0) {
        approach = center;
      }
    }
    MovementResult blocked = obstruction.check(input, sourceArea, approach.direction, false);
    var result =
        new MovementResult(
            0,
            type,
            blocked.blocked(),
            blocked.blockEntity(),
            0,
            resultFlags | blocked.flags(),
            0,
            approach.direction,
            ZERO);
    if (swimming) return new Output(result, Optional.empty(), 0, flags);
    return barrier(
        input,
        flags,
        result,
        type == 0
            ? Optional.of(
                new Move(approach.direction, 400 - (400 - Math.min(approach.distance, 60) * 6)))
            : move(approach.direction, ramp(approach.distance)),
        approach.direction,
        50);
  }

  /** Completion retains result fields and state, while always issuing one speed-300 EA move. */
  public Output finish(MovementInit input, int flags, int sourceArea, Reachability reach) {
    var geometry = geometry(input, reach);
    Vec3 target =
        Math.abs((float) input.origin().z() - (float) reach.start().z())
                < Math.abs((float) input.origin().z() - (float) reach.end().z())
            ? geometry.center()
            : reach.end();
    return new Output(
        result(ZERO, 0, 0),
        Optional.of(new Move(normalize(delta(target, input.origin(), false)).direction, 300)),
        0,
        flags);
  }

  private Output center(MovementInit input, int flags, Vec3 center) {
    Normal normalized = normalize(delta(center, input.origin(), true));
    if (normalized.distance <= 10)
      return new Output(result(ZERO, 0, 0), Optional.empty(), 0, flags);
    float speed = 400 - (400 - Math.min(100, normalized.distance) * 4);
    return new Output(
        result(normalized.direction, 0, 0),
        Optional.of(new Move(normalized.direction, speed)),
        0,
        flags);
  }

  private Output barrier(
      MovementInit input,
      int flags,
      MovementResult result,
      Optional<Move> movement,
      Vec3 direction,
      float speed) {
    if (barrier.check(input, direction, speed)) {
      Vec3 horizontal = normalize(new Vec3(direction.x(), direction.y(), 0)).direction;
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
    Vec3 center =
        new Vec3(
            ((float) b.min().x() + (float) b.max().x()) * .5f + (float) offset.x(),
            ((float) b.min().y() + (float) b.max().y()) * .5f + (float) offset.y(),
            reach.start().z());
    float top = (float) b.max().z() + (float) offset.z();
    if (!Float.isFinite(top))
      throw new IllegalArgumentException("Elevator height exceeds float range");
    return new Geometry(center, top);
  }

  private static void validate(MovementInit input, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 11)
      throw new UnsupportedOperationException("Non-elevator reach execution");
  }

  private static Optional<Move> move(Vec3 direction, float speed) {
    return speed > 5 ? Optional.of(new Move(direction, speed)) : Optional.empty();
  }

  private static float ramp(float distance) {
    return 360 - (360 - Math.min(distance, 60) * 6);
  }

  private static MovementResult result(Vec3 direction, int type, int flags) {
    return new MovementResult(0, type, 0, 0, 0, flags, 0, direction, ZERO);
  }

  private record Normal(Vec3 direction, float distance) {}

  private static Normal normalize(Vec3 delta) {
    float squared = squared(delta), length = (float) Math.sqrt(squared);
    float inverse = length == 0 ? 0 : 1 / length;
    return new Normal(
        new Vec3(
            (float) delta.x() * inverse, (float) delta.y() * inverse, (float) delta.z() * inverse),
        squared * inverse);
  }

  private static float length(Vec3 delta) {
    return (float) Math.sqrt(squared(delta));
  }

  private static float squared(Vec3 delta) {
    float x = (float) delta.x(), y = (float) delta.y(), z = (float) delta.z();
    float result = x * x + y * y + z * z;
    if (!Float.isFinite(result))
      throw new IllegalArgumentException("Platform vector exceeds float range");
    return result;
  }

  private static Vec3 delta(Vec3 target, Vec3 origin, boolean horizontal) {
    return new Vec3(
        (float) target.x() - (float) origin.x(),
        (float) target.y() - (float) origin.y(),
        horizontal ? 0 : (float) target.z() - (float) origin.z());
  }
}
