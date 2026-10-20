package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.BlendFactor;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Wave;
import java.util.List;
import java.util.Objects;

/**
 * One compiled material stage: fixed-function state plus a shading program that a vertex/fragment
 * shader evaluates from per-draw uniforms ({@link ShadingInputs}). Every enum carries a stable
 * {@code code()} used by {@link ProgramLayout} when the program is packed into uniform floats.
 */
public record StageProgram(TextureBundle texture, RenderState state, Shading shading) {
  public StageProgram {
    Objects.requireNonNull(texture, "texture");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(shading, "shading");
  }

  /**
   * Pipeline state of a stage.
   *
   * @param fogMode how this stage's colours are adjusted for a fog volume when the material is not
   *     fogged by a separate pass
   * @param detail a {@code detail} stage, which the backend may skip at low detail
   */
  public record RenderState(
      BlendFactor sourceBlend,
      BlendFactor destinationBlend,
      boolean depthWrite,
      DepthTest depthTest,
      AlphaTest alphaTest,
      FogMode fogMode,
      boolean detail) {
    public RenderState {
      Objects.requireNonNull(sourceBlend, "sourceBlend");
      Objects.requireNonNull(destinationBlend, "destinationBlend");
      Objects.requireNonNull(depthTest, "depthTest");
      Objects.requireNonNull(alphaTest, "alphaTest");
      Objects.requireNonNull(fogMode, "fogMode");
    }

    /** {@code blendFunc GL_ONE GL_ZERO}: the stage replaces the framebuffer colour. */
    public boolean opaque() {
      return sourceBlend == BlendFactor.ONE && destinationBlend == BlendFactor.ZERO;
    }
  }

  /** Per-vertex colour, alpha and texture-coordinate generation. */
  public record Shading(RgbGen rgbGen, AlphaGen alphaGen, TexGen texGen, List<TexMod> texMods) {
    public Shading {
      Objects.requireNonNull(rgbGen, "rgbGen");
      Objects.requireNonNull(alphaGen, "alphaGen");
      Objects.requireNonNull(texGen, "texGen");
      texMods = List.copyOf(texMods);
    }

    /**
     * True when vertex attributes change with time, entity, view or lighting, so a CPU backend
     * cannot cache the evaluated stage. Texture animation is independent of this.
     */
    public boolean dynamic() {
      boolean coordinates = texGen.op() == TexGenOp.ENVIRONMENT || texGen.op() == TexGenOp.FOG;
      boolean color =
          switch (rgbGen.op()) {
            case ENTITY, ONE_MINUS_ENTITY, LIGHTING_DIFFUSE, FOG -> true;
            case WAVE -> animated(rgbGen.wave());
            default -> false;
          };
      boolean alpha =
          switch (alphaGen.op()) {
            case ENTITY, ONE_MINUS_ENTITY, LIGHTING_SPECULAR, PORTAL -> true;
            case WAVE -> animated(alphaGen.wave());
            default -> false;
          };
      return coordinates || color || alpha || texMods.stream().anyMatch(TexMod::animated);
    }

    /** True when the program reads the entity colour, alpha or texture offset uniforms. */
    public boolean entityDependent() {
      return rgbGen.op() == RgbOp.ENTITY
          || rgbGen.op() == RgbOp.ONE_MINUS_ENTITY
          || alphaGen.op() == AlphaOp.ENTITY
          || alphaGen.op() == AlphaOp.ONE_MINUS_ENTITY
          || texMods.stream().anyMatch(m -> m.op() == TexModOp.ENTITY_TRANSLATE);
    }
  }

  static boolean animated(Wave wave) {
    return wave.frequency() != 0 && wave.amplitude() != 0;
  }

  /** Depth comparison; {@code depthFunc disable} becomes {@link #ALWAYS}. */
  public enum DepthTest {
    LEQUAL,
    EQUAL,
    ALWAYS;

    public int code() {
      return ordinal();
    }
  }

  /** Alpha test discarding fragments that fail {@code alpha <op> threshold}. */
  public enum AlphaTest {
    NONE,
    /** Keep {@code alpha > 0}. */
    GT0,
    /** Keep {@code alpha < 0.5}. */
    LT128,
    /** Keep {@code alpha >= 0.5}. */
    GE128;

    public int code() {
      return ordinal();
    }

    /** Whether a fragment with {@code alpha} (0..1) survives. */
    public boolean passes(float alpha) {
      return switch (this) {
        case NONE -> true;
        case GT0 -> alpha > 0;
        case LT128 -> alpha < 0.5f;
        case GE128 -> alpha >= 0.5f;
      };
    }
  }

