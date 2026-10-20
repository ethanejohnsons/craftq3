package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.render.material.StageProgram.Shading;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexMod;

/**
 * The reference CPU evaluation of {@link StageProgram.Shading}: the colour and texture coordinates
 * a GPU shader must produce for the same inputs. Arithmetic is double precision, with the original
 * single-precision rounding points (generated coordinates become floats before modifiers run). Used
 * by tests and as the backend fallback; it allocates nothing per vertex.
 */
public final class StageEvaluator {
  private static final int MAP_OVERBRIGHT_LIMIT = 8;

  private StageEvaluator() {}

  /**
   * Mutable per-vertex input and output, reused across calls. Inputs: position, normal, base and
   * lightmap coordinates, colour ({@code 0xRRGGBBAA}). Outputs: {@link #outS}, {@link #outT},
   * {@link #outRgba}.
   */
  public static final class Vertex {
    public float px, py, pz, nx, ny, nz, s, t, ls, lt;
    public int rgba;
    public float outS, outT;
    public int outRgba;

    public Vertex set(
        float px,
        float py,
        float pz,
        float nx,
        float ny,
        float nz,
        float s,
        float t,
        float ls,
        float lt,
        int rgba) {
      this.px = px;
      this.py = py;
      this.pz = pz;
      this.nx = nx;
      this.ny = ny;
      this.nz = nz;
      this.s = s;
      this.t = t;
      this.ls = ls;
      this.lt = lt;
      this.rgba = rgba;
      return this;
    }

    /** Loads vertex {@code index} of {@code arrays}. */
    public Vertex load(VertexArrays arrays, int index) {
      float[] p = arrays.positions(), n = arrays.normals();
      float[] uv = arrays.texCoords(), lm = arrays.lightmapCoords();
      return set(
          p[index * 3],
          p[index * 3 + 1],
          p[index * 3 + 2],
          n[index * 3],
          n[index * 3 + 1],
          n[index * 3 + 2],
          uv[index * 2],
          uv[index * 2 + 1],
          lm[index * 2],
          lm[index * 2 + 1],
          arrays.rgba(index));
    }
  }

  /** Lighting and fog values for one vertex, seeded from the draw inputs. */
  private static final class Light {
    final float[] values = new float[9];
    final float[] fog = new float[3];
    final double[] rgb = new double[3];
    final double[] a = new double[3];
    final double[] b = new double[3];
    final double[] c = new double[3];

    void reset(ShadingInputs in) {
      in.ambientLight().copyTo(values);
      values[3] = in.directedLight().x();
      values[4] = in.directedLight().y();
      values[5] = in.directedLight().z();
      values[6] = in.lightDirection().x();
      values[7] = in.lightDirection().y();
      values[8] = in.lightDirection().z();
      in.fogColor().copyTo(fog);
    }
  }

  /** Evaluates one vertex with the draw's uniform lighting and fog colour. */
  public static void evaluate(Shading shading, ShadingInputs inputs, Vertex vertex) {
    Light light = new Light();
    light.reset(inputs);
    evaluate(shading, inputs, vertex, light);
  }

  /**
   * Evaluates vertices {@code [first, first + count)} of {@code source}, writing two texture
   * coordinates and four colour bytes per vertex at index {@code outFirst + (v - first)} of the
   * output arrays. {@code lighting} and {@code fog} may be null to use the uniform values.
   */
  public static void evaluate(
      Shading shading,
      ShadingInputs inputs,
      VertexArrays source,
      int first,
      int count,
      VertexLighting lighting,
      VertexFog fog,
      float[] texCoordsOut,
      byte[] colorsOut,
      int outFirst) {
    Vertex vertex = new Vertex();
    Light light = new Light();
    boolean perVertexLight =
        lighting != null
            && (shading.rgbGen().op() == StageProgram.RgbOp.LIGHTING_DIFFUSE
                || shading.alphaGen().op() == StageProgram.AlphaOp.LIGHTING_SPECULAR);
    boolean perVertexFog = fog != null && shading.rgbGen().op() == StageProgram.RgbOp.FOG;
    light.reset(inputs);
    for (int i = 0; i < count; i++) {
      vertex.load(source, first + i);
      if (perVertexLight) {
        light.reset(inputs);
        lighting.sample(
            vertex.px, vertex.py, vertex.pz, vertex.nx, vertex.ny, vertex.nz, light.values);
      }
      if (perVertexFog) fog.color(vertex.px, vertex.py, vertex.pz, light.fog);
      evaluate(shading, inputs, vertex, light);
      int out = outFirst + i;
      texCoordsOut[out * 2] = vertex.outS;
      texCoordsOut[out * 2 + 1] = vertex.outT;
      VertexArrays.putRgba(colorsOut, out, vertex.outRgba);
    }
  }

