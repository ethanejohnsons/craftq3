package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered raw AAS area entries; preserves native boundary visits and repeated areas. */
public final class AasAreaTrace {
  public record Entry(int area, Vec3 point) {
    public Entry {
      if (area < 1) throw new IllegalArgumentException("Invalid raw AAS area");
      Objects.requireNonNull(point);
    }
  }

  private record Point(float x, float y, float z) {
    Point {
      if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
        throw new IllegalArgumentException("Raw AAS point exceeds finite float range");
    }

    static Point of(Vec3 p) {
      Objects.requireNonNull(p);
      float x = (float) p.x(), y = (float) p.y(), z = (float) p.z();
      if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
        throw new IllegalArgumentException("Raw AAS point exceeds float range");
      return new Point(x, y, z);
    }

    Vec3 vector() {
      return new Vec3(x, y, z);
    }
  }

  private record Part(int node, Point start, Point end) {}

  private AasAreaTrace() {}

  /** Stops normally at maxAreas; node-work exhaustion is an explicit exception. */
  public static List<Entry> trace(
      AasMap map, Vec3 start, Vec3 end, int maxAreas, int maxNodeVisits) {
    Objects.requireNonNull(map);
    if (maxAreas < 1 || maxAreas > 65536 || maxNodeVisits < 1 || maxNodeVisits > 100_000_000)
      throw new IllegalArgumentException("Invalid raw AAS trace limits");
    var pending = new ArrayDeque<Part>();
    pending.push(new Part(map.nodes().size() > 1 ? 1 : 0, Point.of(start), Point.of(end)));
    var entries = new ArrayList<Entry>();
    int visits = 0;
    while (!pending.isEmpty() && entries.size() < maxAreas) {
      if (visits++ == maxNodeVisits)
        throw new IllegalStateException("Raw AAS trace node budget exceeded");
      var part = pending.pop();
      if (part.node() == 0) continue;
      if (part.node() < 0) {
        entries.add(new Entry(-part.node(), part.start().vector()));
        continue;
      }
      var node = map.nodes().get(part.node());
      var plane = map.planes().get(node.plane());
      float d0 = distance(plane, part.start()), d1 = distance(plane, part.end());
      if (d0 > 0 && d1 > 0) pending.push(new Part(node.front(), part.start(), part.end()));
      else if (d0 <= 0 && d1 <= 0) pending.push(new Part(node.back(), part.start(), part.end()));
      else {
        float fraction = d0 / (d0 - d1);
        if (!Float.isFinite(fraction))
          throw new IllegalArgumentException("Raw AAS split exceeds finite float range");
        Point a = part.start(), b = part.end();
        var middle =
            new Point(
                a.x() + fraction * (b.x() - a.x()),
                a.y() + fraction * (b.y() - a.y()),
                a.z() + fraction * (b.z() - a.z()));
        // Native crossing-side choice differs from its all-nonpositive branch at zero.
        int near = d0 < 0 ? node.back() : node.front(), far = d0 < 0 ? node.front() : node.back();
        pending.push(new Part(far, middle, part.end()));
        pending.push(new Part(near, part.start(), middle));
      }
    }
    return List.copyOf(entries);
  }

  private static float distance(AasMap.Plane plane, Point point) {
    var n = plane.normal();
    float result =
        (float) n.x() * point.x()
            + (float) n.y() * point.y()
            + (float) n.z() * point.z()
            - plane.distance();
    if (!Float.isFinite(result))
      throw new IllegalArgumentException("Raw AAS distance exceeds finite float range");
    return result;
  }
}
