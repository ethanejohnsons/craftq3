package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Additional SAT support axes needed when a convex shape rotates against a world AABB. */
final class GeometrySupport {
  private GeometrySupport() {}

  static void support(
      List<ConvexTrace.Side> sides,
      Vec3 direction,
      List<Vec3> points,
      int flags,
      int id,
      String shader) {
    Vec3 normal = CollisionMath.unit(direction);
    if (CollisionMath.dot(normal, normal) < 0.5) return;
    double distance = Double.NEGATIVE_INFINITY;
    for (Vec3 point : points) distance = Math.max(distance, CollisionMath.dot(normal, point));
    for (var side : sides)
      if (CollisionMath.dot(side.plane().normal(), normal) > 1 - 1e-9
          && Math.abs(side.plane().distance() - distance) < 1e-5) return;
    sides.add(new ConvexTrace.Side(new TraceResult.Plane(normal, distance), flags, id, shader));
  }

  static ConvexTrace.Shape transform(
      ConvexTrace.Shape shape, RigidTransform transform, boolean rotated, int entity, int model) {
    var sides = new ArrayList<ConvexTrace.Side>();
    for (var side : shape.sides())
      sides.add(
          new ConvexTrace.Side(
              transform.plane(side.plane()), side.flags(), side.side(), side.shader()));
    if (rotated && shape.sides().size() >= 4) {
      List<List<Vec3>> faces = hull(shape);
      List<Vec3> points = new ArrayList<>();
      for (List<Vec3> face : faces) for (Vec3 point : face) points.add(transform.point(point));
      if (points.isEmpty())
        throw new IllegalArgumentException("Rotated brush has no finite convex hull");
      var reference = shape.sides().getFirst();
      for (Vec3 axis : CollisionMath.AXES) {
        support(sides, axis, points, reference.flags(), -1, reference.shader());
        support(sides, axis.scale(-1), points, reference.flags(), -1, reference.shader());
      }
      for (List<Vec3> face : faces)
        for (int i = 0; i < face.size(); i++) {
          Vec3 edge =
              transform.vector(
                  CollisionMath.subtract(face.get((i + 1) % face.size()), face.get(i)));
          for (Vec3 axis : CollisionMath.AXES) {
            Vec3 direction = CollisionMath.cross(edge, axis);
            support(sides, direction, points, reference.flags(), -1, reference.shader());
            support(sides, direction.scale(-1), points, reference.flags(), -1, reference.shader());
          }
        }
    }
    var old = shape.metadata();
    return new ConvexTrace.Shape(
        sides, new ConvexTrace.Metadata(old.contents(), entity, model, old.brush(), old.face()));
  }

  private static List<List<Vec3>> hull(ConvexTrace.Shape shape) {
    if (shape.sides().size() > 256)
      throw new IllegalArgumentException("Rotated brush exceeds 256 planes");
    double extent = 131072;
    for (var side : shape.sides())
      extent = Math.max(extent, Math.abs(side.plane().distance()) * 4 + 64);
    int work = 0;
    var faces = new ArrayList<List<Vec3>>();
    for (int i = 0; i < shape.sides().size(); i++) {
      var plane = shape.sides().get(i).plane();
      Vec3 n = plane.normal(), center = n.scale(plane.distance());
      Vec3 axis = Math.abs(n.z()) > .9 ? CollisionMath.AXES[1] : CollisionMath.AXES[2];
      Vec3 u = CollisionMath.unit(CollisionMath.cross(axis, n)).scale(extent);
      Vec3 v = CollisionMath.cross(n, CollisionMath.unit(u)).scale(extent);
      List<Vec3> polygon =
          List.of(
              center.add(u).add(v),
              center.add(u.scale(-1)).add(v),
              center.add(u.scale(-1)).add(v.scale(-1)),
              center.add(u).add(v.scale(-1)));
      for (int j = 0; j < shape.sides().size() && !polygon.isEmpty(); j++) {
        if (i == j) continue;
        work += polygon.size();
        if (work > 2_000_000)
          throw new IllegalArgumentException("Rotated brush hull work budget exceeded");
        polygon = clip(polygon, shape.sides().get(j).plane());
      }
      if (polygon.size() >= 3) faces.add(polygon);
    }
    return faces;
  }

  private static List<Vec3> clip(List<Vec3> input, TraceResult.Plane plane) {
    var output = new ArrayList<Vec3>();
    for (int i = 0; i < input.size(); i++) {
      Vec3 a = input.get(i), b = input.get((i + 1) % input.size());
      double da = plane.signedDistance(a), db = plane.signedDistance(b);
      boolean insideA = da <= 1e-6, insideB = db <= 1e-6;
      if (insideA) output.add(a);
      if (insideA != insideB) output.add(CollisionMath.lerp(a, b, da / (da - db)));
    }
    return output;
  }
}
