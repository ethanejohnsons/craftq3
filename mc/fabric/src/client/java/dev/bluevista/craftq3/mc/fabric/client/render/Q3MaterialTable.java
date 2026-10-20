package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.BlendFactor;
import dev.bluevista.craftq3.engine.render.SkyGeometry;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import dev.bluevista.craftq3.engine.render.material.StageProgram;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaTest;
import dev.bluevista.craftq3.engine.render.material.StageProgram.RgbOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexGenOp;
import dev.bluevista.craftq3.engine.render.material.TextureBundle;
import java.util.ArrayList;
import java.util.List;

/**
 * GPU-side state of every material of one {@link MaterialRegistry}: per stage a pipeline variant, a
 * static {@code Q3Stage} block and resolved textures, plus the synthetic fog, dynamic-light,
 * portal-backdrop and sky passes. Entries are built on first use, outside render passes, and never
 * change afterwards; per-frame work only picks the animation frame.
 *
 * <p>A {@code $lightmap} filter stage directly after (or before) an opaque texture stage is
 * collapsed into one pass that multiplies by the lightmap in the fragment shader, as Q3's
 * multitexture path does, halving the draws of ordinary lightmapped walls.
 */
final class Q3MaterialTable implements AutoCloseable {
  /** Supplies decoded images by name; returns the missing-texture checker when absent. */
  interface Images {
    Q3Image image(String name);
  }

  /** One drawable pass. */
  static final class Pass {
    final RenderPipeline pipeline;
    final GpuBufferSlice block;
    final GpuTextureView[] frames;
    final TextureBundle bundle;

    /** The stage samples the draw's lightmap page as its texture. */
    final boolean lightmapTexture;

    /** The collapsed pass multiplies by the draw's lightmap page. */
    final boolean multiplyLightmap;

    /** {@code $cinematic/N} stream, or -1. */
    final long cinematic;

    final GpuSampler sampler;
    final boolean depthWrite;

    Pass(
        RenderPipeline pipeline,
        GpuBufferSlice block,
        GpuTextureView[] frames,
        TextureBundle bundle,
        boolean lightmapTexture,
        boolean multiplyLightmap,
        long cinematic,
        GpuSampler sampler,
        boolean depthWrite) {
      this.pipeline = pipeline;
      this.block = block;
      this.frames = frames;
      this.bundle = bundle;
      this.lightmapTexture = lightmapTexture;
      this.multiplyLightmap = multiplyLightmap;
      this.cinematic = cinematic;
      this.sampler = sampler;
      this.depthWrite = depthWrite;
    }

    /** The texture shown at {@code seconds} (animMap frames follow shader time). */
    GpuTextureView frame(double seconds) {
      if (frames.length == 1 || bundle == null) return frames[0];
      return frames[bundle.frameAt(seconds)];
    }
  }

  /** The passes of one material. */
  static final class Material {
    final int index;
    final MaterialProgram program;
    final Pass[] passes;
    final Pass fog;
    final Pass dlight;
    final Pass backdrop;
    final Pass skyBox;
    final GpuTextureView[] skySides;
    final Pass[] clouds;

    /** Sort rank above opaque: drawn after the opaque world, in Q3 sort order. */
    final boolean translucent;

    Material(
        int index,
        MaterialProgram program,
        Pass[] passes,
        Pass fog,
        Pass dlight,
        Pass backdrop,
        Pass skyBox,
        GpuTextureView[] skySides,
        Pass[] clouds) {
      this.index = index;
      this.program = program;
      this.passes = passes;
      this.fog = fog;
      this.dlight = dlight;
      this.backdrop = backdrop;
      this.skyBox = skyBox;
      this.skySides = skySides;
      this.clouds = clouds;
      this.translucent = !program.opaque();
    }

    boolean draws() {
      return passes.length > 0 && !program.noDraw();
    }
  }

  private final MaterialRegistry registry;
  private final Q3RenderContext context;
  private final Images images;
  private final Q3BlockArena blocks;
  private final List<Material> materials = new ArrayList<>();

  Q3MaterialTable(MaterialRegistry registry, Q3RenderContext context, Images images) {
    this.registry = registry;
    this.context = context;
    this.images = images;
    int expected = 0;
    for (int i = 0; i < registry.size(); i++) expected += registry.program(i).stages().size() + 3;
    this.blocks = new Q3BlockArena("CraftQ3 material stages", Q3Blocks.STAGE_BYTES, expected, 256);
  }

  MaterialRegistry registry() {
    return registry;
  }

