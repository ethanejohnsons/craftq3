package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Static presence-hull collision through the AAS's already expanded BSP partition. */
public final class AasPresenceTrace {
  public record Result(
      boolean startSolid,
      float fraction,
      Vec3f endPosition,
      int entity,
      int lastArea,
      int area,
      int planeNumber,
      int nodeVisits) {}

  /** Dynamic collision is evaluated independently within each traversed, permitted leaf. */
  @FunctionalInterface
  public interface AreaCollision {
    Optional<EntityHit> trace(int area, Vec3f start, Vec3f end, int presenceMask);
  }

  public record EntityHit(boolean startSolid, float fraction, Vec3f endPosition, int entity) {
    public EntityHit {
      Objects.requireNonNull(endPosition);
      if (!Float.isFinite(fraction) || fraction < 0 || fraction > 1 || entity < 0 || entity > 1023)
        throw new IllegalArgumentException("Invalid AAS dynamic collision");
    }
  }

  private record Segment(int node, Vec3f start, Vec3f end, int enteringPlane, int sourcePlane) {}

  private final AasWorld map;
  private final int maxNodeVisits;

  public AasPresenceTrace(AasWorld map) {
    this(map, 1_000_000);
  }

  public AasPresenceTrace(AasWorld map, int maxNodeVisits) {
    this.map = Objects.requireNonNull(map);
    if (maxNodeVisits < 1 || maxNodeVisits > 2_000_000)
      throw new IllegalArgumentException("AAS trace work limit out of range");
    this.maxNodeVisits = maxNodeVisits;
  }

  /**
   * Presence bits select pre-expanded traversable areas; zero/node solid and nonmatching areas
   * block. Entity is zero because dynamic entity clipping belongs to the composed world adapter.
   * The reported fraction precedes the final 0.125-unit position backoff, matching the native ABI.
   */
  public Result trace(Vec3f start, Vec3f end, int presenceMask) {
    return trace(start, end, presenceMask, null);
  }

  public Result trace(Vec3f start, Vec3f end, int presenceMask, AreaCollision collision) {
    Vec3f origin = bounded(start), target = bounded(end);
    float totalLength = finite(target.subtract(origin)).length();
    var pending = new ArrayDeque<Segment>();
    pending.push(new Segment(map.nodes().size() > 1 ? 1 : 0, origin, target, 0, 0));
    int visits = 0, lastArea = 0;
    while (!pending.isEmpty()) {
      if (++visits > maxNodeVisits)
        throw new IllegalStateException("AAS presence trace work budget exceeded");
      var part = pending.pop();
      if (part.node() <= 0) {
        int area = -part.node();
        if (area > 0 && (map.areaSettings().get(area).presenceType() & presenceMask) != 0) {
          if (collision != null) {
            var hit =
                Objects.requireNonNull(
                    collision.trace(area, part.start(), part.end(), presenceMask));
            if (hit.isPresent()) {
              var dynamic = hit.orElseThrow();
              if (dynamic.startSolid() || dynamic.fraction() < 1) {
                Vec3f endpoint = bounded(dynamic.endPosition());
                float fraction =
                    dynamic.startSolid() || totalLength == 0
                        ? 0
                        : endpoint.subtract(origin).length() / totalLength;
                return new Result(
                    dynamic.startSolid(),
                    fraction,
                    endpoint,
                    dynamic.entity(),
                    lastArea,
                    0,
                    0,
                    visits);
              }
            }
          }
          lastArea = area;
          continue;
        }
        if (lastArea == 0) return new Result(true, 0, origin, 0, 0, area, 0, visits);
        Vec3f delta = finite(part.start().subtract(origin));
        float distance = delta.length();
        // An exact margin split can visit a permitted leaf without advancing the ray.
        // Native start-solid results retain that leaf and the raw partition plane.
        if (distance == 0)
          return new Result(true, 0, origin, 0, lastArea, area, part.sourcePlane(), visits);
        float fraction = totalLength == 0 ? 0 : distance / totalLength;
        Vec3f normal =
            totalLength == 0 ? Vec3f.ZERO : finite(target.subtract(origin).scale(1 / totalLength));
        return new Result(
            false,
            fraction,
            finite(part.start().multiplyAdd(-.125f, normal)),
            0,
            lastArea,
            area,
            part.enteringPlane(),
            visits);
      }
      var node = map.nodes().get(part.node());
      var plane = map.planes().get(node.plane());
      float from = distance(plane, part.start()), to = distance(plane, part.end());
      if (from >= 0 && to >= 0)
        pending.push(
            new Segment(
                node.front(), part.start(), part.end(), part.enteringPlane(), part.sourcePlane()));
      else if (from < 0 && to < 0)
        pending.push(
            new Segment(
                node.back(), part.start(), part.end(), part.enteringPlane(), part.sourcePlane()));
      else {
        boolean front = from >= 0;
        float split = (from + (front ? -.125f : .125f)) / (from - to);
        if (split < 0) split = .001f;
        else if (split > 1) split = .999f;
        Vec3f middle = finite(part.start().lerp(part.end(), split));
        int near = front ? node.front() : node.back(), far = front ? node.back() : node.front();
        pending.push(
            new Segment(
                far, middle, part.end(), front ? node.plane() : node.plane() ^ 1, node.plane()));
        pending.push(
            new Segment(near, part.start(), middle, part.enteringPlane(), part.sourcePlane()));
      }
    }
    return new Result(false, 1, target, 0, lastArea, 0, 0, visits);
  }

  private static float distance(AasWorld.Plane plane, Vec3f point) {
    Vec3f n = plane.normal();
    float value = n.x() * point.x() + n.y() * point.y() + n.z() * point.z() - plane.distance();
    if (!Float.isFinite(value)) throw new IllegalArgumentException("AAS plane distance overflow");
    return value;
  }

  /** A guest point, rejected beyond the supported coordinate range. */
  private static Vec3f bounded(Vec3f value) {
    Objects.requireNonNull(value);
    if (Math.abs(value.x()) > 1e9 || Math.abs(value.y()) > 1e9 || Math.abs(value.z()) > 1e9)
      throw new IllegalArgumentException("AAS trace coordinates exceed limit");
    return finite(value);
  }

  private static Vec3f finite(Vec3f value) {
    if (!value.isFinite()) throw new IllegalArgumentException("Nonfinite AAS trace arithmetic");
    return value;
  }
}
