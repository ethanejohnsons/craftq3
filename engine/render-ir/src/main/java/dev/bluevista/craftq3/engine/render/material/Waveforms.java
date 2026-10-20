package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Wave;

/**
 * Shader waveforms and noise. Every function here is defined with operations a GPU shader can
 * reproduce exactly: the noise lattice hash uses only 32-bit integer multiplication, xor and
 * logical shifts, so a GLSL {@code uint} implementation yields the same lattice values.
 */
public final class Waveforms {
  private static final int HASH_X = 0x8da6b343;
  private static final int HASH_Y = 0xd8163841;
  private static final int HASH_Z = 0xcb1ab31f;
  private static final int HASH_T = 0x165667b1;
  private static final int MIX_1 = 0x85ebca6b;
  private static final int MIX_2 = 0xc2b2ae35;
  private static final double UNIT_23 = 0x1.0p-23;

  private Waveforms() {}

  /** {@code base + amplitude * f(phase + seconds * frequency)}. */
  public static double evaluate(Wave wave, double seconds) {
    return evaluate(wave, seconds, 0);
  }

  /** As {@link #evaluate(Wave, double)} with an extra phase offset in cycles. */
  public static double evaluate(Wave wave, double seconds, double phaseOffset) {
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

  /** {@code value - floor(value)}. */
  public static double fraction(double value) {
    return value - Math.floor(value);
  }

  /**
   * Smooth 4D value noise in [-1, 1]: the 16 lattice corners around the point are hashed with
   * {@link #lattice} and blended with smoothstep weights.
   */
  public static double noise(double x, double y, double z, double time) {
    int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
    int iz = (int) Math.floor(z), it = (int) Math.floor(time);
    double fx = smooth(x - Math.floor(x)), fy = smooth(y - Math.floor(y));
    double fz = smooth(z - Math.floor(z)), ft = smooth(time - Math.floor(time));
    double result = 0;
    for (int corner = 0; corner < 16; corner++) {
      int ox = corner & 1, oy = (corner >>> 1) & 1, oz = (corner >>> 2) & 1, ot = corner >>> 3;
      double weight =
          (ox == 0 ? 1 - fx : fx)
              * (oy == 0 ? 1 - fy : fy)
              * (oz == 0 ? 1 - fz : fz)
              * (ot == 0 ? 1 - ft : ft);
      result += weight * lattice(ix + ox, iy + oy, iz + oz, it + ot);
    }
    return result;
  }

  /**
   * The lattice value at integer coordinates, in [-1, 1): a multiplicative hash of the coordinates
   * followed by the murmur3 finaliser; the top 24 bits become the value.
   */
  public static double lattice(int x, int y, int z, int t) {
    int h = x * HASH_X ^ y * HASH_Y ^ z * HASH_Z ^ t * HASH_T;
    h ^= h >>> 16;
    h *= MIX_1;
    h ^= h >>> 13;
    h *= MIX_2;
    h ^= h >>> 16;
    return (h >>> 8) * UNIT_23 - 1;
  }

  private static double smooth(double t) {
    return t * t * (3 - 2 * t);
  }
}
