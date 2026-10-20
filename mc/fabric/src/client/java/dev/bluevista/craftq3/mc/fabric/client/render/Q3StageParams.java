package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.DynamicUniformStorage;

/**
 * Per-draw parameters of one Quake III shader stage, evaluated entirely by the {@code q3_stage} GPU
 * program. Time is not part of a stage: it comes from the per-frame {@link Q3ViewParams}, so a
 * stage's uniform block is identical every frame and animation costs no CPU work.
 *
 * <p>Layout ({@code Q3Stage} in {@code q3_stage.vsh}/{@code .fsh}, std140, all {@code vec4}):
 *
 * <pre>
 * Modes      x rgbGen   y wave func   z tcGen       w tcMod count
 * RgbWave    base       amp           phase         freq
 * ConstColor r          g             b             alphaGen const
 * Options    lightmap   alphaFunc     alphaGen      unused
 * TcMods[8]  two vec4 per tcMod: (type, p0, p1, p2), (p3, p4, p5, p6)
 * </pre>
 */
public record Q3StageParams(
    RgbGen rgbGen,
    Wave rgbWave,
    float red,
    float green,
    float blue,
    AlphaGen alphaGen,
    float alphaConst,
    AlphaFunc alphaFunc,
    TcGen tcGen,
    boolean lightmap,
    List<TcMod> tcMods)
    implements DynamicUniformStorage.DynamicUniform {
  /** Fixed tcMod capacity of the uniform block; Q3 allows 4 per stage (TR_MAX_TEXMODS). */
  public static final int MAX_TC_MODS = 4;

  public static final int UBO_SIZE =
      new Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().get()
          + MAX_TC_MODS * 2 * 16;

  public Q3StageParams {
    tcMods = List.copyOf(tcMods);
    if (tcMods.size() > MAX_TC_MODS)
      throw new IllegalArgumentException(
          "A stage has at most " + MAX_TC_MODS + " tcMods, got " + tcMods.size());
  }

  /** A stage that samples its texture with identity colour and no tcMods. */
  public static Q3StageParams identity() {
    return new Q3StageParams(
        RgbGen.IDENTITY,
        Wave.NONE,
        1,
        1,
        1,
        AlphaGen.IDENTITY,
        1,
        AlphaFunc.NONE,
        TcGen.BASE,
        false,
        List.of());
  }

  public Q3StageParams withRgbConst(float r, float g, float b) {
    return new Q3StageParams(
        RgbGen.CONST, rgbWave, r, g, b, alphaGen, alphaConst, alphaFunc, tcGen, lightmap, tcMods);
  }

  public Q3StageParams withRgbWave(Wave wave) {
    return new Q3StageParams(
        RgbGen.WAVE,
        wave,
        red,
        green,
        blue,
        alphaGen,
        alphaConst,
        alphaFunc,
        tcGen,
        lightmap,
        tcMods);
  }

  public Q3StageParams withRgbVertex() {
    return new Q3StageParams(
        RgbGen.VERTEX,
        rgbWave,
        red,
        green,
        blue,
        alphaGen,
        alphaConst,
        alphaFunc,
        tcGen,
        lightmap,
        tcMods);
  }

  public Q3StageParams withAlphaConst(float alpha) {
    return new Q3StageParams(
        rgbGen,
        rgbWave,
        red,
        green,
        blue,
        AlphaGen.CONST,
        alpha,
        alphaFunc,
        tcGen,
        lightmap,
        tcMods);
  }

  public Q3StageParams withAlphaFunc(AlphaFunc func) {
    return new Q3StageParams(
        rgbGen, rgbWave, red, green, blue, alphaGen, alphaConst, func, tcGen, lightmap, tcMods);
  }

  public Q3StageParams withTcGen(TcGen gen) {
    return new Q3StageParams(
        rgbGen, rgbWave, red, green, blue, alphaGen, alphaConst, alphaFunc, gen, lightmap, tcMods);
  }

  public Q3StageParams withLightmap() {
    return new Q3StageParams(
        rgbGen, rgbWave, red, green, blue, alphaGen, alphaConst, alphaFunc, tcGen, true, tcMods);
  }

  public Q3StageParams withTcMods(TcMod... mods) {
    return new Q3StageParams(
        rgbGen,
        rgbWave,
        red,
        green,
        blue,
        alphaGen,
        alphaConst,
        alphaFunc,
        tcGen,
        lightmap,
        List.of(mods));
  }

  @Override
  public void write(ByteBuffer buffer) {
    Std140Builder out = Std140Builder.intoBuffer(buffer);
    out.putVec4(rgbGen.ordinal(), rgbWave.func().ordinal(), tcGen.ordinal(), tcMods.size());
    out.putVec4(rgbWave.base(), rgbWave.amp(), rgbWave.phase(), rgbWave.freq());
    out.putVec4(red, green, blue, alphaConst);
    out.putVec4(lightmap ? 1 : 0, alphaFunc.ordinal(), alphaGen.ordinal(), 0);
    for (int i = 0; i < MAX_TC_MODS; i++) {
      float[] p = i < tcMods.size() ? tcMods.get(i).pack() : new float[8];
      out.putVec4(p[0], p[1], p[2], p[3]);
      out.putVec4(p[4], p[5], p[6], p[7]);
    }
  }

  /** {@code rgbGen}; ordinals are the shader's {@code Modes.x}. */
  public enum RgbGen {
    IDENTITY,
    VERTEX,
    CONST,
    WAVE
  }

  /** {@code alphaGen}; ordinals are the shader's {@code Options.z}. */
  public enum AlphaGen {
    IDENTITY,
    VERTEX,
    CONST
  }

  /** {@code alphaFunc}; ordinals are the shader's {@code Options.y}. */
  public enum AlphaFunc {
    NONE,
    GT0,
    LT128,
    GE128
  }

  /** {@code tcGen}; ordinals are the shader's {@code Modes.z}. */
  public enum TcGen {
    BASE,
    LIGHTMAP,
    ENVIRONMENT
  }

  /** Q3 wave functions; ordinals are the shader's wave selector. */
  public enum WaveFunc {
    SIN,
    SQUARE,
    TRIANGLE,
    SAWTOOTH,
    INVERSE_SAWTOOTH
  }

  /** {@code func base amplitude phase frequency}. */
  public record Wave(WaveFunc func, float base, float amp, float phase, float freq) {
    public static final Wave NONE = new Wave(WaveFunc.SIN, 1, 0, 0, 0);
  }

  /** One {@code tcMod}; {@link #pack()} gives its two {@code vec4}s, type first. */
  public sealed interface TcMod {
    float[] pack();

    record Scroll(float s, float t) implements TcMod {
      @Override
      public float[] pack() {
        return new float[] {1, s, t, 0, 0, 0, 0, 0};
      }
    }

    record Rotate(float degreesPerSecond) implements TcMod {
      @Override
      public float[] pack() {
        return new float[] {2, degreesPerSecond, 0, 0, 0, 0, 0, 0};
      }
    }

    /** {@code tcMod turb base amp phase freq}; Q3 ignores {@code base}. */
    record Turb(float amp, float phase, float freq) implements TcMod {
      @Override
      public float[] pack() {
        return new float[] {3, 0, amp, phase, freq, 0, 0, 0};
      }
    }

    record Scale(float s, float t) implements TcMod {
      @Override
      public float[] pack() {
        return new float[] {4, s, t, 0, 0, 0, 0, 0};
      }
    }

    record Stretch(Wave wave) implements TcMod {
      @Override
      public float[] pack() {
        return new float[] {
          5, wave.func().ordinal(), wave.base(), wave.amp(), wave.phase(), wave.freq(), 0, 0
        };
      }
    }
  }
}
