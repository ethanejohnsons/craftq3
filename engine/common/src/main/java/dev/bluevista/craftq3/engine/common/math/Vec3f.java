package dev.bluevista.craftq3.engine.common.math;

/**
 * An immutable single-precision 3-vector in Quake III coordinates (right-handed, +Z up). All
 * arithmetic is float, matching the original engine's {@code vec3_t}. Hot loops should use {@link
 * VecMath} on {@code float[3]} scratch arrays instead of allocating.
 */
public record Vec3f(float x, float y, float z) {
  public static final Vec3f ZERO = new Vec3f(0, 0, 0);

  public static Vec3f of(float[] v) {
    return new Vec3f(v[0], v[1], v[2]);
  }

  public float get(int axis) {
    return switch (axis) {
      case 0 -> x;
      case 1 -> y;
      case 2 -> z;
      default -> throw new IndexOutOfBoundsException(axis);
    };
  }

  public Vec3f add(Vec3f o) {
    return new Vec3f(x + o.x, y + o.y, z + o.z);
  }

  public Vec3f subtract(Vec3f o) {
    return new Vec3f(x - o.x, y - o.y, z - o.z);
  }

  public Vec3f scale(float s) {
    return new Vec3f(x * s, y * s, z * s);
  }

  /** {@code this + direction * scale}, the original {@code VectorMA}. */
  public Vec3f multiplyAdd(float scale, Vec3f direction) {
    return new Vec3f(x + direction.x * scale, y + direction.y * scale, z + direction.z * scale);
  }

  public Vec3f negate() {
    return new Vec3f(-x, -y, -z);
  }

  public float dot(Vec3f o) {
    return x * o.x + y * o.y + z * o.z;
  }

  public Vec3f cross(Vec3f o) {
    return new Vec3f(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
  }

  public float lengthSquared() {
    return x * x + y * y + z * z;
  }

  public float length() {
    return (float) Math.sqrt(lengthSquared());
  }

  public float distance(Vec3f o) {
    return subtract(o).length();
  }

  /** The unit vector, or {@link #ZERO} for a zero vector, as {@code VectorNormalize} does. */
  public Vec3f normalize() {
    float length = length();
    if (length == 0) return ZERO;
    float inverse = 1 / length;
    return new Vec3f(x * inverse, y * inverse, z * inverse);
  }

  public Vec3f lerp(Vec3f to, float fraction) {
    return new Vec3f(
        x + (to.x - x) * fraction, y + (to.y - y) * fraction, z + (to.z - z) * fraction);
  }

  public Vec3f min(Vec3f o) {
    return new Vec3f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
  }

  public Vec3f max(Vec3f o) {
    return new Vec3f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
  }

  public boolean isFinite() {
    return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
  }

  public float[] toArray() {
    return new float[] {x, y, z};
  }

  public void copyTo(float[] out) {
    out[0] = x;
    out[1] = y;
    out[2] = z;
  }
}
