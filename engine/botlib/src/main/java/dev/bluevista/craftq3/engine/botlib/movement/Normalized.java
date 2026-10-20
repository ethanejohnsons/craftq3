package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * A vector normalized as the original {@code VectorNormalize} does it, shared by the travel
 * executors: the direction is scaled by the rounded inverse root, and the returned length is the
 * squared length times that inverse root (which can differ from the plain root in the last bit). A
 * vector whose squared length is zero is returned unchanged with length zero.
 */
record Normalized(Vec3f direction, float length) {
  static Normalized of(Vec3f vector) {
    float squared = vector.lengthSquared();
    if (!Float.isFinite(squared))
      throw new IllegalArgumentException("Movement vector exceeds float range");
    if (squared == 0) return new Normalized(vector, 0);
    float inverse = 1 / (float) Math.sqrt(squared);
    return new Normalized(vector.scale(inverse), squared * inverse);
  }

  /** The horizontal part of {@code vector}, normalized. */
  static Normalized horizontal(Vec3f vector) {
    return of(new Vec3f(vector.x(), vector.y(), 0));
  }

  /** {@code target - origin}, normalized. */
  static Normalized towards(Vec3f target, Vec3f origin) {
    return of(target.subtract(origin));
  }

  /** {@code target - origin} without its height, normalized. */
  static Normalized horizontallyTowards(Vec3f target, Vec3f origin) {
    return horizontal(target.subtract(origin));
  }
}
