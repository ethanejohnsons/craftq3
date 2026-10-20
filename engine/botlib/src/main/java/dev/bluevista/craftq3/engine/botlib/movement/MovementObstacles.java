package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/** Bounded native-observed barrier and gap queries; no movement actions or state mutation. */
public final class MovementObstacles {
  private final AasMovementPredictor.World world;
  private final float stepHeight, maxBarrier;

  public MovementObstacles(AasMovementPredictor.World world) {
    this(world, 18, 32);
  }

  public MovementObstacles(AasMovementPredictor.World world, float stepHeight, float maxBarrier) {
    this.world = Objects.requireNonNull(world);
    if (!Float.isFinite(stepHeight)
        || !Float.isFinite(maxBarrier)
        || stepHeight < 0
        || maxBarrier < 0
        || stepHeight > 100_000
        || maxBarrier > 100_000)
      throw new IllegalArgumentException("Invalid obstacle movement setting");
    this.stepHeight = stepHeight;
    this.maxBarrier = maxBarrier;
  }

  /** Zero means no dry drop detected, one means already unsupported, otherwise distance 8..96. */
  public float gapDistance(Vec3f origin, Vec3f direction, int entity) {
    origin = Objects.requireNonNull(origin);
    direction = Objects.requireNonNull(direction);
    var first = world.trace(origin, vertical(origin, origin.z() - 60), 4, entity);
    if (first.fraction() == 1) return 1;
    float floor = first.endPosition().z() + 1;
    for (int distance = 8; distance < 100; distance += 8) {
      var start =
          new Vec3f(
              origin.x() + distance * direction.x(),
              origin.y() + distance * direction.y(),
              floor + 24);
      var trace = world.trace(start, vertical(start, start.z() - 80), 4, entity);
      if (trace.startSolid()) continue;
      float next = trace.endPosition().z();
      if (start.z() - next > 50) {
        int contents = world.contents(vertical(trace.endPosition(), next - 20));
        return (contents & 32) != 0 ? 0 : distance;
      }
      floor = next;
    }
    return 0;
  }

  /** Tests clearance and a landing at least stepHeight above the original position. */
  public boolean barrierJump(MovementInit input, Vec3f direction, float speed) {
    Objects.requireNonNull(input);
    direction = horizontal(direction);
    if (!Float.isFinite(speed) || Math.abs(speed) > 1_000_000)
      throw new IllegalArgumentException("Invalid obstacle movement speed");
    var origin = input.origin();
    var up = world.trace(origin, vertical(origin, origin.z() + maxBarrier), 2, input.entity());
    if (up.startSolid() || up.endPosition().z() - origin.z() < stepHeight) return false;
    double distance = (speed * input.thinkTime()) * .5;
    var start = up.endPosition();
    var target =
        new Vec3f(
            (float) (start.x() + direction.x() * distance),
            (float) (start.y() + direction.y() * distance),
            start.z());
    var across = world.trace(start, target, 2, input.entity());
    if (across.startSolid()) return false;
    var down =
        world.trace(
            across.endPosition(), vertical(across.endPosition(), origin.z()), 2, input.entity());
    return !down.startSolid()
        && down.fraction() < 1
        && down.endPosition().z() - origin.z() >= stepHeight;
  }

  static Vec3f horizontal(Vec3f direction) {
    direction = Objects.requireNonNull(direction);
    float x = direction.x(), y = direction.y();
    float length = (float) Math.sqrt(x * x + y * y);
    if (length != 0) {
      float inverse = 1 / length;
      x *= inverse;
      y *= inverse;
    }
    return new Vec3f(x, y, 0);
  }

  private static Vec3f vertical(Vec3f point, float z) {
    return new Vec3f(point.x(), point.y(), z);
  }
}
