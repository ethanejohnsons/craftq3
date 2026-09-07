package dev.bluevista.craftq3.render.material;

import dev.bluevista.craftq3.assets.shader.ShaderDefinition.Wave;
import dev.bluevista.craftq3.core.math.Vec3;

/** Small independent math helpers; no host or engine implementation is shared here. */
final class ShaderMath {
  static final Vec3 ZERO = new Vec3(0, 0, 0);

  private ShaderMath() {}

  static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  static Vec3 subtract(Vec3 a, Vec3 b) {
    return a.add(b.scale(-1));
  }

  static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }

  static Vec3 normalize(Vec3 v) {
    double length = Math.sqrt(dot(v, v));
    return length > 1e-12 ? v.scale(1 / length) : ZERO;
  }

  static double fraction(double value) {
    return value - Math.floor(value);
  }

  static double wave(Wave wave, double seconds, double phaseOffset) {
    double cycles = wave.phase() + phaseOffset + seconds * wave.frequency();
    double t = fraction(cycles);
    double value =
        switch (wave.function()) {
          case SIN -> Math.sin(t * Math.TAU);
          case TRIANGLE -> t < 0.25 ? 4 * t : t < 0.75 ? 2 - 4 * t : 4 * t - 4;
          case SQUARE -> t < 0.5 ? 1 : -1;
          case SAWTOOTH -> t;
          case INVERSE_SAWTOOTH -> 1 - t;
          case NOISE -> noise(0, 0, 0, cycles);
        };
    return wave.base() + wave.amplitude() * value;
  }

  /**
   * Deterministic interpolated value noise, independently defined rather than Q3's random table.
   */
  static double noise(double x, double y, double z, double time) {
    double[] coordinates = {x, y, z, time};
    long[] base = new long[4];
    double[] fraction = new double[4];
    for (int axis = 0; axis < 4; axis++) {
      base[axis] = (long) Math.floor(coordinates[axis]);
      double t = ShaderMath.fraction(coordinates[axis]);
      fraction[axis] = t * t * (3 - 2 * t);
    }
    double result = 0;
    for (int corner = 0; corner < 16; corner++) {
      long hash = 0x6a09e667f3bcc909L;
      double weight = 1;
      for (int axis = 0; axis < 4; axis++) {
        int offset = (corner >>> axis) & 1;
        weight *= offset == 0 ? 1 - fraction[axis] : fraction[axis];
        long value = base[axis] + offset + 0x9e3779b97f4a7c15L * (axis + 1L);
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        hash ^= value ^ (value >>> 31);
      }
      result += weight * ((hash >>> 11) * 0x1.0p-52 - 1);
    }
    return result;
  }

  static int channel(double normalized) {
    return (int) Math.round(Math.clamp(normalized, 0, 1) * 255);
  }
}
