package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.render.DrawBatch;
import dev.bluevista.craftq3.engine.render.FogVolumes;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.SceneTextures;
import dev.bluevista.craftq3.engine.render.SkyGeometry;
import dev.bluevista.craftq3.engine.render.WorldGeometry;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import dev.bluevista.craftq3.engine.render.material.ShadingInputs;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * The GPU copy of one map, built once at load: the static {@link WorldGeometry} vertex buffer and
 * two index buffers (Q3 winding reversed for GL front faces, and as stored for back-sided materials
 * and mirrored views), one texture per 128×128 lightmap page, the packed fog volumes, static
 * per-fog draw blocks, the view-centred sky meshes and the map's material table. Nothing here
 * changes per frame.
 */
final class Q3WorldRenderer implements AutoCloseable {
  /** Bytes per vertex of {@link Q3RenderContext#STAGE_FORMAT}. */
  static final int VERTEX_BYTES = 44;

  private static final float SKY_DISTANCE = 1024;

  private final RenderScene scene;
  private final Q3MaterialTable materials;
  private final GpuBuffer vertices;
  private final GpuBuffer frontIndices;
  private final GpuBuffer backIndices;
  private final List<GpuTexture> lightmapTextures = new ArrayList<>();
  private final List<GpuTextureView> lightmaps = new ArrayList<>();
  private final GpuBuffer staticBlocks;
  private final GpuBufferSlice fogBlock;
  private final GpuBufferSlice[] worldDraws;
  private final int fogVolumes;
  private final float[] batchBounds;
  private final Map<Float, SkyMesh> skyMeshes = new HashMap<>();
  private SkyMesh skyBox;

  /** A static sky mesh: box faces (6 × 6 indices) or cloud dome. */
  record SkyMesh(GpuBuffer vertices, GpuBuffer indices, int indexCount) {
    void close() {
      vertices.close();
      indices.close();
    }
  }

  Q3WorldRenderer(
      RenderScene scene,
      MaterialRegistry registry,
      SceneTextures textures,
      Q3MaterialTable.Images images,
      Q3RenderContext context,
      CommandEncoder encoder) {
    this.scene = scene;
    GpuDevice device = RenderSystem.getDevice();
    WorldGeometry world = scene.world();
    String name = scene.name();
    vertices =
        upload(
            device,
            "CraftQ3 world vertices " + name,
            GpuBuffer.USAGE_VERTEX,
            vertexData(world.vertexArrays(), world.vertexCount()));
    frontIndices =
        upload(
            device,
            "CraftQ3 world indices " + name,
            GpuBuffer.USAGE_INDEX,
            indexData(world.indices(), true));
    backIndices =
        upload(
            device,
            "CraftQ3 world back indices " + name,
            GpuBuffer.USAGE_INDEX,
            indexData(world.indices(), false));
    for (int page = 0; page < textures.lightmaps().size(); page++)
      uploadLightmap(device, encoder, page, textures.lightmaps().get(page));

    FogVolumes fog = scene.fog();
    fogVolumes = Math.min(Q3Blocks.MAX_FOG_VOLUMES, fog.count());
    int alignment = Math.max(16, device.getDeviceInfo().limits().minUniformOffsetAlignment());
    int fogBytes = align(Q3Blocks.FOG_BYTES, alignment);
    int drawBytes = align(Q3Blocks.DRAW_BYTES, alignment);
    int drawCount = fogVolumes + 1;
    ByteBuffer data =
        MemoryUtil.memCalloc(fogBytes + drawBytes * drawCount).order(ByteOrder.nativeOrder());
    try {
      Q3Blocks.fog(data, 0, fog);
      for (int i = 0; i < drawCount; i++)
        Q3Blocks.draw(
            data,
            fogBytes + i * drawBytes,
            null,
            -1,
            ShadingInputs.DEFAULT_AMBIENT,
            ShadingInputs.DEFAULT_DIRECTED,
            ShadingInputs.UP,
            0,
            i - 1,
            0,
            0,
            0);
      staticBlocks =
          device.createBuffer(() -> "CraftQ3 world blocks " + name, GpuBuffer.USAGE_UNIFORM, data);
    } finally {
      MemoryUtil.memFree(data);
    }
    fogBlock = staticBlocks.slice(0, fogBytes);
    worldDraws = new GpuBufferSlice[drawCount];
    for (int i = 0; i < drawCount; i++)
      worldDraws[i] = staticBlocks.slice(fogBytes + (long) i * drawBytes, drawBytes);

    List<DrawBatch> batches = world.batches();
    batchBounds = new float[batches.size() * 6];
    for (DrawBatch batch : batches) {
      int at = batch.index() * 6;
      batchBounds[at] = batchBounds[at + 1] = batchBounds[at + 2] = Float.POSITIVE_INFINITY;
      batchBounds[at + 3] = batchBounds[at + 4] = batchBounds[at + 5] = Float.NEGATIVE_INFINITY;
      for (int k = batch.firstSurface(); k < batch.firstSurface() + batch.surfaceCount(); k++) {
        int surface = world.batchSurface(k);
        for (int c = 0; c < 3; c++) {
          batchBounds[at + c] = Math.min(batchBounds[at + c], world.surfaceBound(surface, c));
          batchBounds[at + 3 + c] =
              Math.max(batchBounds[at + 3 + c], world.surfaceBound(surface, c + 3));
        }
      }
    }
    materials = new Q3MaterialTable(registry, context, images);
    materials.buildAll(encoder);
    materials.flush(encoder);
  }

