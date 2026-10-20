package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Static presence-hull collision through the AAS's already expanded BSP partition. */
public final class AasPresenceTrace {
  public record Result(
      boolean startSolid,
      float fraction,
      Vec3 endPosition,
      int entity,
      int lastArea,
      int area,
      int planeNumber,
      int nodeVisits) {}

  /** Dynamic collision is evaluated independently within each traversed, permitted leaf. */
  @FunctionalInterface
  public interface AreaCollision {
    Optional<EntityHit> trace(int area, Vec3 start, Vec3 end, int presenceMask);
  }

  public record EntityHit(boolean startSolid, float fraction, Vec3 endPosition, int entity) {
    public EntityHit {
      Objects.requireNonNull(endPosition);
      if (!Float.isFinite(fraction) || fraction < 0 || fraction > 1 || entity < 0 || entity > 1023)
        throw new IllegalArgumentException("Invalid AAS dynamic collision");
    }
  }

  private record Point(float x, float y, float z) {
    Point {
      if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
        throw new IllegalArgumentException("Nonfinite AAS trace arithmetic");
    }

    static Point from(Vec3 value) {
      Objects.requireNonNull(value);
      if (Math.abs(value.x()) > 1e9 || Math.abs(value.y()) > 1e9 || Math.abs(value.z()) > 1e9)
        throw new IllegalArgumentException("AAS trace coordinates exceed limit");
      return new Point((float) value.x(), (float) value.y(), (float) value.z());
    }

    Point subtract(Point other) {
      return new Point(x - other.x, y - other.y, z - other.z);
    }

    Point along(Point other, float fraction) {
      return new Point(
          x + fraction * (other.x - x), y + fraction * (other.y - y), z + fraction * (other.z - z));
    }

    Point back(Point direction) {
      return new Point(x - .125f * direction.x, y - .125f * direction.y, z - .125f * direction.z);
    }

    float length() {
      return (float) Math.sqrt(x * x + y * y + z * z);
    }

    Point scale(float amount) {
      return new Point(x * amount, y * amount, z * amount);
    }

    Vec3 vector() {
      return new Vec3(x, y, z);
    }
  }

  private record Segment(int node, Point start, Point end, int enteringPlane, int sourcePlane) {}

  private final AasMap map;
  private final int maxNodeVisits;

  public AasPresenceTrace(AasMap map) {
    this(map, 1_000_000);
  }

  public AasPresenceTrace(AasMap map, int maxNodeVisits) {
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
  public Result trace(Vec3 start, Vec3 end, int presenceMask) {
    return trace(start, end, presenceMask, null);
  }

  public Result trace(Vec3 start, Vec3 end, int presenceMask, AreaCollision collision) {
    Point origin = Point.from(start), target = Point.from(end);
    float totalLength = target.subtract(origin).length();
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
                    collision.trace(
                        area, part.start().vector(), part.end().vector(), presenceMask));
            if (hit.isPresent()) {
              var dynamic = hit.orElseThrow();
              if (dynamic.startSolid() || dynamic.fraction() < 1) {
                Point endpoint = Point.from(dynamic.endPosition());
                float fraction =
                    dynamic.startSolid() || totalLength == 0
                        ? 0
                        : endpoint.subtract(origin).length() / totalLength;
                return new Result(
                    dynamic.startSolid(),
                    fraction,
                    endpoint.vector(),
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
        if (lastArea == 0) return new Result(true, 0, origin.vector(), 0, 0, area, 0, visits);
        Point delta = part.start().subtract(origin);
        float distance = delta.length();
        // An exact margin split can visit a permitted leaf without advancing the ray.
        // Native start-solid results retain that leaf and the raw partition plane.
        if (distance == 0)
          return new Result(
              true, 0, origin.vector(), 0, lastArea, area, part.sourcePlane(), visits);
        float fraction = totalLength == 0 ? 0 : distance / totalLength;
        Point normal =
            totalLength == 0 ? new Point(0, 0, 0) : target.subtract(origin).scale(1 / totalLength);
        return new Result(
            false,
            fraction,
            part.start().back(normal).vector(),
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
        Point middle = part.start().along(part.end(), split);
        int near = front ? node.front() : node.back(), far = front ? node.back() : node.front();
        pending.push(
            new Segment(
                far, middle, part.end(), front ? node.plane() : node.plane() ^ 1, node.plane()));
        pending.push(
            new Segment(near, part.start(), middle, part.enteringPlane(), part.sourcePlane()));
      }
    }
    return new Result(false, 1, target.vector(), 0, lastArea, 0, 0, visits);
  }

  private static float distance(AasMap.Plane plane, Point point) {
    Vec3 n = plane.normal();
    float value =
        (float) n.x() * point.x()
            + (float) n.y() * point.y()
            + (float) n.z() * point.z()
            - plane.distance();
    if (!Float.isFinite(value)) throw new IllegalArgumentException("AAS plane distance overflow");
    return value;
  }
}
