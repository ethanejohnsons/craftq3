package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;

final class CollisionMath {
  static final Vec3 ZERO = new Vec3(0, 0, 0);
  static final Vec3[] AXES = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
  static final double CONTACT_EPSILON = 0.125;

  private CollisionMath() {}

  static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  static Vec3 subtract(Vec3 a, Vec3 b) {
    return new Vec3(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
  }

  static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }

  static double length(Vec3 v) {
    return Math.hypot(Math.hypot(v.x(), v.y()), v.z());
  }

  static Vec3 unit(Vec3 v) {
    double length = length(v);
    return length < 1e-12 ? ZERO : v.scale(1 / length);
  }

  static Vec3 lerp(Vec3 a, Vec3 b, double t) {
    return a.scale(1 - t).add(b.scale(t));
  }

  static double maxAbs(Vec3 p) {
    return Math.max(Math.abs(p.x()), Math.max(Math.abs(p.y()), Math.abs(p.z())));
  }

  static double minSupport(Vec3 n, Vec3 mins, Vec3 maxs) {
    return n.x() * (n.x() >= 0 ? mins.x() : maxs.x())
        + n.y() * (n.y() >= 0 ? mins.y() : maxs.y())
        + n.z() * (n.z() >= 0 ? mins.z() : maxs.z());
  }

  static double maxSupport(Vec3 n, Vec3 mins, Vec3 maxs) {
    return n.x() * (n.x() >= 0 ? maxs.x() : mins.x())
        + n.y() * (n.y() >= 0 ? maxs.y() : mins.y())
        + n.z() * (n.z() >= 0 ? maxs.z() : mins.z());
  }
}