  RenderScene scene() {
    return scene;
  }

  Q3MaterialTable materials() {
    return materials;
  }

  GpuBuffer vertices() {
    return vertices;
  }

  /** Indices wound so that {@code frontFaces} (Q3 front sides) are GL front faces. */
  GpuBuffer indices(boolean frontFaces) {
    return frontFaces ? frontIndices : backIndices;
  }

  /** Lightmap page {@code page}, or null for none and vertex lighting. */
  GpuTextureView lightmap(int page) {
    return page >= 0 && page < lightmaps.size() ? lightmaps.get(page) : null;
  }

  GpuBufferSlice fogBlock() {
    return fogBlock;
  }

  /** Volumes of {@link FogVolumes} the fog block holds (the first ones). */
  int packedFogVolumes() {
    return fogVolumes;
  }

  /** The packed volume index of BSP effect {@code effect}, or -1. */
  int fogVolume(int effect) {
    int volume = scene.fog().volumeForEffect(effect);
    return volume < fogVolumes ? volume : -1;
  }

  /** The identity draw block of world surfaces in packed fog volume {@code volume} (-1 none). */
  GpuBufferSlice worldDraw(int volume) {
    return worldDraws[volume + 1];
  }

  /** Bounds (min xyz, max xyz) of batch {@code batch}. */
  boolean batchTouches(int batch, float x, float y, float z, float radius) {
    int at = batch * 6;
    return x + radius >= batchBounds[at]
        && y + radius >= batchBounds[at + 1]
        && z + radius >= batchBounds[at + 2]
        && x - radius <= batchBounds[at + 3]
        && y - radius <= batchBounds[at + 4]
        && z - radius <= batchBounds[at + 5];
  }

  /** The sky box around the origin; faces in {@link SkyGeometry#SIDES} order. */
  SkyMesh skyBox() {
    if (skyBox == null) {
      MeshBuffer mesh = new MeshBuffer(64, 64);
      SkyGeometry.box(Vec3f.ZERO, SKY_DISTANCE, mesh);
      skyBox = upload(mesh, "CraftQ3 sky box");
    }
    return skyBox;
  }

  /** The cloud dome of {@code cloudHeight} around the origin. */
  SkyMesh clouds(float cloudHeight) {
    SkyMesh mesh = skyMeshes.get(cloudHeight);
    if (mesh == null) {
      MeshBuffer buffer = new MeshBuffer(4096, 8192);
      SkyGeometry.clouds(Vec3f.ZERO, SKY_DISTANCE, cloudHeight > 0 ? cloudHeight : 512, buffer);
      mesh = upload(buffer, "CraftQ3 clouds " + cloudHeight);
      skyMeshes.put(cloudHeight, mesh);
    }
    return mesh;
  }

