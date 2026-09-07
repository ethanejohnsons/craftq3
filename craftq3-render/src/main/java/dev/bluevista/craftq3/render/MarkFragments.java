package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Clips world triangles to a finite convex decal projection; cgame owns textures and lifetimes. */
public final class MarkFragments {
  private static final double EPSILON = .05;
  private final List<Surface> surfaces;

  private record Surface(BspMap.Bounds bounds, List<Triangle> triangles) {}

  private record Triangle(Vec3 a, Vec3 b, Vec3 c, Vec3 normal) {}

  private record Plane(Vec3 normal, double distance) {}

  public MarkFragments(BspMap map) {
    this(BspSceneBuilder.build("marks", map, 8));
  }

  public MarkFragments(RenderScene scene) {
    List<Surface> result = new ArrayList<>();
    for (var surface : scene.surfaces()) {
      if (scene.bsp() != null
          && (scene.bsp().textures().get(surface.texture()).flags() & (4 | 16 | 32)) != 0) continue;
      List<Triangle> triangles = new ArrayList<>();
      for (int i = surface.firstVertex();
          i < surface.firstVertex() + surface.vertexCount();
          i += 3) {
        var a = scene.vertices().get(i);
        var b = scene.vertices().get(i + 1);
        var c = scene.vertices().get(i + 2);
        Vec3 normal =
            cross(subtract(b.position(), a.position()), subtract(c.position(), a.position()));
        double length = length(normal);
        if (length < 1e-10) continue;
        normal = normal.scale(1 / length);
        if (dot(normal, a.normal().add(b.normal()).add(c.normal())) < 0) normal = normal.scale(-1);
        triangles.add(new Triangle(a.position(), b.position(), c.position(), normal));
      }
      if (!triangles.isEmpty()) result.add(new Surface(surface.bounds(), List.copyOf(triangles)));
    }
    surfaces = List.copyOf(result);
  }

  /**
   * Output polygons retain triangle winding. Limits are caller capacities, never allocation sizes.
   */
  public List<List<Vec3>> project(
      List<Vec3> polygon, Vec3 projection, int maxPoints, int maxFragments) {
    if (polygon.size() < 3
        || polygon.size() > 64
        || maxPoints < 0
        || maxPoints > 65536
        || maxFragments < 0
        || maxFragments > 8192)
      throw new IllegalArgumentException("Invalid mark fragment capacity");
    double length = length(projection);
    if (length < 1e-8 || maxPoints < 3 || maxFragments == 0) return List.of();
    if (length > 131072) throw new IllegalArgumentException("Mark projection too long");
    Vec3 direction = projection.scale(1 / length), center = new Vec3(0, 0, 0);
    for (Vec3 point : polygon) center = center.add(point.scale(1.0 / polygon.size()));
    List<Plane> planes = new ArrayList<>();
    double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
    List<Vec3> swept = new ArrayList<>();
    for (int i = 0; i < polygon.size(); i++) {
      Vec3 point = polygon.get(i), next = polygon.get((i + 1) % polygon.size());
      Vec3 normal = cross(subtract(next, point), direction);
      double normalLength = length(normal);
      if (normalLength < 1e-8) return List.of();
      normal = normal.scale(1 / normalLength);
      double distance = dot(normal, point);
      if (dot(normal, center) > distance) {
        normal = normal.scale(-1);
        distance = -distance;
      }
      planes.add(new Plane(normal, distance));
      low = Math.min(low, dot(direction, point));
      high = Math.max(high, dot(direction, point) + length);
      swept.add(point);
      swept.add(point.add(projection));
    }
    // Contact traces deliberately stop slightly in front of the surface; retain a narrow cap
    // margin.
    planes.add(new Plane(direction.scale(-1), -low + .5));
    planes.add(new Plane(direction, high + .5));
    BspMap.Bounds bounds = bounds(swept);
    List<List<Vec3>> result = new ArrayList<>();
    int points = 0, visited = 0;
    for (Surface surface : surfaces) {
      if (!intersects(bounds, surface.bounds())) continue;
      for (Triangle triangle : surface.triangles()) {
        if (++visited > 1_000_000)
          throw new IllegalStateException("Mark clipping work budget exceeded");
        if (dot(direction, triangle.normal()) >= -.01) continue;
        List<Vec3> clipped = List.of(triangle.a(), triangle.b(), triangle.c());
        for (Plane plane : planes) {
          clipped = clip(clipped, plane);
          if (clipped.size() < 3) break;
        }
        if (clipped.size() < 3 || area(clipped) < 1e-8) continue;
        if (points + clipped.size() > maxPoints || result.size() >= maxFragments)
          return List.copyOf(result);
        result.add(List.copyOf(clipped));
        points += clipped.size();
      }
    }
    return List.copyOf(result);
  }

  private static List<Vec3> clip(List<Vec3> points, Plane plane) {
    if (points.isEmpty()) return points;
    List<Vec3> result = new ArrayList<>();
    Vec3 previous = points.getLast();
    double before = dot(plane.normal(), previous) - plane.distance();
    for (Vec3 point : points) {
      double after = dot(plane.normal(), point) - plane.distance();
      boolean inBefore = before <= EPSILON, inAfter = after <= EPSILON;
      if (inBefore != inAfter) {
        double fraction = Math.clamp(before / (before - after), 0, 1);
        result.add(previous.add(subtract(point, previous).scale(fraction)));
      }
      if (inAfter) result.add(point);
      previous = point;
      before = after;
    }
    return result;
  }

  private static double area(List<Vec3> points) {
    Vec3 sum = new Vec3(0, 0, 0), origin = points.getFirst();
    for (int i = 1; i + 1 < points.size(); i++)
      sum = sum.add(cross(subtract(points.get(i), origin), subtract(points.get(i + 1), origin)));
    return length(sum) * .5;
  }

  private static BspMap.Bounds bounds(List<Vec3> points) {
    double x = Double.POSITIVE_INFINITY, y = x, z = x, xx = -x, yy = -x, zz = -x;
    for (Vec3 p : points) {
      x = Math.min(x, p.x());
      y = Math.min(y, p.y());
      z = Math.min(z, p.z());
      xx = Math.max(xx, p.x());
      yy = Math.max(yy, p.y());
      zz = Math.max(zz, p.z());
    }
    return new BspMap.Bounds(
        new Vec3(x - .55, y - .55, z - .55), new Vec3(xx + .55, yy + .55, zz + .55));
  }

  private static boolean intersects(BspMap.Bounds a, BspMap.Bounds b) {
    return a.min().x() <= b.max().x()
        && a.max().x() >= b.min().x()
        && a.min().y() <= b.max().y()
        && a.max().y() >= b.min().y()
        && a.min().z() <= b.max().z()
        && a.max().z() >= b.min().z();
  }

  private static Vec3 subtract(Vec3 a, Vec3 b) {
    return a.add(b.scale(-1));
  }

  private static double length(Vec3 value) {
    return Math.sqrt(dot(value, value));
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }
}
