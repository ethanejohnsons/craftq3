package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Wave;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveFunction;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaGen;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaTest;
import dev.bluevista.craftq3.engine.render.material.StageProgram.FogMode;
import dev.bluevista.craftq3.engine.render.material.StageProgram.RgbGen;
import dev.bluevista.craftq3.engine.render.material.StageProgram.RgbOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.Shading;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexGen;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexGenOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexMod;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexModOp;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed float layouts for uploading programs as uniform blocks. Enum values are stored as their
 * {@code code()} (exact in a float). Offsets are in floats; blocks are padded to whole vec4s.
 *
 * <p>Stage block ({@link #STAGE_FLOATS}): {@code [0]} rgb op, {@code [1]} rgb wave function, {@code
 * [2..5]} rgb wave base/amplitude/phase/frequency, {@code [6..8]} rgb constant; {@code [9]} alpha
 * op, {@code [10]} alpha wave function, {@code [11..14]} alpha wave, {@code [15]} alpha constant,
 * {@code [16]} portal range; {@code [17]} tcGen op, {@code [18..20]} s vector, {@code [21..23]} t
 * vector; {@code [24]} tcMod count; then {@link #MAX_TEX_MODS} records of {@link #TEX_MOD_FLOATS}
 * at {@link #TEX_MODS}: op, wave function, wave base/amplitude/phase/frequency, p0..p5; then alpha
 * test and fog mode at {@link #ALPHA_TEST} and {@link #FOG_MODE}.
 *
 * <p>Deform block ({@link #DEFORM_FLOATS}): {@code [0]} count, then {@link #MAX_DEFORMS} records of
 * {@link #DEFORM_RECORD_FLOATS}: op, wave function, wave base/amplitude/phase/frequency, p0..p2.
 */
public final class ProgramLayout {
  /** Stages per material the encoding keeps (the original renderer allows 8). */
  public static final int MAX_STAGES = 8;

  /** Texture-coordinate modifiers per stage the encoding keeps (the original allows 4). */
  public static final int MAX_TEX_MODS = 8;

  /** Vertex deforms per material the encoding keeps (the original allows 3). */
  public static final int MAX_DEFORMS = 4;

  public static final int TEX_MOD_FLOATS = 12;
  public static final int TEX_MODS = 28;
  public static final int ALPHA_TEST = TEX_MODS + MAX_TEX_MODS * TEX_MOD_FLOATS;
  public static final int FOG_MODE = ALPHA_TEST + 1;
  public static final int STAGE_FLOATS = (FOG_MODE + 1 + 3) / 4 * 4;

  public static final int DEFORM_RECORD_FLOATS = 9;
  public static final int DEFORM_FLOATS = (1 + MAX_DEFORMS * DEFORM_RECORD_FLOATS + 3) / 4 * 4;

  private static final WaveFunction[] FUNCTIONS = WaveFunction.values();

  private ProgramLayout() {}

  /** Writes {@code stage}'s shading and fragment tests at {@code out[offset..]}. */
  public static void packStage(StageProgram stage, float[] out, int offset) {
    Shading s = stage.shading();
    java.util.Arrays.fill(out, offset, offset + STAGE_FLOATS, 0);
    out[offset] = s.rgbGen().op().code();
    wave(s.rgbGen().wave(), out, offset + 1);
    vector(s.rgbGen().constant(), out, offset + 6);
    out[offset + 9] = s.alphaGen().op().code();
    wave(s.alphaGen().wave(), out, offset + 10);
    out[offset + 15] = s.alphaGen().constant();
    out[offset + 16] = s.alphaGen().portalRange();
    out[offset + 17] = s.texGen().op().code();
    vector(s.texGen().s(), out, offset + 18);
    vector(s.texGen().t(), out, offset + 21);
    out[offset + 24] = s.texMods().size();
    for (int i = 0; i < s.texMods().size(); i++) {
      TexMod mod = s.texMods().get(i);
      int at = offset + TEX_MODS + i * TEX_MOD_FLOATS;
      out[at] = mod.op().code();
      wave(mod.wave(), out, at + 1);
      out[at + 6] = mod.p0();
      out[at + 7] = mod.p1();
      out[at + 8] = mod.p2();
      out[at + 9] = mod.p3();
      out[at + 10] = mod.p4();
      out[at + 11] = mod.p5();
    }
    out[offset + ALPHA_TEST] = stage.state().alphaTest().code();
    out[offset + FOG_MODE] = stage.state().fogMode().code();
  }

  /** Reads back the shading written by {@link #packStage}. */
  public static Shading unpackShading(float[] in, int offset) {
    RgbGen rgb =
        new RgbGen(RgbOp.values()[(int) in[offset]], wave(in, offset + 1), vector(in, offset + 6));
    AlphaGen alpha =
        new AlphaGen(
            AlphaOp.values()[(int) in[offset + 9]],
            wave(in, offset + 10),
            in[offset + 15],
            in[offset + 16]);
    TexGen tex =
        new TexGen(
            TexGenOp.values()[(int) in[offset + 17]],
            vector(in, offset + 18),
            vector(in, offset + 21));
    int count = (int) in[offset + 24];
    List<TexMod> mods = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int at = offset + TEX_MODS + i * TEX_MOD_FLOATS;
      mods.add(
          new TexMod(
              TexModOp.values()[(int) in[at]],
              wave(in, at + 1),
              in[at + 6],
              in[at + 7],
              in[at + 8],
              in[at + 9],
              in[at + 10],
              in[at + 11]));
    }
    return new Shading(rgb, alpha, tex, mods);
  }

  public static AlphaTest unpackAlphaTest(float[] in, int offset) {
    return AlphaTest.values()[(int) in[offset + ALPHA_TEST]];
  }

  public static FogMode unpackFogMode(float[] in, int offset) {
    return FogMode.values()[(int) in[offset + FOG_MODE]];
  }

  /** Writes the material's deforms at {@code out[offset..]}. */
  public static void packDeforms(List<DeformProgram> deforms, float[] out, int offset) {
    if (deforms.size() > MAX_DEFORMS) throw new IllegalArgumentException("Too many deforms");
    java.util.Arrays.fill(out, offset, offset + DEFORM_FLOATS, 0);
    out[offset] = deforms.size();
    for (int i = 0; i < deforms.size(); i++) {
      DeformProgram d = deforms.get(i);
      int at = offset + 1 + i * DEFORM_RECORD_FLOATS;
      out[at] = d.op().code();
      wave(d.wave(), out, at + 1);
      out[at + 6] = d.p0();
      out[at + 7] = d.p1();
      out[at + 8] = d.p2();
    }
  }

  public static List<DeformProgram> unpackDeforms(float[] in, int offset) {
    int count = (int) in[offset];
    List<DeformProgram> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int at = offset + 1 + i * DEFORM_RECORD_FLOATS;
      result.add(
          new DeformProgram(
              DeformProgram.Op.values()[(int) in[at]],
              wave(in, at + 1),
              in[at + 6],
              in[at + 7],
              in[at + 8]));
    }
    return result;
  }

  private static void wave(Wave wave, float[] out, int at) {
    out[at] = wave.function().ordinal();
    out[at + 1] = wave.base();
    out[at + 2] = wave.amplitude();
    out[at + 3] = wave.phase();
    out[at + 4] = wave.frequency();
  }

  private static Wave wave(float[] in, int at) {
    return new Wave(FUNCTIONS[(int) in[at]], in[at + 1], in[at + 2], in[at + 3], in[at + 4]);
  }

  private static void vector(Vec3f v, float[] out, int at) {
    out[at] = v.x();
    out[at + 1] = v.y();
    out[at + 2] = v.z();
  }

  private static Vec3f vector(float[] in, int at) {
    return new Vec3f(in[at], in[at + 1], in[at + 2]);
  }
}