  private static void evaluate(Shading shading, ShadingInputs in, Vertex v, Light light) {
    coordinates(shading, in, v, light);
    double[] rgb = light.rgb;
    color(shading, in, v, light, rgb);
    double alpha = alpha(shading, in, v, light);
    v.outRgba =
        channel(rgb[0]) << 24 | channel(rgb[1]) << 16 | channel(rgb[2]) << 8 | channel(alpha);
  }

  /** True when the stage's vertex attributes change per frame (see {@link Shading#dynamic()}). */
  public static boolean isDynamic(StageProgram stage) {
    return stage.shading().dynamic();
  }

  /**
   * Restores BSP lighting stored with {@code overbrightBits} of headroom; scaling by the brightest
   * channel at saturation preserves hue. Alpha is unchanged.
   */
  public static int restoreMapLightColor(int rgba, int overbrightBits) {
    if (overbrightBits < 0 || overbrightBits > MAP_OVERBRIGHT_LIMIT)
      throw new IllegalArgumentException("Overbright shift must be 0..8");
    double r = (rgba >>> 24) * (1 << overbrightBits);
    double g = ((rgba >>> 16) & 255) * (1 << overbrightBits);
    double b = ((rgba >>> 8) & 255) * (1 << overbrightBits);
    double divisor = Math.max(255, Math.max(r, Math.max(g, b)));
    return channel(r / divisor) << 24
        | channel(g / divisor) << 16
        | channel(b / divisor) << 8
        | (rgba & 255);
  }

  /** {@code round(clamp(value, 0, 1) * 255)}. */
  public static int channel(double normalized) {
    return (int) Math.round(Math.clamp(normalized, 0, 1) * 255);
  }

