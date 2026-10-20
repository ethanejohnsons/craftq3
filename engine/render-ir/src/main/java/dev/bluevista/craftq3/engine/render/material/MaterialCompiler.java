package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AutoSprite;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Blend;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.BlendFactor;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.BulgeDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Deform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.EntityTranslate;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.MoveDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.NormalDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.ProjectionShadow;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Rotate;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Scale;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Scroll;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Stage;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Stretch;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.TcMod;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.TextDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Transform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Turbulence;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Wave;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveFunction;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaGen;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaTest;
import dev.bluevista.craftq3.engine.render.material.StageProgram.DepthTest;
import dev.bluevista.craftq3.engine.render.material.StageProgram.FogMode;
import dev.bluevista.craftq3.engine.render.material.StageProgram.RenderState;
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
 * Compiles parsed {@link ShaderDefinition}s into {@link MaterialProgram}s. Compilation is pure: the
 * same definition always gives an equal program. Features beyond the {@link ProgramLayout} limits
 * are dropped and listed in {@link MaterialProgram#unexpressed()}.
 */
public final class MaterialCompiler {
  private static final Wave FLAT = Wave.ONE;

  private MaterialCompiler() {}

  public static MaterialProgram compile(ShaderDefinition shader) {
    List<String> unexpressed = new ArrayList<>();
    List<Stage> sourceStages = shader.stages();
    if (sourceStages.size() > ProgramLayout.MAX_STAGES) {
      unexpressed.add(
          "stages beyond " + ProgramLayout.MAX_STAGES + " (" + sourceStages.size() + ")");
      sourceStages = sourceStages.subList(0, ProgramLayout.MAX_STAGES);
    }
    boolean opaque = !sourceStages.isEmpty() && sourceStages.getFirst().blend().opaque();
    List<StageProgram> stages = new ArrayList<>(sourceStages.size());
    for (Stage stage : sourceStages) stages.add(stage(shader, stage, opaque, unexpressed));

    List<DeformProgram> deforms = new ArrayList<>();
    for (Deform deform : shader.deforms()) {
      if (deforms.size() == ProgramLayout.MAX_DEFORMS) {
        unexpressed.add("deforms beyond " + ProgramLayout.MAX_DEFORMS);
        break;
      }
      deforms.add(deform(deform));
    }

    boolean sky = shader.skySurface();
    boolean noDraw = shader.noDraw();
    boolean fogVolume = shader.fog().isPresent();
    boolean fogPass = opaque && !sky && !noDraw && !fogVolume;
    boolean dynamicLightPass =
        opaque && !sky && !noDraw && !shader.surfaceParms().contains("nodlight");
    return new MaterialProgram(
        shader.name(),
        shader.sort(),
        switch (shader.cull()) {
          case FRONT -> MaterialProgram.CullMode.FRONT_SIDED;
          case BACK -> MaterialProgram.CullMode.BACK_SIDED;
          case NONE -> MaterialProgram.CullMode.TWO_SIDED;
        },
        shader.polygonOffset(),
        stages,
        deforms,
        shader
            .sky()
            .map(s -> new MaterialProgram.SkyParameters(s.farBox(), s.cloudHeight(), s.nearBox())),
        shader.fog().map(f -> new MaterialProgram.FogParameters(f.color(), f.depthForOpaque())),
        shader.portal(),
        noDraw,
        shader.clampTime(),
        fogPass,
        dynamicLightPass,
        unexpressed);
  }

  private static StageProgram stage(
      ShaderDefinition shader, Stage stage, boolean opaqueMaterial, List<String> unexpressed) {
    TextureBundle texture = texture(shader, stage);
    Blend blend = stage.blend();
    RenderState state =
        new RenderState(
            blend.source(),
            blend.destination(),
            stage.depthWrite(),
            switch (stage.depthFunc()) {
              case LEQUAL -> DepthTest.LEQUAL;
              case EQUAL -> DepthTest.EQUAL;
              case DISABLE -> DepthTest.ALWAYS;
            },
            switch (stage.alphaFunc()) {
              case NONE -> AlphaTest.NONE;
              case GT0 -> AlphaTest.GT0;
              case LT128 -> AlphaTest.LT128;
              case GE128 -> AlphaTest.GE128;
            },
            opaqueMaterial ? FogMode.NONE : fogMode(blend),
            stage.detail());
    List<TexMod> mods = new ArrayList<>(stage.tcMods().size());
    for (TcMod mod : stage.tcMods()) {
      if (mods.size() == ProgramLayout.MAX_TEX_MODS) {
        unexpressed.add("tcMods beyond " + ProgramLayout.MAX_TEX_MODS);
        break;
      }
      mods.add(texMod(mod));
    }
    return new StageProgram(
        texture,
        state,
        new Shading(rgbGen(stage.rgbGen()), alphaGen(stage.alphaGen()), texGen(stage), mods));
  }

  private static TextureBundle texture(ShaderDefinition shader, Stage stage) {
    var map = stage.texture();
    boolean mipmaps = !shader.noMipmaps();
    boolean picmip = !shader.noPicmip();
    if (map.frames().size() > 1)
      return new TextureBundle(
          TextureBundle.Kind.ANIMATED, map.frames(), map.fps(), map.clamp(), mipmaps, picmip);
    String image = map.frames().getFirst();
    TextureBundle.Kind kind;
    if (image.equals(TextureBundle.LIGHTMAP)) kind = TextureBundle.Kind.LIGHTMAP;
    else if (image.equals(TextureBundle.WHITE) || image.equals("*white"))
      kind = TextureBundle.Kind.WHITE;
    else if (image.startsWith("$")) kind = TextureBundle.Kind.BUILTIN;
    else kind = TextureBundle.Kind.IMAGE;
    if (kind == TextureBundle.Kind.WHITE) image = TextureBundle.WHITE;
    return new TextureBundle(kind, List.of(image), 0, map.clamp(), mipmaps, picmip);
  }

  /** Legacy-compatible fog adjustment chosen from the stage blend function. */
  static FogMode fogMode(Blend blend) {
    BlendFactor src = blend.source(), dst = blend.destination();
    if (src == BlendFactor.ONE && dst == BlendFactor.ONE) return FogMode.MODULATE_RGB;
    if (src == BlendFactor.ZERO && dst == BlendFactor.ONE_MINUS_SRC_COLOR)
      return FogMode.MODULATE_RGB;
    if (src == BlendFactor.SRC_ALPHA && dst == BlendFactor.ONE_MINUS_SRC_ALPHA)
      return FogMode.MODULATE_ALPHA;
    if (src == BlendFactor.ONE && dst == BlendFactor.ONE_MINUS_SRC_ALPHA)
      return FogMode.MODULATE_RGBA;
    if (src == BlendFactor.DST_COLOR && dst == BlendFactor.ZERO) return FogMode.BLEND_TO_WHITE;
    if (src == BlendFactor.ZERO && dst == BlendFactor.SRC_COLOR) return FogMode.BLEND_TO_WHITE;
    return FogMode.NONE;
  }

  private static RgbGen rgbGen(ShaderDefinition.RgbGen gen) {
    RgbOp op =
        switch (gen.type()) {
          case IDENTITY -> RgbOp.IDENTITY;
          case IDENTITY_LIGHTING -> RgbOp.IDENTITY_LIGHTING;
          case VERTEX -> RgbOp.VERTEX;
          case EXACT_VERTEX -> RgbOp.EXACT_VERTEX;
          case ONE_MINUS_VERTEX -> RgbOp.ONE_MINUS_VERTEX;
          case ENTITY -> RgbOp.ENTITY;
          case ONE_MINUS_ENTITY -> RgbOp.ONE_MINUS_ENTITY;
          case LIGHTING_DIFFUSE -> RgbOp.LIGHTING_DIFFUSE;
          case WAVE -> RgbOp.WAVE;
          case CONSTANT -> RgbOp.CONSTANT;
          case FOG -> RgbOp.FOG;
        };
    return new RgbGen(op, gen.wave(), gen.constant());
  }

  private static AlphaGen alphaGen(ShaderDefinition.AlphaGen gen) {
    AlphaOp op =
        switch (gen.type()) {
          case IDENTITY -> AlphaOp.IDENTITY;
          case VERTEX -> AlphaOp.VERTEX;
          case ONE_MINUS_VERTEX -> AlphaOp.ONE_MINUS_VERTEX;
          case ENTITY -> AlphaOp.ENTITY;
          case ONE_MINUS_ENTITY -> AlphaOp.ONE_MINUS_ENTITY;
          case LIGHTING_SPECULAR -> AlphaOp.LIGHTING_SPECULAR;
          case WAVE -> AlphaOp.WAVE;
          case CONSTANT -> AlphaOp.CONSTANT;
          case PORTAL -> AlphaOp.PORTAL;
        };
    return new AlphaGen(op, gen.wave(), gen.constant(), gen.portalRange());
  }

  private static TexGen texGen(Stage stage) {
    var gen = stage.tcGen();
    TexGenOp op =
        switch (gen.type()) {
          case BASE -> TexGenOp.BASE;
          case LIGHTMAP -> TexGenOp.LIGHTMAP;
          case ENVIRONMENT -> TexGenOp.ENVIRONMENT;
          case VECTOR -> TexGenOp.VECTOR;
          case IDENTITY -> TexGenOp.IDENTITY;
          case FOG -> TexGenOp.FOG;
        };
    return new TexGen(op, gen.s(), gen.t());
  }

  private static TexMod texMod(TcMod mod) {
    return switch (mod) {
      case Turbulence turb ->
          TexMod.waved(
              TexModOp.TURB,
              new Wave(
                  WaveFunction.SIN, turb.base(), turb.amplitude(), turb.phase(), turb.frequency()));
      case Scale scale -> TexMod.of(TexModOp.SCALE, scale.s(), scale.t());
      case Scroll scroll -> TexMod.of(TexModOp.SCROLL, scroll.s(), scroll.t());
      case Stretch stretch -> TexMod.waved(TexModOp.STRETCH, stretch.wave());
      case Transform t ->
          TexMod.of(TexModOp.TRANSFORM, t.m00(), t.m01(), t.m10(), t.m11(), t.t0(), t.t1());
      case Rotate rotate -> TexMod.of(TexModOp.ROTATE, rotate.degreesPerSecond());
      case EntityTranslate ignored -> TexMod.of(TexModOp.ENTITY_TRANSLATE);
    };
  }

  private static DeformProgram deform(Deform deform) {
    return switch (deform) {
      case WaveDeform wave ->
          new DeformProgram(DeformProgram.Op.WAVE, wave.wave(), wave.divisor(), 0, 0);
      case NormalDeform normal ->
          new DeformProgram(
              DeformProgram.Op.NORMAL, FLAT, normal.amplitude(), normal.frequency(), 0);
      case BulgeDeform bulge ->
          new DeformProgram(
              DeformProgram.Op.BULGE, FLAT, bulge.width(), bulge.height(), bulge.speed());
      case MoveDeform move ->
          new DeformProgram(
              DeformProgram.Op.MOVE,
              move.wave(),
              move.direction().x(),
              move.direction().y(),
              move.direction().z());
      case AutoSprite sprite ->
          new DeformProgram(
              sprite.axial() ? DeformProgram.Op.AUTOSPRITE2 : DeformProgram.Op.AUTOSPRITE,
              FLAT,
              0,
              0,
              0);
      case ProjectionShadow ignored ->
          new DeformProgram(DeformProgram.Op.PROJECTION_SHADOW, FLAT, 0, 0, 0);
      case TextDeform text -> new DeformProgram(DeformProgram.Op.TEXT, FLAT, text.index(), 0, 0);
    };
  }
}
