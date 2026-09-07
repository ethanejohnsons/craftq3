package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;

record Bounds(Vec3 min, Vec3 max) {
  static Bounds of(Vec3 a, Vec3 b, Vec3 c) {
    return new Bounds(
        new Vec3(
            Math.min(a.x(), Math.min(b.x(), c.x())),
            Math.min(a.y(), Math.min(b.y(), c.y())),
            Math.min(a.z(), Math.min(b.z(), c.z()))),
        new Vec3(
            Math.max(a.x(), Math.max(b.x(), c.x())),
            Math.max(a.y(), Math.max(b.y(), c.y())),
            Math.max(a.z(), Math.max(b.z(), c.z()))));
  }

  boolean intersects(TraceRequest request) {
    double epsilon = CollisionMath.CONTACT_EPSILON;
    return Math.min(request.start().x(), request.end().x()) + request.mins().x()
            <= max.x() + epsilon
        && Math.max(request.start().x(), request.end().x()) + request.maxs().x()
            >= min.x() - epsilon
        && Math.min(request.start().y(), request.end().y()) + request.mins().y()
            <= max.y() + epsilon
        && Math.max(request.start().y(), request.end().y()) + request.maxs().y()
            >= min.y() - epsilon
        && Math.min(request.start().z(), request.end().z()) + request.mins().z()
            <= max.z() + epsilon
        && Math.max(request.start().z(), request.end().z()) + request.maxs().z()
            >= min.z() - epsilon;
  }
}
