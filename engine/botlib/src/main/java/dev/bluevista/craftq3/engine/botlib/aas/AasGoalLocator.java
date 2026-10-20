package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Native-observed item goal localization over immutable AAS geometry; no route-distance heuristic.
 */
public final class AasGoalLocator {
  public static final int CROUCH = 4;

  public record GoalArea(int area, Vec3f origin) {
    public GoalArea {
      if (area < 0) throw new IllegalArgumentException("Invalid goal area");
      Objects.requireNonNull(origin);
    }
  }

  private final AasNavigation navigation;
  private final AasWorld map;
  private final AasPresenceTrace presenceTrace;
  private final int maxNodeVisits;

  public AasGoalLocator(AasNavigation navigation) {
    this(navigation, 1_000_000);
  }

  public AasGoalLocator(AasNavigation navigation, int maxNodeVisits) {
    this.navigation = Objects.requireNonNull(navigation);
    this.map = navigation.map();
    if (maxNodeVisits < 1 || maxNodeVisits > 2_000_000)
      throw new IllegalArgumentException("AAS goal work limit out of range");
    this.maxNodeVisits = maxNodeVisits;
    presenceTrace = new AasPresenceTrace(map, maxNodeVisits);
  }

  public GoalArea bestReachableArea(Vec3f origin, Vec3f mins, Vec3f maxs) {
    origin = point(origin);
    mins = point(mins);
    maxs = point(maxs);
    bounds(mins, maxs);
    var budget = new Budget();
    Vec3f candidate = origin;
    int area = navigation.pointArea(candidate);
    if (area == 0) {
      search:
      for (int z = 0; z <= 16; z += 4) {
        for (int radius = 0; radius <= 16; radius += 4) {
          for (int x : new int[] {-radius, 0, radius})
            for (int y : new int[] {-radius, 0, radius}) {
              candidate = offset(origin, x, y, z);
              area = navigation.pointArea(candidate);
              if (area != 0) break search;
            }
        }
      }
    }
    if (area != 0) {
      Vec3f raised = offset(candidate, 0, 0, .25f);
      var trace = presenceTrace.trace(raised, offset(candidate, 0, 0, -50), CROUCH);
      if (trace.startSolid()) return new GoalArea(area, raised);
      int destination = navigation.pointArea(trace.endPosition());
      if (destination != 0) return new GoalArea(destination, trace.endPosition());
    }
    var linked = linkedAreas(offset(origin, mins), offset(origin, maxs), CROUCH, budget);
    int best = linked.isEmpty() ? 0 : linked.getFirst();
    for (int linkedArea : linked) {
      if ((map.areaSettings().get(linkedArea).flags() & 5) != 0) {
        best = linkedArea;
        break;
      }
    }
    return new GoalArea(best, origin);
  }

  /** Ordered presence-expanded area links used by native item fallback; not sorted boxAreas. */
  public List<Integer> linkedAreas(Vec3f absMin, Vec3f absMax, int presence) {
    return linkedAreas(point(absMin), point(absMax), presence, new Budget());
  }

  private List<Integer> linkedAreas(Vec3f absMin, Vec3f absMax, int presence, Budget budget) {
    bounds(absMin, absMax);
    if (presence != 2 && presence != CROUCH)
      throw new IllegalArgumentException("Unsupported AAS presence bounds " + presence);
    // Runtime botlib defaults differ from the compile-time bounding boxes stored in some AAS files.
    Vec3f min = subtract(absMin, new Vec3f(15, 15, presence == CROUCH ? 8 : 32));
    Vec3f max = subtract(absMax, new Vec3f(-15, -15, -24));
    var pending = new ArrayDeque<Integer>();
    pending.push(root());
    var areas = new BitSet(map.areas().size());
    var result = new ArrayList<Integer>();
    while (!pending.isEmpty()) {
      budget.visit();
      int node = pending.pop();
      if (node == 0) continue;
      if (node < 0) {
        int area = -node;
        if (!areas.get(area)) {
          areas.set(area);
          result.add(area);
        }
        continue;
      }
      var partition = map.nodes().get(node);
      var plane = map.planes().get(partition.plane());
      Vec3f n = plane.normal();
      Vec3f low =
          new Vec3f(
              n.x() >= 0 ? min.x() : max.x(),
              n.y() >= 0 ? min.y() : max.y(),
              n.z() >= 0 ? min.z() : max.z());
      Vec3f high =
          new Vec3f(
              n.x() >= 0 ? max.x() : min.x(),
              n.y() >= 0 ? max.y() : min.y(),
              n.z() >= 0 ? max.z() : min.z());
      if (distance(plane, high) >= 0) pending.push(partition.front());
      if (distance(plane, low) < 0) pending.push(partition.back());
    }
    Collections.reverse(result);
    return List.copyOf(result);
  }

  private int root() {
    return map.nodes().size() > 1 ? 1 : 0;
  }

  private static float distance(AasWorld.Plane plane, Vec3f point) {
    Vec3f n = plane.normal();
    return n.x() * point.x() + n.y() * point.y() + n.z() * point.z() - plane.distance();
  }

  private static Vec3f point(Vec3f value) {
    Objects.requireNonNull(value);
    if (Math.abs(value.x()) > 1e9 || Math.abs(value.y()) > 1e9 || Math.abs(value.z()) > 1e9)
      throw new IllegalArgumentException("AAS goal coordinates exceed limit");
    return new Vec3f(value.x(), value.y(), value.z());
  }

  private static Vec3f offset(Vec3f a, Vec3f b) {
    return offset(a, b.x(), b.y(), b.z());
  }

  private static Vec3f subtract(Vec3f a, Vec3f b) {
    return offset(a, -b.x(), -b.y(), -b.z());
  }

  private static Vec3f offset(Vec3f a, float x, float y, float z) {
    return point(new Vec3f(a.x() + x, a.y() + y, a.z() + z));
  }

  private static void bounds(Vec3f min, Vec3f max) {
    if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
      throw new IllegalArgumentException("Inverted AAS goal bounds");
  }

  private final class Budget {
    private int visits;

    void visit() {
      if (++visits > maxNodeVisits)
        throw new IllegalStateException("AAS goal work budget exceeded");
    }
  }
}
