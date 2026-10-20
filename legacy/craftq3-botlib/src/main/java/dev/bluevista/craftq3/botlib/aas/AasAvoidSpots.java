package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.botlib.movement.BotMovement;
import dev.bluevista.craftq3.botlib.movement.BotMovement.AvoidSpot;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded native-observed spherical avoidance along an outgoing reachability. */
public final class AasAvoidSpots {
  private final List<AvoidSpot> spots;

  public AasAvoidSpots(List<AvoidSpot> spots) {
    if (spots.size() > BotMovement.MAX_AVOID_SPOTS)
      throw new IllegalArgumentException("Too many movement avoid spots");
    this.spots = List.copyOf(spots);
  }

  /**
   * Returns the last intersected type, stopping immediately at AVOID_ALWAYS (1). Any nonzero result
   * excludes a movement candidate. In particular, type 2 does not promise a fallback.
   */
  public int type(Vec3 origin, Reachability reachability) {
    Objects.requireNonNull(reachability);
    Point from = Point.of(origin),
        start = Point.of(reachability.start()),
        end = Point.of(reachability.end());
    boolean approachOnly =
        switch (reachability.baseTravelType()) {
          case 5, 7, 10, 11, 12, 13, 14, 18, 19 -> true;
          default -> false;
        };
    int result = 0;
    for (var spot : spots) {
      Point center = Point.of(spot.origin());
      float radiusSquared = spot.radius() * spot.radius();
      float distance = segmentDistance(center, from, start);
      boolean hit = distance < radiusSquared && (approachOnly || center.distance(from) > distance);
      if (!approachOnly) {
        distance = segmentDistance(center, start, end);
        hit |= distance < radiusSquared && center.distance(start) > distance;
      }
      if (hit) {
        result = spot.type();
        if (result == BotMovement.AVOID_ALWAYS) return result;
      }
    }
    return result;
  }

  private static float segmentDistance(Point point, Point start, Point end) {
    Point direction = end.minus(start);
    float lengthSquared = direction.dot(direction);
    // Native arithmetic leaves a zero-length segment nonintersecting, even inside a sphere.
    if (lengthSquared == 0) return Float.NaN;
    float fraction = point.minus(start).dot(direction) / lengthSquared;
    if (fraction <= 0) return point.distance(start);
    if (fraction >= 1) return point.distance(end);
    return point.distance(
        new Point(
            start.x + fraction * direction.x,
            start.y + fraction * direction.y,
            start.z + fraction * direction.z));
  }

  private record Point(float x, float y, float z) {
    static Point of(Vec3 value) {
      Objects.requireNonNull(value);
      if (!Float.isFinite((float) value.x())
          || !Float.isFinite((float) value.y())
          || !Float.isFinite((float) value.z()))
        throw new IllegalArgumentException("Avoid-spot point exceeds float range");
      return new Point((float) value.x(), (float) value.y(), (float) value.z());
    }

    Point minus(Point other) {
      return new Point(x - other.x, y - other.y, z - other.z);
    }

    float dot(Point other) {
      return x * other.x + y * other.y + z * other.z;
    }

    float distance(Point other) {
      Point delta = minus(other);
      return delta.dot(delta);
    }
  }
}
