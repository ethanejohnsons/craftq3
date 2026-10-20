package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;

/** Q3 angles are pitch, yaw, roll in degrees, with positive pitch looking downward. */
final class RigidTransform {
  static final RigidTransform IDENTITY = new RigidTransform(CollisionMath.ZERO, CollisionMath.ZERO);
  private final Vec3 origin, x, y, z;

  RigidTransform(Vec3 origin, Vec3 angles) {
    if (CollisionMath.maxAbs(origin) > 1e12)
      throw new IllegalArgumentException("Model origin exceeds numeric range");
    this.origin = origin;
    double p = Math.toRadians(angles.x()),
        yaw = Math.toRadians(angles.y()),
        r = Math.toRadians(angles.z());
    double cp = Math.cos(p),
        sp = Math.sin(p),
        cy = Math.cos(yaw),
        sy = Math.sin(yaw),
        cr = Math.cos(r),
        sr = Math.sin(r);
    x = new Vec3(cp * cy, cp * sy, -sp);
    y = new Vec3(cy * sp * sr - sy * cr, sy * sp * sr + cy * cr, cp * sr);
    z = new Vec3(cy * sp * cr + sy * sr, sy * sp * cr - cy * sr, cp * cr);
  }

  Vec3 vector(Vec3 value) {
    return x.scale(value.x()).add(y.scale(value.y())).add(z.scale(value.z()));
  }

  Vec3 point(Vec3 value) {
    return vector(value).add(origin);
  }

  TraceResult.Plane plane(TraceResult.Plane value) {
    Vec3 normal = vector(value.normal());
    return new TraceResult.Plane(normal, value.distance() + CollisionMath.dot(normal, origin));
  }
}