  /** The material at registry index {@code index}, built on first use (outside render passes). */
  Material get(CommandEncoder encoder, int index) {
    while (materials.size() <= index) materials.add(null);
    Material material = materials.get(index);
    if (material == null) {
      material = build(encoder, index, registry.program(index));
      materials.set(index, material);
    }
    return material;
  }

  /** Builds every material the registry holds now (the map's own materials at load). */
  void buildAll(CommandEncoder encoder) {
    for (int i = 0; i < registry.size(); i++) get(encoder, i);
  }

  /** Uploads blocks of materials built since the last flush. Call before the frame's passes. */
  void flush(CommandEncoder encoder) {
    blocks.flush(encoder);
  }

  int built() {
    int count = 0;
    for (Material material : materials) if (material != null) count++;
    return count;
  }

  int gpuBuffers() {
    return blocks.buffers();
  }

  @Override
  public void close() {
    blocks.close();
    materials.clear();
  }

  private Material build(CommandEncoder encoder, int index, MaterialProgram program) {
    boolean cull = program.cull() != MaterialProgram.CullMode.TWO_SIDED;
    boolean offset = program.polygonOffset();
    List<StageProgram> stages = program.stages();
    List<Pass> passes = new ArrayList<>(stages.size());
    if (!program.isSky()) {
      int first = 0;
      StageProgram merged = collapse(stages);
      if (merged != null) {
        passes.add(pass(encoder, program, merged, Q3Blocks.FLAG_LIGHTMAP, cull, offset, false));
        first = 2;
      }
      for (int i = first; i < stages.size(); i++)
        passes.add(pass(encoder, program, stages.get(i), 0, cull, offset, false));
    }
    GpuTextureView white = context.textures().white(encoder);
    Pass fog =
        program.fogPass()
            ? synthetic(
                program,
                Q3Blocks.FLAG_FOG_PASS,
                key(
                    BlendFactor.SRC_ALPHA,
                    BlendFactor.ONE_MINUS_SRC_ALPHA,
                    false,
                    Q3Pipelines.Depth.EQUAL,
                    cull,
                    offset),
                white)
            : null;
    Pass dlight =
        program.dynamicLightPass()
            ? synthetic(
                program,
                Q3Blocks.FLAG_DLIGHT,
                key(
                    BlendFactor.DST_COLOR,
                    BlendFactor.ONE,
                    false,
                    Q3Pipelines.Depth.EQUAL,
                    cull,
                    offset),
                white)
            : null;
    Pass backdrop =
        program.portal()
            ? synthetic(
                program,
                Q3Blocks.FLAG_SCREEN,
                key(
                    BlendFactor.ONE,
                    BlendFactor.ZERO,
                    true,
                    Q3Pipelines.Depth.LEQUAL,
                    cull,
                    offset),
                white)
            : null;
    Pass skyBox = null;
    GpuTextureView[] sides = null;
    Pass[] clouds = new Pass[0];
    if (program.isSky()) {
      MaterialProgram.SkyParameters sky = program.sky().orElseThrow();
      if (!sky.farBox().equals("-")) {
        sides = new GpuTextureView[SkyGeometry.SIDES.size()];
        for (int s = 0; s < sides.length; s++) {
          String name = sky.farBox() + "_" + SkyGeometry.SIDES.get(s);
          sides[s] = context.textures().get(encoder, name, () -> images.image(name));
        }
        skyBox =
            synthetic(
                program,
                0,
                key(
                    BlendFactor.ONE,
                    BlendFactor.ZERO,
                    false,
                    Q3Pipelines.Depth.LEQUAL,
                    false,
                    false),
                white);
      }
      List<Pass> layers = new ArrayList<>();
      for (StageProgram stage : stages)
        layers.add(pass(encoder, program, stage, 0, false, false, true));
      clouds = layers.toArray(Pass[]::new);
    }
    return new Material(
        index, program, passes.toArray(Pass[]::new), fog, dlight, backdrop, skyBox, sides, clouds);
  }

