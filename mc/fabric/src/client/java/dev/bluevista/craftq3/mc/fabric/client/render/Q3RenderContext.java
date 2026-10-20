package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * The one Blaze3D context per client, shared by every CraftQ3 screen.
 *
 * <ul>
 *   <li>Pipelines are built and registered with {@link RenderPipelines#register} once, from the
 *       constructor, which runs at client initialisation before the first resource reload; the
 *       shader manager then precompiles them with the vanilla pipelines on both backends.
 *   <li>Samplers and textures are cached by key/name; static meshes are uploaded once and kept.
 *   <li>Per-draw stage uniforms and per-view uniforms live in {@link DynamicUniformStorage}s
 *       (triple-buffered, fenced, aligned to {@code minUniformOffsetAlignment}).
 *   <li>Dynamic vertices go into a triple-buffered {@link MappableRingBuffer} reused across frames.
 * </ul>
 *
 * <p>All GPU objects are created lazily on the render thread, because the device does not exist yet
 * when the client initialises. {@link #endFrame()} must be called once after each frame's CraftQ3
 * passes.
 */
public final class Q3RenderContext implements AutoCloseable {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Vertex layout of every Q3 stage draw: 44 bytes. */
  public static final VertexFormat STAGE_FORMAT =
      VertexFormat.builder(0)
          .addAttribute("Position", GpuFormat.RGB32_FLOAT)
          .addAttribute("UV0", GpuFormat.RG32_FLOAT)
          .addAttribute("LightmapUV", GpuFormat.RG32_FLOAT)
          .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
          .addAttribute("Color", GpuFormat.RGBA8_UNORM)
          .build();

  public static final String VIEW_UNIFORM = "Q3View";
  public static final String STAGE_UNIFORM = "Q3Stage";
  public static final String DIFFUSE_SAMPLER = "Diffuse";
  public static final String LIGHTMAP_SAMPLER = "Lightmap";

  private static final BindGroupLayout STAGE_LAYOUT =
      BindGroupLayout.builder()
          .withUniform(VIEW_UNIFORM, UniformType.UNIFORM_BUFFER)
          .withUniform(STAGE_UNIFORM, UniformType.UNIFORM_BUFFER)
          .withSampler(DIFFUSE_SAMPLER)
          .withSampler(LIGHTMAP_SAMPLER)
          .build();

  private static final int DYNAMIC_VERTEX_ALIGNMENT = 256;
  private static final int INITIAL_DYNAMIC_VERTEX_BYTES = 1 << 20;

  private final Map<Q3Blend, RenderPipeline> pipelines = new EnumMap<>(Q3Blend.class);
  private final Map<SamplerKey, GpuSampler> samplers = new HashMap<>();
  private final Map<String, Q3Texture> textures = new HashMap<>();
  private final Map<String, StaticMesh> staticMeshes = new HashMap<>();
  private final List<MappableRingBuffer> retiredVertexRings = new ArrayList<>();
  private DynamicUniformStorage<Q3StageParams> stageUniforms;
  private DynamicUniformStorage<Q3ViewParams> viewUniforms;
  private MappableRingBuffer vertexRing;
  private int vertexCursor;
  private int staticUploads;
  private long frames;
  private boolean closed;

  /** Builds and registers every pipeline variant. Call once, from client initialisation. */
  public Q3RenderContext() {
    for (Q3Blend blend : Q3Blend.values())
      pipelines.put(
          blend,
          RenderPipelines.register(
              RenderPipeline.builder()
                  .withLocation(
                      Identifier.fromNamespaceAndPath("craftq3", "pipeline/q3_stage_" + blend.id()))
                  .withVertexShader(Identifier.fromNamespaceAndPath("craftq3", "core/q3_stage"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("craftq3", "core/q3_stage"))
                  .withVertexBinding(0, STAGE_FORMAT)
                  .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                  .withBindGroupLayout(STAGE_LAYOUT)
                  .withColorTargetState(blend.colorTarget())
                  .withDepthStencilState(Optional.empty())
                  .withCull(false)
                  .build()));
  }

  public RenderPipeline pipeline(Q3Blend blend) {
    return pipelines.get(blend);
  }

  public GpuSampler sampler(AddressMode address, FilterMode filter) {
    return samplers.computeIfAbsent(
        new SamplerKey(address, filter),
        key ->
            RenderSystem.getDevice()
                .createSampler(address, address, filter, filter, 1, OptionalDouble.of(0)));
  }

  /**
   * Returns the cached texture {@code name}, creating it from tightly packed RGBA8 pixels on first
   * use. {@code pixels} is only called on a cache miss.
   */
  public GpuTextureView texture(String name, int width, int height, Supplier<byte[]> pixels) {
    return textures
        .computeIfAbsent(name, n -> uploadTexture(n, width, height, pixels.get()))
        .view();
  }

  /** Returns the static mesh {@code name}, uploading its vertices on first use only. */
  public StaticMesh staticMesh(String name, Supplier<ByteBuffer> vertices) {
    return staticMeshes.computeIfAbsent(
        name,
        n -> {
          ByteBuffer data = vertices.get();
          int count = data.remaining() / STAGE_FORMAT.getVertexSize();
          GpuBuffer buffer =
              RenderSystem.getDevice()
                  .createBuffer(() -> "CraftQ3 static " + n, GpuBuffer.USAGE_VERTEX, data);
          staticUploads++;
          return new StaticMesh(buffer, count);
        });
  }

  /**
   * Copies this frame's dynamic vertices into the ring buffer and returns the slice to bind. Must
   * be called before the render pass that draws them is opened.
   */
  public GpuBufferSlice writeDynamicVertices(ByteBuffer data) {
    int length = data.remaining();
    if (vertexRing == null || vertexCursor + length > vertexRing.size()) growVertexRing(length);
    GpuBufferSlice slice = vertexRing.currentBuffer().slice(vertexCursor, length);
    try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
      view.data().put(data);
    }
    vertexCursor += align(length, DYNAMIC_VERTEX_ALIGNMENT);
    return slice;
  }

  public GpuBufferSlice stageUniform(Q3StageParams params) {
    if (stageUniforms == null)
      stageUniforms = new DynamicUniformStorage<>("CraftQ3 stages", Q3StageParams.UBO_SIZE, 64);
    return stageUniforms.writeUniform(params);
  }

  public GpuBufferSlice viewUniform(Q3ViewParams params) {
    if (viewUniforms == null)
      viewUniforms = new DynamicUniformStorage<>("CraftQ3 views", Q3ViewParams.UBO_SIZE, 4);
    return viewUniforms.writeUniform(params);
  }

  /** Rotates the ring buffers. Call once per frame after the frame's CraftQ3 passes. */
  public void endFrame() {
    if (stageUniforms != null) stageUniforms.endFrame();
    if (viewUniforms != null) viewUniforms.endFrame();
    if (vertexRing != null) vertexRing.rotate();
    vertexCursor = 0;
    retiredVertexRings.forEach(MappableRingBuffer::close);
    retiredVertexRings.clear();
    frames++;
  }

  /** Number of static meshes uploaded so far; each is uploaded exactly once per context. */
  public int staticUploads() {
    return staticUploads;
  }

  public long frames() {
    return frames;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    staticMeshes.values().forEach(mesh -> mesh.buffer().close());
    staticMeshes.clear();
    textures.values().forEach(Q3Texture::close);
    textures.clear();
    samplers.values().forEach(GpuSampler::close);
    samplers.clear();
    retiredVertexRings.forEach(MappableRingBuffer::close);
    retiredVertexRings.clear();
    if (vertexRing != null) vertexRing.close();
    if (stageUniforms != null) stageUniforms.close();
    if (viewUniforms != null) viewUniforms.close();
    vertexRing = null;
    stageUniforms = null;
    viewUniforms = null;
  }

  private void growVertexRing(int needed) {
    int size = vertexRing == null ? INITIAL_DYNAMIC_VERTEX_BYTES : vertexRing.size();
    while (size < vertexCursor + needed) size *= 2;
    if (vertexRing != null) {
      // Draws recorded earlier this frame still reference the old ring; close it at endFrame.
      LOGGER.info("Growing CraftQ3 dynamic vertex ring to {} bytes", size);
      retiredVertexRings.add(vertexRing);
    }
    vertexRing =
        new MappableRingBuffer(
            () -> "CraftQ3 dynamic vertices",
            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
            size);
    vertexCursor = 0;
  }

  private static Q3Texture uploadTexture(String name, int width, int height, byte[] rgba) {
    if (rgba.length != width * height * 4)
      throw new IllegalArgumentException(
          "Texture " + name + " needs " + width * height * 4 + " RGBA bytes, got " + rgba.length);
    var device = RenderSystem.getDevice();
    GpuTexture texture =
        device.createTexture(
            "CraftQ3 " + name,
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM,
            width,
            height,
            1,
            1);
    ByteBuffer pixels = MemoryUtil.memAlloc(rgba.length);
    try {
      pixels.put(rgba).flip();
      device.createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, width, height);
      return new Q3Texture(texture, device.createTextureView(texture));
    } catch (RuntimeException e) {
      texture.close();
      throw e;
    } finally {
      MemoryUtil.memFree(pixels);
    }
  }

  private static int align(int value, int alignment) {
    return (value + alignment - 1) / alignment * alignment;
  }

  /** A vertex buffer in {@link #STAGE_FORMAT} that never changes after upload. */
  public record StaticMesh(GpuBuffer buffer, int vertexCount) {}

  private record SamplerKey(AddressMode address, FilterMode filter) {}

  private record Q3Texture(GpuTexture texture, GpuTextureView view) {
    void close() {
      view.close();
      texture.close();
    }
  }
}