  /** GPU objects this map owns (buffers and lightmap textures). */
  int gpuObjects() {
    return 4
        + lightmaps.size()
        + skyMeshes.size() * 2
        + (skyBox == null ? 0 : 2)
        + materials.gpuBuffers();
  }

  @Override
  public void close() {
    vertices.close();
    frontIndices.close();
    backIndices.close();
    staticBlocks.close();
    lightmaps.forEach(GpuTextureView::close);
    lightmapTextures.forEach(GpuTexture::close);
    lightmaps.clear();
    lightmapTextures.clear();
    skyMeshes.values().forEach(SkyMesh::close);
    skyMeshes.clear();
    if (skyBox != null) skyBox.close();
    skyBox = null;
    materials.close();
  }

  private void uploadLightmap(GpuDevice device, CommandEncoder encoder, int page, Q3Image image) {
    GpuTexture texture =
        device.createTexture(
            "CraftQ3 lightmap " + page,
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM,
            image.width(),
            image.height(),
            1,
            1);
    Q3TextureCache.writeLevel(encoder, texture, 0, image);
    lightmapTextures.add(texture);
    lightmaps.add(device.createTextureView(texture));
  }

  private static SkyMesh upload(MeshBuffer mesh, String label) {
    GpuDevice device = RenderSystem.getDevice();
    GpuBuffer v =
        upload(
            device,
            label + " vertices",
            GpuBuffer.USAGE_VERTEX,
            vertexData(mesh.arrays(), mesh.vertexCount()));
    int[] indices = java.util.Arrays.copyOf(mesh.indices(), mesh.indexCount());
    GpuBuffer i =
        upload(device, label + " indices", GpuBuffer.USAGE_INDEX, indexData(indices, false));
    return new SkyMesh(v, i, mesh.indexCount());
  }

  private static GpuBuffer upload(GpuDevice device, String label, int usage, ByteBuffer data) {
    try {
      return device.createBuffer(() -> label, usage, data);
    } finally {
      MemoryUtil.memFree(data);
    }
  }

  /** Interleaves {@code count} vertices into {@link Q3RenderContext#STAGE_FORMAT}. */
  static ByteBuffer vertexData(VertexArrays arrays, int count) {
    ByteBuffer out =
        MemoryUtil.memAlloc(Math.max(1, count) * VERTEX_BYTES).order(ByteOrder.nativeOrder());
    putVertices(out, arrays, 0, count);
    if (count == 0) out.put(new byte[VERTEX_BYTES]);
    return out.flip();
  }

  static void putVertices(ByteBuffer out, VertexArrays a, int first, int count) {
    float[] p = a.positions(), n = a.normals(), uv = a.texCoords(), lm = a.lightmapCoords();
    byte[] c = a.colors();
    for (int v = first; v < first + count; v++) {
      out.putFloat(p[v * 3]).putFloat(p[v * 3 + 1]).putFloat(p[v * 3 + 2]);
      out.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]);
      out.putFloat(lm[v * 2]).putFloat(lm[v * 2 + 1]);
      out.putFloat(n[v * 3]).putFloat(n[v * 3 + 1]).putFloat(n[v * 3 + 2]);
      out.put(c[v * 4]).put(c[v * 4 + 1]).put(c[v * 4 + 2]).put(c[v * 4 + 3]);
    }
  }

  /**
   * 32-bit indices; {@code reverse} swaps each triangle's last two corners, turning Q3's clockwise
   * front faces into GL's counter-clockwise ones.
   */
  static ByteBuffer indexData(int[] indices, boolean reverse) {
    ByteBuffer out =
        MemoryUtil.memAlloc(Math.max(3, indices.length) * 4).order(ByteOrder.nativeOrder());
    for (int i = 0; i + 2 < indices.length; i += 3) {
      out.putInt(indices[i]);
      out.putInt(reverse ? indices[i + 2] : indices[i + 1]);
      out.putInt(reverse ? indices[i + 1] : indices[i + 2]);
    }
    if (indices.length < 3) out.putInt(0).putInt(0).putInt(0);
    return out.flip();
  }

  private static int align(int value, int alignment) {
    return (value + alignment - 1) / alignment * alignment;
  }
}