  private Pass pass(
      CommandEncoder encoder,
      MaterialProgram program,
      StageProgram stage,
      int flags,
      boolean cull,
      boolean offset,
      boolean sky) {
    StageProgram.RenderState state = stage.state();
    Q3Pipelines.Key key =
        sky
            ? key(
                state.sourceBlend(),
                state.destinationBlend(),
                false,
                Q3Pipelines.Depth.LEQUAL,
                false,
                false)
            : key(
                state.sourceBlend(),
                state.destinationBlend(),
                state.depthWrite(),
                Q3Pipelines.Depth.of(state.depthTest()),
                cull,
                offset);
    TextureBundle bundle = stage.texture();
    boolean lightmap = bundle.kind() == TextureBundle.Kind.LIGHTMAP;
    long cinematic = -1;
    GpuTextureView[] frames;
    if (lightmap) {
      frames = new GpuTextureView[] {context.textures().white(encoder)};
    } else {
      frames = new GpuTextureView[bundle.images().size()];
      for (int i = 0; i < frames.length; i++) {
        String name = bundle.images().get(i);
        frames[i] = resolve(encoder, bundle.kind(), name);
        if (name.startsWith("$cinematic/")) cinematic = parseStream(name);
      }
    }
    GpuSampler sampler =
        lightmap
            ? context.q3Sampler(true, false)
            : context.q3Sampler(
                bundle.clamp(), bundle.mipmaps() && !bundle.images().getFirst().startsWith("$"));
    return new Pass(
        context.pipelines().get(key),
        blocks.add(Q3Blocks.stage(program, stage, flags)),
        frames,
        bundle,
        lightmap,
        (flags & Q3Blocks.FLAG_LIGHTMAP) != 0,
        cinematic,
        sampler,
        key.depthWrite());
  }

  private Pass synthetic(
      MaterialProgram program, int flags, Q3Pipelines.Key key, GpuTextureView texture) {
    return new Pass(
        context.pipelines().get(key),
        blocks.add(Q3Blocks.stage(program, null, flags)),
        new GpuTextureView[] {texture},
        null,
        false,
        false,
        -1,
        context.q3Sampler(true, false),
        key.depthWrite());
  }

  private GpuTextureView resolve(CommandEncoder encoder, TextureBundle.Kind kind, String name) {
    Q3TextureCache textures = context.textures();
    return switch (kind) {
      case WHITE -> textures.white(encoder);
      case BUILTIN, LIGHTMAP ->
          name.equals(TextureBundle.WHITE)
              ? textures.white(encoder)
              : name.equals("$missing") ? textures.missing(encoder) : textures.black(encoder);
      // Cinematic streams on surfaces need the client's RoQ player; until then they show black.
      case VIDEO -> textures.black(encoder);
      case IMAGE, ANIMATED -> textures.get(encoder, name, () -> images.image(name));
    };
  }

  private static long parseStream(String name) {
    try {
      return Long.parseLong(name.substring("$cinematic/".length()));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static Q3Pipelines.Key key(
      BlendFactor source,
      BlendFactor destination,
      boolean depthWrite,
      Q3Pipelines.Depth depth,
      boolean cull,
      boolean offset) {
    return new Q3Pipelines.Key(source, destination, depthWrite, depth, cull, offset);
  }

  /**
   * The single pass replacing an opaque texture stage and a {@code $lightmap} filter stage (in
   * either order), or null when the first two stages do not form that pair.
   */
  static StageProgram collapse(List<StageProgram> stages) {
    if (stages.size() < 2) return null;
    StageProgram first = stages.get(0), second = stages.get(1);
    if (!first.state().opaque() || !filter(second.state())) return null;
    if (second.state().alphaTest() != AlphaTest.NONE) return null;
    StageProgram lightmap, texture;
    if (plainLightmap(first) && second.texture().kind() != TextureBundle.Kind.LIGHTMAP) {
      lightmap = first;
      texture = second;
    } else if (plainLightmap(second) && first.texture().kind() != TextureBundle.Kind.LIGHTMAP) {
      lightmap = second;
      texture = first;
    } else return null;
    if (lightmap == first && first.state().alphaTest() != AlphaTest.NONE) return null;
    return new StageProgram(texture.texture(), first.state(), texture.shading());
  }

  private static boolean filter(StageProgram.RenderState state) {
    return (state.sourceBlend() == BlendFactor.DST_COLOR
            && state.destinationBlend() == BlendFactor.ZERO)
        || (state.sourceBlend() == BlendFactor.ZERO
            && state.destinationBlend() == BlendFactor.SRC_COLOR);
  }

  private static boolean plainLightmap(StageProgram stage) {
    StageProgram.Shading s = stage.shading();
    return stage.texture().kind() == TextureBundle.Kind.LIGHTMAP
        && (s.rgbGen().op() == RgbOp.IDENTITY || s.rgbGen().op() == RgbOp.IDENTITY_LIGHTING)
        && s.alphaGen().op() == AlphaOp.IDENTITY
        && s.texGen().op() == TexGenOp.LIGHTMAP
        && s.texMods().isEmpty();
  }
}
