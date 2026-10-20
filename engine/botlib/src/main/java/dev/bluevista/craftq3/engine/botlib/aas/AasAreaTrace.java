package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered raw AAS area entries; preserves native boundary visits and repeated areas. */
public final class AasAreaTrace {
  public record Entry(int area, Vec3f point) {
    public Entry {
      if (area < 1) throw new IllegalArgumentException("Invalid raw AAS area");
      Objects.requireNonNull(point);
    }
  }

  private record Part(int node, Vec3f start, Vec3f end) {}

  private AasAreaTrace() {}

  /** Stops normally at maxAreas; node-work exhaustion is an explicit exception. */
  public static List<Entry> trace(
      AasWorld map, Vec3f start, Vec3f end, int maxAreas, int maxNodeVisits) {
    Objects.requireNonNull(map);
    if (maxAreas < 1 || maxAreas > 65536 || maxNodeVisits < 1 || maxNodeVisits > 100_000_000)
      throw new IllegalArgumentException("Invalid raw AAS trace limits");
    var pending = new ArrayDeque<Part>();
    pending.push(new Part(map.nodes().size() > 1 ? 1 : 0, finite(start), finite(end)));
    var entries = new ArrayList<Entry>();
    int visits = 0;
    while (!pending.isEmpty() && entries.size() < maxAreas) {
      if (visits++ == maxNodeVisits)
        throw new IllegalStateException("Raw AAS trace node budget exceeded");
      var part = pending.pop();
      if (part.node() == 0) continue;
      if (part.node() < 0) {
        entries.add(new Entry(-part.node(), part.start()));
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
        Vec3f middle = finite(part.start().lerp(part.end(), fraction));
        // Native crossing-side choice differs from its all-nonpositive branch at zero.
        int near = d0 < 0 ? node.back() : node.front(), far = d0 < 0 ? node.front() : node.back();
        pending.push(new Part(far, middle, part.end()));
        pending.push(new Part(near, part.start(), middle));
      }
    }
    return List.copyOf(entries);
  }

  private static float distance(AasWorld.Plane plane, Vec3f point) {
    float result = plane.normal().dot(point) - plane.distance();
    if (!Float.isFinite(result))
      throw new IllegalArgumentException("Raw AAS distance exceeds finite float range");
    return result;
  }

  private static Vec3f finite(Vec3f point) {
    if (!Objects.requireNonNull(point).isFinite())
      throw new IllegalArgumentException("Raw AAS point exceeds finite float range");
    return point;
  }
}
