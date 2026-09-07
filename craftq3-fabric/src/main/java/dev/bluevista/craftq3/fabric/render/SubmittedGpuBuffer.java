package dev.bluevista.craftq3.fabric.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.render.SubmittedMaterials;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/** A separate streaming buffer for cgame entities and HUD, with no BSP/PVS identifiers. */
final class SubmittedGpuBuffer implements AutoCloseable {
  private static final int STRIDE = 28;

  record Draw(SubmittedMaterials.Batch batch, int first, int count) {}

  private GpuBuffer buffer;

  GpuBuffer buffer() {
    return buffer;
  }

  List<Draw> upload(List<SubmittedMaterials.Batch> batches) {
    long count = batches.stream().mapToLong(b -> b.vertices().size()).sum();
    long bytes = count * STRIDE;
    if (bytes > 256L * 1024 * 1024)
      throw new IllegalArgumentException("Cgame vertex buffer exceeds 256 MiB");
    if (count == 0) return List.of();
    ByteBuffer data = MemoryUtil.memAlloc((int) bytes);
    var result = new ArrayList<Draw>();
    int first = 0;
    try {
      for (var batch : batches) {
        result.add(new Draw(batch, first, batch.vertices().size()));
        for (int i = 0; i < batch.vertices().size(); i++) {
          var vertex = batch.vertices().get(i);
          var p = vertex.position();
          int rgba = vertex.rgba();
          data.putFloat((float) p.x()).putFloat((float) p.y()).putFloat((float) p.z());
          data.putFloat(vertex.textureUv().u()).putFloat(vertex.textureUv().v());
          data.put((byte) (rgba >>> 24))
              .put((byte) (rgba >>> 16))
              .put((byte) (rgba >>> 8))
              .put((byte) rgba);
          data.putFloat(batch.fogAmounts().get(i));
        }
        first += batch.vertices().size();
      }
      data.flip();
      if (buffer == null || buffer.size() < bytes) {
        if (buffer != null) buffer.close();
        buffer =
            RenderSystem.getDevice()
                .createBuffer(
                    () -> "CraftQ3 submitted geometry",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    bytes);
      }
      RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, bytes), data);
    } finally {
      MemoryUtil.memFree(data);
    }
    return List.copyOf(result);
  }

  @Override
  public void close() {
    if (buffer != null) buffer.close();
    buffer = null;
  }
}