  private static void coordinates(Shading shading, ShadingInputs in, Vertex v, Light light) {
    Vec3f eye = in.viewOrigin();
    float startS, startT;
    switch (shading.texGen().op()) {
      case BASE -> {
        startS = v.s;
        startT = v.t;
      }
      case LIGHTMAP -> {
        startS = v.ls;
        startT = v.lt;
      }
      case IDENTITY -> {
        startS = 0;
        startT = 0;
      }
      case VECTOR -> {
        Vec3f sv = shading.texGen().s(), tv = shading.texGen().t();
        startS = (float) ((double) v.px * sv.x() + (double) v.py * sv.y() + (double) v.pz * sv.z());
        startT = (float) ((double) v.px * tv.x() + (double) v.py * tv.y() + (double) v.pz * tv.z());
      }
      case ENVIRONMENT -> {
        double[] view =
            unit(
                light.a, (double) eye.x() - v.px, (double) eye.y() - v.py, (double) eye.z() - v.pz);
        double[] normal = unit(light.b, v.nx, v.ny, v.nz);
        double d = 2 * dot(normal, view);
        startS = (float) (0.5 + 0.5 * (normal[1] * d - view[1]));
        startT = (float) (0.5 - 0.5 * (normal[2] * d - view[2]));
      }
      case FOG -> {
        double dx = (double) v.px - eye.x(), dy = (double) v.py - eye.y();
        double dz = (double) v.pz - eye.z();
        startS = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 256);
        startT = 0.5f;
      }
      default -> throw new IllegalStateException();
    }
    double s = startS, t = startT;
    double seconds = in.seconds();
    for (TexMod mod : shading.texMods()) {
      double oldS = s, oldT = t;
      switch (mod.op()) {
        case SCROLL -> {
          s += Waveforms.fraction(seconds * mod.p0());
          t += Waveforms.fraction(seconds * mod.p1());
        }
        case SCALE -> {
          s *= mod.p0();
          t *= mod.p1();
        }
        case ROTATE -> {
          double angle = Math.toRadians(-mod.p0() * seconds);
          double sin = Math.sin(angle), cos = Math.cos(angle);
          s = (oldS - 0.5) * cos - (oldT - 0.5) * sin + 0.5;
          t = (oldS - 0.5) * sin + (oldT - 0.5) * cos + 0.5;
        }
        case STRETCH -> {
          double value = Waveforms.evaluate(mod.wave(), seconds);
          // A zero stretch has no finite inverse; keep this degenerate stage bounded.
          double inverse = 1 / (Math.abs(value) < 1e-6 ? Math.copySign(1e-6, value) : value);
          s = (s - 0.5) * inverse + 0.5;
          t = (t - 0.5) * inverse + 0.5;
        }
        case TRANSFORM -> {
          s = oldS * mod.p0() + oldT * mod.p2() + mod.p4();
          t = oldS * mod.p1() + oldT * mod.p3() + mod.p5();
        }
        case TURB -> {
          double cycles = mod.wave().phase() + seconds * mod.wave().frequency();
          double amplitude = mod.wave().amplitude();
          s += Math.sin((((double) v.px + v.pz) / 1024 + cycles) * Math.TAU) * amplitude;
          t += Math.sin(((double) v.py / 1024 + cycles) * Math.TAU) * amplitude;
        }
        case ENTITY_TRANSLATE -> {
          s += in.entityTexOffset().x();
          t += in.entityTexOffset().y();
        }
        default -> throw new IllegalStateException();
      }
    }
    v.outS = (float) s;
    v.outT = (float) t;
  }

  private static void color(
      Shading shading, ShadingInputs in, Vertex v, Light light, double[] out) {
    double vr = (v.rgba >>> 24) / 255.0;
    double vg = ((v.rgba >>> 16) & 255) / 255.0;
    double vb = ((v.rgba >>> 8) & 255) / 255.0;
    double identity = in.identityLight();
    switch (shading.rgbGen().op()) {
      case IDENTITY -> set(out, 1, 1, 1);
      case IDENTITY_LIGHTING -> set(out, identity, identity, identity);
      case VERTEX -> set(out, vr * identity, vg * identity, vb * identity);
      case EXACT_VERTEX -> set(out, vr, vg, vb);
      case ONE_MINUS_VERTEX ->
          set(out, (1 - vr) * identity, (1 - vg) * identity, (1 - vb) * identity);
      case ENTITY -> set(out, in.entityColor().x(), in.entityColor().y(), in.entityColor().z());
      case ONE_MINUS_ENTITY ->
          set(
              out,
              1 - (double) in.entityColor().x(),
              1 - (double) in.entityColor().y(),
              1 - (double) in.entityColor().z());
      case LIGHTING_DIFFUSE -> {
        float[] l = light.values;
        double lambert =
            Math.max(0, dot(unit(light.a, v.nx, v.ny, v.nz), unit(light.b, l[6], l[7], l[8])));
        set(out, l[0] + l[3] * lambert, l[1] + l[4] * lambert, l[2] + l[5] * lambert);
      }
      case WAVE -> {
        double value = Waveforms.evaluate(shading.rgbGen().wave(), in.seconds()) * identity;
        set(out, value, value, value);
      }
      case CONSTANT -> {
        Vec3f c = shading.rgbGen().constant();
        set(out, c.x(), c.y(), c.z());
      }
      case FOG -> set(out, light.fog[0], light.fog[1], light.fog[2]);
      default -> throw new IllegalStateException();
    }
  }

  private static double alpha(Shading shading, ShadingInputs in, Vertex v, Light light) {
    Vec3f eye = in.viewOrigin();
    return switch (shading.alphaGen().op()) {
      case IDENTITY -> 1;
      case VERTEX -> (v.rgba & 255) / 255.0;
      case ONE_MINUS_VERTEX -> 1 - (v.rgba & 255) / 255.0;
      case ENTITY -> in.entityAlpha();
      case ONE_MINUS_ENTITY -> 1 - (double) in.entityAlpha();
      case LIGHTING_SPECULAR -> {
        float[] l = light.values;
        double[] normal = unit(light.a, v.nx, v.ny, v.nz);
        double[] toLight = unit(light.b, l[6], l[7], l[8]);
        double d = 2 * dot(normal, toLight);
        double[] view =
            unit(
                light.c, (double) eye.x() - v.px, (double) eye.y() - v.py, (double) eye.z() - v.pz);
        double reflected =
            (normal[0] * d - toLight[0]) * view[0]
                + (normal[1] * d - toLight[1]) * view[1]
                + (normal[2] * d - toLight[2]) * view[2];
        yield Math.pow(Math.max(0, reflected), 4);
      }
      case WAVE -> Waveforms.evaluate(shading.alphaGen().wave(), in.seconds());
      case CONSTANT -> shading.alphaGen().constant();
      case PORTAL -> {
        double dx = (double) v.px - eye.x(), dy = (double) v.py - eye.y();
        double dz = (double) v.pz - eye.z();
        yield Math.sqrt(dx * dx + dy * dy + dz * dz)
            / Math.max(1e-6, shading.alphaGen().portalRange());
      }
      default -> throw new IllegalStateException();
    };
  }

  /** Writes the unit vector of (x, y, z) to {@code out}; vectors shorter than 1e-12 become zero. */
  private static double[] unit(double[] out, double x, double y, double z) {
    double length = Math.sqrt(x * x + y * y + z * z);
    if (length > 1e-12) {
      double inverse = 1 / length;
      out[0] = x * inverse;
      out[1] = y * inverse;
      out[2] = z * inverse;
    } else {
      out[0] = out[1] = out[2] = 0;
    }
    return out;
  }

  private static double dot(double[] a, double[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  private static void set(double[] out, double r, double g, double b) {
    out[0] = r;
    out[1] = g;
    out[2] = b;
  }
}
