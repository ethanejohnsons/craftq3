package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Subtract convex supports from a planar rectangle without sampling or voxelization. */
final class FaceCoverage {
  private static final double EPSILON = 1e-7;
  private static final int MAX_FRAGMENTS = 4096;
  private List<List<Vec3>> gaps;
  private boolean overflow;

  FaceCoverage(Vec3 min, Vec3 max, Vec3 normal) {
    double[] lo = {min.x(), min.y(), min.z()}, hi = {max.x(), max.y(), max.z()};
    double[] n = {normal.x(), normal.y(), normal.z()};
    int axis = -1;
    for (int i = 0; i < 3; i++) {
      if (n[i] != 0) {
        if (Math.abs(n[i]) != 1 || axis >= 0)
          throw new IllegalArgumentException("Expected cardinal face normal");
        axis = i;
      }
      if (lo[i] > hi[i]) throw new IllegalArgumentException("Inverted support rectangle");
    }
    if (axis < 0 || lo[axis] != hi[axis])
      throw new IllegalArgumentException("Expected planar support rectangle");
    int u = (axis + 1) % 3, v = (axis + 2) % 3;
    if (lo[u] == hi[u] || lo[v] == hi[v])
      throw new IllegalArgumentException("Empty support rectangle");
    var polygon = new ArrayList<Vec3>();
    for (int[] corner : new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
      double[] p = lo.clone();
      p[u] = corner[0] == 0 ? lo[u] : hi[u];
      p[v] = corner[1] == 0 ? lo[v] : hi[v];
      polygon.add(new Vec3(p[0], p[1], p[2]));
    }
    gaps = List.of(polygon);
  }

  boolean covered() {
    return !overflow && gaps.isEmpty();
  }

  void subtract(List<TraceResult.Plane> planes) {
    if (overflow || gaps.isEmpty()) return;
    var remaining = new ArrayList<List<Vec3>>();
    for (var gap : gaps) {
      var inside = gap;
      for (var plane : planes) {
        var outside = clip(inside, plane, false);
        if (hasArea(outside)) remaining.add(outside);
        if (remaining.size() > MAX_FRAGMENTS) {
          overflow = true;
          return;
        }
        inside = clip(inside, plane, true);
        if (!hasArea(inside)) break;
      }
      // The portion inside every half-space is supported and is discarded.
    }
    gaps = remaining;
  }

  static List<TraceResult.Plane> boxPlanes(Vec3 min, Vec3 max) {
    double[] lo = {min.x(), min.y(), min.z()}, hi = {max.x(), max.y(), max.z()};
    var planes = new ArrayList<TraceResult.Plane>();
    for (int i = 0; i < 3; i++) {
      planes.add(new TraceResult.Plane(CollisionMath.AXES[i], hi[i]));
      planes.add(new TraceResult.Plane(CollisionMath.AXES[i].scale(-1), -lo[i]));
    }
    return planes;
  }

  private static List<Vec3> clip(List<Vec3> polygon, TraceResult.Plane plane, boolean inside) {
    var result = new ArrayList<Vec3>();
    if (polygon.isEmpty()) return result;
    var previous = polygon.getLast();
    double a = plane.signedDistance(previous) - EPSILON;
    boolean previousKept = inside ? a <= 0 : a >= 0;
    for (var point : polygon) {
      double b = plane.signedDistance(point) - EPSILON;
      boolean kept = inside ? b <= 0 : b >= 0;
      if (kept != previousKept) result.add(CollisionMath.lerp(previous, point, a / (a - b)));
      if (kept) result.add(point);
      previous = point;
      a = b;
      previousKept = kept;
    }
    return result;
  }

  private static boolean hasArea(List<Vec3> polygon) {
    if (polygon.size() < 3) return false;
    var origin = polygon.getFirst();
    var area = CollisionMath.ZERO;
    for (int i = 1; i + 1 < polygon.size(); i++)
      area =
          area.add(
              CollisionMath.cross(
                  CollisionMath.subtract(polygon.get(i), origin),
                  CollisionMath.subtract(polygon.get(i + 1), origin)));
    return CollisionMath.length(area) > 1e-10;
  }
}
