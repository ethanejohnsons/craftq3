package dev.bluevista.craftq3.engine.common.math;

/**
 * Allocation-free float vector operations on {@code float[3]} arrays, mirroring the original
 * engine's vector macros. Output arrays may alias inputs.
 */
public final class VecMath {
  private VecMath() {}

  public static void set(float[] out, float x, float y, float z) {
    out[0] = x;
    out[1] = y;
    out[2] = z;
  }

  public static void copy(float[] from, float[] out) {
    out[0] = from[0];
    out[1] = from[1];
    out[2] = from[2];
  }

  public static void add(float[] a, float[] b, float[] out) {
    out[0] = a[0] + b[0];
    out[1] = a[1] + b[1];
    out[2] = a[2] + b[2];
  }

  public static void subtract(float[] a, float[] b, float[] out) {
    out[0] = a[0] - b[0];
    out[1] = a[1] - b[1];
    out[2] = a[2] - b[2];
  }

  public static void scale(float[] a, float s, float[] out) {
    out[0] = a[0] * s;
    out[1] = a[1] * s;
    out[2] = a[2] * s;
  }

  /** {@code out = start + direction * scale}. */
  public static void multiplyAdd(float[] start, float scale, float[] direction, float[] out) {
    out[0] = start[0] + direction[0] * scale;
    out[1] = start[1] + direction[1] * scale;
    out[2] = start[2] + direction[2] * scale;
  }

  public static float dot(float[] a, float[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  public static void cross(float[] a, float[] b, float[] out) {
    float x = a[1] * b[2] - a[2] * b[1];
    float y = a[2] * b[0] - a[0] * b[2];
    float z = a[0] * b[1] - a[1] * b[0];
    out[0] = x;
    out[1] = y;
    out[2] = z;
  }

  public static float length(float[] a) {
    return (float) Math.sqrt(dot(a, a));
  }

  /** Normalizes in place and returns the original length; zero vectors stay zero. */
  public static float normalize(float[] a) {
    float length = length(a);
    if (length != 0) {
      float inverse = 1 / length;
      a[0] *= inverse;
      a[1] *= inverse;
      a[2] *= inverse;
    }
    return length;
  }

  /**
   * Converts pitch/yaw/roll degrees to forward, right and up axes, matching {@code AngleVectors}.
   * Any output may be {@code null}.
   */
  public static void angleVectors(float[] angles, float[] forward, float[] right, float[] up) {
    double yaw = Math.toRadians(angles[1]);
    double pitch = Math.toRadians(angles[0]);
    double roll = Math.toRadians(angles[2]);
    float sy = (float) Math.sin(yaw), cy = (float) Math.cos(yaw);
    float sp = (float) Math.sin(pitch), cp = (float) Math.cos(pitch);
    float sr = (float) Math.sin(roll), cr = (float) Math.cos(roll);
    if (forward != null) set(forward, cp * cy, cp * sy, -sp);
    if (right != null)
      set(right, -1 * sr * sp * cy + -1 * cr * -sy, -1 * sr * sp * sy + -1 * cr * cy, -1 * sr * cp);
    if (up != null) set(up, cr * sp * cy + -sr * -sy, cr * sp * sy + -sr * cy, cr * cp);
  }
}
