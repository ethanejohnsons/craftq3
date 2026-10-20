package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.render.material.StageProgram;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Pipeline variants of the {@code q3_material} program. Blaze3D fixes blend, depth, cull and
 * polygon offset per {@link RenderPipeline}, so each distinct stage state is one variant. Variants
 * are built on first use and cached for the life of the client: a variant is never built twice, so
 * per-frame work only looks it up. {@link #precompile} compiles the variants built so far on the
 * device (the backend calls it at map load), so the first frame that uses one does not stall on
 * shader compilation. Every variant writes colour only (alpha stays 1 for the GUI and screenshots)
 * and uses reversed-Z depth ({@code GREATER_THAN_OR_EQUAL}, cleared to 0).
 */
public final class Q3Pipelines {
  private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
  public static final String VIEW = "Q3View";
  public static final String DRAW = "Q3Draw";
  public static final String STAGE = "Q3Stage";
  public static final String FOG = "Q3Fog";
  public static final String DIFFUSE = "Diffuse";
  public static final String LIGHTMAP = "Lightmap";

  private static final BindGroupLayout LAYOUT =
      BindGroupLayout.builder()
          .withUniform(VIEW, UniformType.UNIFORM_BUFFER)
          .withUniform(DRAW, UniformType.UNIFORM_BUFFER)
          .withUniform(STAGE, UniformType.UNIFORM_BUFFER)
          .withUniform(FOG, UniformType.UNIFORM_BUFFER)
          .withSampler(DIFFUSE)
          .withSampler(LIGHTMAP)
          .build();

  private static final Identifier SHADER =
      Identifier.fromNamespaceAndPath("craftq3", "core/q3_material");

  /** Q3's {@code r_offsetFactor}/{@code r_offsetUnits} (-1, -2), negated for reversed-Z depth. */
  private static final float OFFSET_FACTOR = 1, OFFSET_UNITS = 2;

  /** Depth comparison of a variant. */
  public enum Depth {
    LEQUAL,
    EQUAL,
    ALWAYS;

    static Depth of(StageProgram.DepthTest test) {
      return switch (test) {
        case LEQUAL -> LEQUAL;
        case EQUAL -> EQUAL;
        case ALWAYS -> ALWAYS;
      };
    }

    CompareOp op() {
      return switch (this) {
        // Reversed-Z: nearer fragments have greater depth.
        case LEQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
        case EQUAL -> CompareOp.EQUAL;
        case ALWAYS -> CompareOp.ALWAYS_PASS;
      };
    }
  }

  /**
   * One variant's fixed state. {@code cull} culls GL back faces; the backend picks the index
   * winding so that the faces a material keeps are GL front faces.
   */
  public record Key(
      ShaderDefinition.BlendFactor source,
      ShaderDefinition.BlendFactor destination,
      boolean depthWrite,
      Depth depth,
      boolean cull,
      boolean polygonOffset) {
    public boolean blends() {
      return !(source == ShaderDefinition.BlendFactor.ONE
          && destination == ShaderDefinition.BlendFactor.ZERO);
    }

    String id() {
      return (source.name()
              + "_"
              + destination.name()
              + "_"
              + depth.name()
              + (depthWrite ? "_w" : "")
              + (cull ? "_c" : "")
              + (polygonOffset ? "_o" : ""))
          .toLowerCase(Locale.ROOT);
    }
  }

  private final Map<Key, RenderPipeline> variants = new HashMap<>();
  private final java.util.Set<Key> compiled = new java.util.HashSet<>();

  /** The variant for {@code key}, built on first request. */
  public RenderPipeline get(Key key) {
    RenderPipeline pipeline = variants.get(key);
    if (pipeline == null) {
      pipeline = build(key);
      variants.put(key, pipeline);
    }
    return pipeline;
  }

  public int variantCount() {
    return variants.size();
  }

  /**
   * Compiles every variant not compiled yet on the current device; returns how many were. Must run
   * on the render thread, outside render passes.
   */
  public int precompile() {
    GpuDevice device = RenderSystem.getDevice();
    int count = 0;
    for (Map.Entry<Key, RenderPipeline> variant : variants.entrySet()) {
      if (!compiled.add(variant.getKey())) continue;
      if (!device.precompilePipeline(variant.getValue()).isValid())
        LOGGER.warn("CraftQ3: pipeline variant {} failed to compile", variant.getKey().id());
      count++;
    }
    return count;
  }

  /**
   * Builds the variants every map can need besides its own materials: the stage states cgame
   * entities, effects and the 2D overlay commonly use.
   */
  public void prepareCommon() {
    ShaderDefinition.BlendFactor one = ShaderDefinition.BlendFactor.ONE;
    ShaderDefinition.BlendFactor zero = ShaderDefinition.BlendFactor.ZERO;
    ShaderDefinition.BlendFactor srcAlpha = ShaderDefinition.BlendFactor.SRC_ALPHA;
    ShaderDefinition.BlendFactor invSrcAlpha = ShaderDefinition.BlendFactor.ONE_MINUS_SRC_ALPHA;
    ShaderDefinition.BlendFactor dstColor = ShaderDefinition.BlendFactor.DST_COLOR;
    ShaderDefinition.BlendFactor[][] blends = {
      {one, zero}, {one, one}, {srcAlpha, invSrcAlpha}, {dstColor, zero}, {one, invSrcAlpha}
    };
    for (ShaderDefinition.BlendFactor[] blend : blends)
      for (boolean cull : new boolean[] {true, false}) {
        boolean opaque = blend[0] == one && blend[1] == zero;
        get(new Key(blend[0], blend[1], opaque, Depth.LEQUAL, cull, false));
      }
    // 2D: alpha-blended without depth test; projected shadows: opaque with polygon offset.
    get(new Key(srcAlpha, invSrcAlpha, false, Depth.ALWAYS, false, false));
    get(new Key(one, zero, true, Depth.LEQUAL, true, true));
  }

  private static RenderPipeline build(Key key) {
    Optional<BlendFunction> blend =
        key.blends()
            ? Optional.of(new BlendFunction(factor(key.source()), factor(key.destination())))
            : Optional.empty();
    return RenderPipeline.builder()
        .withLocation(
            Identifier.fromNamespaceAndPath("craftq3", "pipeline/q3_material/" + key.id()))
        .withVertexShader(SHADER)
        .withFragmentShader(SHADER)
        .withVertexBinding(0, Q3RenderContext.STAGE_FORMAT)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withBindGroupLayout(LAYOUT)
        .withColorTargetState(
            new ColorTargetState(blend, GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
        .withDepthStencilState(
            key.polygonOffset()
                ? new DepthStencilState(
                    key.depth().op(), key.depthWrite(), OFFSET_FACTOR, OFFSET_UNITS)
                : new DepthStencilState(key.depth().op(), key.depthWrite()))
        .withCull(key.cull())
        .build();
  }

  private static BlendFactor factor(ShaderDefinition.BlendFactor factor) {
    return switch (factor) {
      case ZERO -> BlendFactor.ZERO;
      case ONE -> BlendFactor.ONE;
      case SRC_COLOR -> BlendFactor.SRC_COLOR;
      case ONE_MINUS_SRC_COLOR -> BlendFactor.ONE_MINUS_SRC_COLOR;
      case DST_COLOR -> BlendFactor.DST_COLOR;
      case ONE_MINUS_DST_COLOR -> BlendFactor.ONE_MINUS_DST_COLOR;
      case SRC_ALPHA -> BlendFactor.SRC_ALPHA;
      case ONE_MINUS_SRC_ALPHA -> BlendFactor.ONE_MINUS_SRC_ALPHA;
      case DST_ALPHA -> BlendFactor.DST_ALPHA;
      case ONE_MINUS_DST_ALPHA -> BlendFactor.ONE_MINUS_DST_ALPHA;
      case SRC_ALPHA_SATURATE -> BlendFactor.SRC_ALPHA_SATURATE;
    };
  }
}
