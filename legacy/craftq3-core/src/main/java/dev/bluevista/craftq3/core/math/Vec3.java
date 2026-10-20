package dev.bluevista.craftq3.core.math;

public record Vec3(double x, double y, double z) {
  public Vec3 {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
      throw new IllegalArgumentException("Vector components must be finite");
    }
  }

  public Vec3 add(Vec3 other) {
    return new Vec3(x + other.x, y + other.y, z + other.z);
  }

  public Vec3 scale(double value) {
    return new Vec3(x * value, y * value, z * value);
  }
}