  /**
   * Colour adjustment of a translucent stage drawn inside a fog volume, chosen from its blend
   * function: additive stages fade their colour, alpha-blended stages their alpha, premultiplied
   * stages both, and filters fade toward white.
   */
  public enum FogMode {
    NONE,
    MODULATE_RGB,
    MODULATE_ALPHA,
    MODULATE_RGBA,
    BLEND_TO_WHITE;

    public int code() {
      return ordinal();
    }
  }

  public enum RgbOp {
    IDENTITY,
    IDENTITY_LIGHTING,
    VERTEX,
    EXACT_VERTEX,
    ONE_MINUS_VERTEX,
    ENTITY,
    ONE_MINUS_ENTITY,
    LIGHTING_DIFFUSE,
    WAVE,
    CONSTANT,
    FOG;

    public int code() {
      return ordinal();
    }
  }

  /** {@code rgbGen}; {@code wave} is used by {@link RgbOp#WAVE}, {@code constant} by CONSTANT. */
  public record RgbGen(RgbOp op, Wave wave, Vec3f constant) {
    public RgbGen {
      Objects.requireNonNull(op, "op");
      Objects.requireNonNull(wave, "wave");
      Objects.requireNonNull(constant, "constant");
    }

    public static RgbGen of(RgbOp op) {
      return new RgbGen(op, Wave.ONE, new Vec3f(1, 1, 1));
    }
  }

  public enum AlphaOp {
    IDENTITY,
    VERTEX,
    ONE_MINUS_VERTEX,
    ENTITY,
    ONE_MINUS_ENTITY,
    LIGHTING_SPECULAR,
    WAVE,
    CONSTANT,
    PORTAL;

    public int code() {
      return ordinal();
    }
  }

  /** {@code alphaGen}; {@code portalRange} is the distance at which a portal becomes opaque. */
  public record AlphaGen(AlphaOp op, Wave wave, float constant, float portalRange) {
    public AlphaGen {
      Objects.requireNonNull(op, "op");
      Objects.requireNonNull(wave, "wave");
    }

    public static AlphaGen of(AlphaOp op) {
      return new AlphaGen(op, Wave.ONE, 1, 256);
    }
  }

  public enum TexGenOp {
    BASE,
    LIGHTMAP,
    ENVIRONMENT,
    VECTOR,
    IDENTITY,
    FOG;

    public int code() {
      return ordinal();
    }
  }

  /** {@code tcGen}; {@code s} and {@code t} are the projection vectors of {@link #VECTOR}. */
  public record TexGen(TexGenOp op, Vec3f s, Vec3f t) {
    public TexGen {
      Objects.requireNonNull(op, "op");
      Objects.requireNonNull(s, "s");
      Objects.requireNonNull(t, "t");
    }

    public static TexGen of(TexGenOp op) {
      return new TexGen(op, new Vec3f(1, 0, 0), new Vec3f(0, 1, 0));
    }
  }

  public enum TexModOp {
    /** {@code wave} holds base, amplitude, phase and frequency; position-dependent sine offset. */
    TURB,
    /** p0, p1: s and t factors. */
    SCALE,
    /** p0, p1: s and t units per second. */
    SCROLL,
    /** {@code wave}: the scale factor around the texture centre is its inverse. */
    STRETCH,
    /** p0..p5: m00, m01, m10, m11, t0, t1. */
    TRANSFORM,
    /** p0: degrees per second around the texture centre. */
    ROTATE,
    /** Adds the entity's shader texture coordinate offset. */
    ENTITY_TRANSLATE;

    public int code() {
      return ordinal();
    }
  }

  /** One ordered texture-coordinate modifier; parameters are interpreted per {@link TexModOp}. */
  public record TexMod(
      TexModOp op, Wave wave, float p0, float p1, float p2, float p3, float p4, float p5) {
    public TexMod {
      Objects.requireNonNull(op, "op");
      Objects.requireNonNull(wave, "wave");
    }

    public static TexMod of(TexModOp op, float... parameters) {
      float[] p = new float[6];
      System.arraycopy(parameters, 0, p, 0, Math.min(6, parameters.length));
      return new TexMod(op, Wave.ONE, p[0], p[1], p[2], p[3], p[4], p[5]);
    }

    public static TexMod waved(TexModOp op, Wave wave) {
      return new TexMod(op, wave, 0, 0, 0, 0, 0, 0);
    }

    /** True when the modifier changes with time or entity state. */
    public boolean animated() {
      return switch (op) {
        case SCROLL -> p0 != 0 || p1 != 0;
        case ROTATE -> p0 != 0;
        case STRETCH -> StageProgram.animated(wave);
        case TURB -> wave.frequency() != 0 && wave.amplitude() != 0;
        case ENTITY_TRANSLATE -> true;
        case SCALE, TRANSFORM -> false;
      };
    }
  }
}
