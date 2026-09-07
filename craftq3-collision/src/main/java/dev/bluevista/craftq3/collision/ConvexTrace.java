package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Optional;

/** Parametric clipping against support planes of a convex collision volume. */
final class ConvexTrace {
  private ConvexTrace() {}

  record Side(TraceResult.Plane plane, int flags, int side, String shader) {}

  record Metadata(int contents, int entity, int model, int brush, int face) {}

  record Shape(List<Side> sides, Metadata metadata) {
    Shape {
      sides = List.copyOf(sides);
    }
  }

  static TraceResult trace(Shape shape, TraceRequest request) {
    if ((shape.metadata().contents() & request.contentsMask()) == 0
        || shape.metadata().entity() == request.ignoreEntity()
        || shape.sides().isEmpty()) return TraceResult.clear(request);
    double enter = -1, leave = 1;
    boolean outsideStart = false, outsideEnd = false;
    Side impact = null;
    for (Side side : shape.sides()) {
      TraceResult.Plane plane = side.plane();
      double expandedDistance =
          plane.distance()
              - CollisionMath.minSupport(plane.normal(), request.mins(), request.maxs());
      double start = CollisionMath.dot(plane.normal(), request.start()) - expandedDistance;
      double end = CollisionMath.dot(plane.normal(), request.end()) - expandedDistance;
      outsideStart |= start > 0;
      outsideEnd |= end > 0;
      if (start > 0 && (end >= CollisionMath.CONTACT_EPSILON || end >= start))
        return TraceResult.clear(request);
      if (start <= 0 && end <= 0) continue;
      double delta = start - end;
      if (start > end) {
        double crossing = Math.max(0, (start - CollisionMath.CONTACT_EPSILON) / delta);
        if (crossing > enter) {
          enter = crossing;
          impact = side;
        }
      } else {
        leave = Math.min(leave, Math.min(1, (start + CollisionMath.CONTACT_EPSILON) / delta));
      }
    }
    if (!outsideStart) {
      return new TraceResult(
          outsideEnd ? 1 : 0,
          outsideEnd ? request.end() : request.start(),
          true,
          !outsideEnd,
          outsideEnd ? Optional.empty() : Optional.of(hit(shape, null)));
    }
    if (impact != null && enter < leave && enter < 1) {
      return new TraceResult(
          enter,
          CollisionMath.lerp(request.start(), request.end(), enter),
          false,
          false,
          Optional.of(hit(shape, impact)));
    }
    return TraceResult.clear(request);
  }

  static int contents(Shape shape, Vec3 point, int mask, int ignore) {
    if ((shape.metadata().contents() & mask) == 0
        || shape.metadata().entity() == ignore
        || shape.sides().isEmpty()) return 0;
    for (Side side : shape.sides()) if (side.plane().signedDistance(point) > 1e-7) return 0;
    return shape.metadata().contents();
  }

  private static TraceResult.Hit hit(Shape shape, Side side) {
    var metadata = shape.metadata();
    return new TraceResult.Hit(
        side == null ? TraceResult.Plane.NONE : side.plane(),
        metadata.contents(),
        side == null ? 0 : side.flags(),
        metadata.entity(),
        metadata.model(),
        metadata.brush(),
        side == null ? -1 : side.side(),
        metadata.face(),
        side == null ? "" : side.shader());
  }
}
