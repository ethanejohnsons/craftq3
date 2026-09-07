package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Biquadratic Q3 patch tessellation into one-sided facets with AABB/edge bevel support planes. */
final class PatchCollision {
  private PatchCollision() {}

  record Sample(Vec3 point, Vec3 normal) {}

  record Facet(
      Vec3 a, Vec3 b, Vec3 c, TraceResult.Plane front, Bounds bounds, ConvexTrace.Shape shape) {
    TraceResult trace(TraceRequest request) {
      if (!bounds.intersects(request)) return TraceResult.clear(request);
      if (!request.isPoint()) {
        TraceResult result = ConvexTrace.trace(shape, request);
        if (result.hit().isPresent() && result.hit().orElseThrow().side() == 1)
          return TraceResult.clear(request);
        return result;
      }
      if ((shape.metadata().contents() & request.contentsMask()) == 0
          || shape.metadata().entity() == request.ignoreEntity()) return TraceResult.clear(request);
      Vec3 start = request.start().add(request.mins()), end = request.end().add(request.mins());
      double distance = front.signedDistance(start), delta = front.signedDistance(end) - distance;
      if (distance < -1e-7 || delta >= -1e-12) return TraceResult.clear(request);
      double exact = -distance / delta;
      if (exact < 0 || exact > 1) return TraceResult.clear(request);
      Vec3 point = CollisionMath.lerp(start, end, exact);
      double e0 =
          CollisionMath.dot(
              CollisionMath.cross(CollisionMath.subtract(b, a), CollisionMath.subtract(point, a)),
              front.normal());
      double e1 =
          CollisionMath.dot(
              CollisionMath.cross(CollisionMath.subtract(c, b), CollisionMath.subtract(point, b)),
              front.normal());
      double e2 =
          CollisionMath.dot(
              CollisionMath.cross(CollisionMath.subtract(a, c), CollisionMath.subtract(point, c)),
              front.normal());
      if (!((e0 >= -1e-6 && e1 >= -1e-6 && e2 >= -1e-6)
          || (e0 <= 1e-6 && e1 <= 1e-6 && e2 <= 1e-6))) return TraceResult.clear(request);
      double fraction = Math.max(0, (distance - CollisionMath.CONTACT_EPSILON) / -delta);
      var metadata = shape.metadata();
      var side = shape.sides().getFirst();
      return new TraceResult(
          fraction,
          CollisionMath.lerp(request.start(), request.end(), fraction),
          false,
          false,
          Optional.of(
              new TraceResult.Hit(
                  front,
                  metadata.contents(),
                  side.flags(),
                  metadata.entity(),
                  metadata.model(),
                  -1,
                  0,
                  metadata.face(),
                  side.shader())));
    }
  }

  static List<Facet> build(
      BspMap map,
      int faceIndex,
      int subdivisions,
      int entity,
      int model,
      RigidTransform transform) {
    BspMap.Face face = map.faces().get(faceIndex);
    BspMap.Texture texture = map.textures().get(face.texture());
    if (texture.contents() == 0 || (texture.flags() & 0x4000) != 0) return List.of();
    var facets = new ArrayList<Facet>();
    for (int y = 0; y < face.patchHeight() - 2; y += 2)
      for (int x = 0; x < face.patchWidth() - 2; x += 2) {
        Sample[][] grid = new Sample[subdivisions + 1][subdivisions + 1];
        for (int row = 0; row <= subdivisions; row++)
          for (int column = 0; column <= subdivisions; column++)
            grid[row][column] =
                sample(
                    map,
                    face,
                    x,
                    y,
                    column / (double) subdivisions,
                    row / (double) subdivisions,
                    transform);
        for (int row = 0; row < subdivisions; row++)
          for (int column = 0; column < subdivisions; column++) {
            facet(
                facets,
                grid[row][column],
                grid[row][column + 1],
                grid[row + 1][column],
                texture,
                entity,
                model,
                faceIndex);
            facet(
                facets,
                grid[row][column + 1],
                grid[row + 1][column + 1],
                grid[row + 1][column],
                texture,
                entity,
                model,
                faceIndex);
          }
      }
    return List.copyOf(facets);
  }

  private static void facet(
      List<Facet> output,
      Sample a,
      Sample b,
      Sample c,
      BspMap.Texture texture,
      int entity,
      int model,
      int face) {
    Vec3 n =
        CollisionMath.unit(
            CollisionMath.cross(
                CollisionMath.subtract(b.point(), a.point()),
                CollisionMath.subtract(c.point(), a.point())));
    if (CollisionMath.dot(n, n) < .5) return;
    if (CollisionMath.dot(n, a.normal().add(b.normal()).add(c.normal())) < 0) n = n.scale(-1);
    double distance = CollisionMath.dot(n, a.point());
    var front = new TraceResult.Plane(n, distance);
    var sides = new ArrayList<ConvexTrace.Side>();
    sides.add(new ConvexTrace.Side(front, texture.flags(), 0, texture.name()));
    sides.add(
        new ConvexTrace.Side(
            new TraceResult.Plane(n.scale(-1), -distance), texture.flags(), 1, texture.name()));
    var points = List.of(a.point(), b.point(), c.point());
    for (Vec3 axis : CollisionMath.AXES) {
      GeometrySupport.support(sides, axis, points, texture.flags(), -1, texture.name());
      GeometrySupport.support(sides, axis.scale(-1), points, texture.flags(), -1, texture.name());
    }
    for (int edge = 0; edge < 3; edge++) {
      Vec3 direction = CollisionMath.subtract(points.get((edge + 1) % 3), points.get(edge));
      for (Vec3 axis : CollisionMath.AXES) {
        Vec3 normal = CollisionMath.cross(direction, axis);
        GeometrySupport.support(sides, normal, points, texture.flags(), -1, texture.name());
        GeometrySupport.support(
            sides, normal.scale(-1), points, texture.flags(), -1, texture.name());
      }
    }
    output.add(
        new Facet(
            a.point(),
            b.point(),
            c.point(),
            front,
            Bounds.of(a.point(), b.point(), c.point()),
            new ConvexTrace.Shape(
                sides, new ConvexTrace.Metadata(texture.contents(), entity, model, -1, face))));
  }

  private static Sample sample(
      BspMap map, BspMap.Face face, int x, int y, double u, double v, RigidTransform transform) {
    double[] us = {(1 - u) * (1 - u), 2 * u * (1 - u), u * u},
        vs = {(1 - v) * (1 - v), 2 * v * (1 - v), v * v};
    Vec3 point = CollisionMath.ZERO, normal = CollisionMath.ZERO;
    for (int j = 0; j < 3; j++)
      for (int i = 0; i < 3; i++) {
        var vertex = map.vertices().get(face.firstVertex() + (y + j) * face.patchWidth() + x + i);
        double weight = us[i] * vs[j];
        point = point.add(vertex.position().scale(weight));
        normal = normal.add(vertex.normal().scale(weight));
      }
    return new Sample(transform.point(point), transform.vector(normal));
  }
}
