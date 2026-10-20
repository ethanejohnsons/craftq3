package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.movement.BotMovement;
import dev.bluevista.craftq3.engine.botlib.movement.BotMovement.AvoidSpot;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
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
  public int type(Vec3f origin, Reachability reachability) {
    Objects.requireNonNull(reachability);
    Vec3f from = finite(origin), start = finite(reachability.start());
    Vec3f end = finite(reachability.end());
    boolean approachOnly =
        switch (reachability.travel()) {
          case JUMP,
              WALK_OFF_LEDGE,
              TELEPORT,
              ELEVATOR,
              ROCKET_JUMP,
              BFG_JUMP,
              GRAPPLE_HOOK,
              JUMP_PAD,
              FUNC_BOB ->
              true;
          default -> false;
        };
    int result = 0;
    for (var spot : spots) {
      Vec3f center = finite(spot.origin());
      float radiusSquared = spot.radius() * spot.radius();
      float distance = segmentDistance(center, from, start);
      boolean hit = distance < radiusSquared && (approachOnly || squared(center, from) > distance);
      if (!approachOnly) {
        distance = segmentDistance(center, start, end);
        hit |= distance < radiusSquared && squared(center, start) > distance;
      }
      if (hit) {
        result = spot.type();
        if (result == BotMovement.AVOID_ALWAYS) return result;
      }
    }
    return result;
  }

  /** Squared distance from {@code point} to the segment, NaN for a zero-length segment. */
  private static float segmentDistance(Vec3f point, Vec3f start, Vec3f end) {
    Vec3f direction = end.subtract(start);
    float lengthSquared = direction.lengthSquared();
    // Native arithmetic leaves a zero-length segment nonintersecting, even inside a sphere.
    if (lengthSquared == 0) return Float.NaN;
    float fraction = point.subtract(start).dot(direction) / lengthSquared;
    if (fraction <= 0) return squared(point, start);
    if (fraction >= 1) return squared(point, end);
    return squared(point, start.multiplyAdd(fraction, direction));
  }

  private static float squared(Vec3f a, Vec3f b) {
    return a.subtract(b).lengthSquared();
  }

  private static Vec3f finite(Vec3f value) {
    if (!Objects.requireNonNull(value).isFinite())
      throw new IllegalArgumentException("Avoid-spot point exceeds float range");
    return value;
  }
}
