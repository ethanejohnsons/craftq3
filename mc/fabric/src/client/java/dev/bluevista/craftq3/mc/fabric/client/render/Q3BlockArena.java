package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * Static uniform blocks of one size (material stages), written once and bound by slice for the life
 * of a map. Blocks live in GPU buffers of {@code chunkBlocks} blocks each; new blocks are staged in
 * CPU memory and copied with one write per dirty chunk at {@link #flush}, which runs before any
 * render pass of the frame.
 */
final class Q3BlockArena implements AutoCloseable {
  private final String label;
  private final int blockSize;
  private final int chunkBlocks;
  private final List<Chunk> chunks = new ArrayList<>();
  private int blocks;

  private static final class Chunk {
    final GpuBuffer buffer;
    final ByteBuffer mirror;
    int dirtyFrom = Integer.MAX_VALUE, dirtyTo = -1;

    Chunk(GpuBuffer buffer, ByteBuffer mirror) {
      this.buffer = buffer;
      this.mirror = mirror;
    }
  }

  /** {@code firstChunkBlocks} sizes the first chunk (the map's own materials) exactly. */
  Q3BlockArena(String label, int bytes, int firstChunkBlocks, int chunkBlocks) {
    int alignment =
        Math.max(16, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
    this.label = label;
    this.blockSize = (bytes + alignment - 1) / alignment * alignment;
    this.chunkBlocks = chunkBlocks;
    if (firstChunkBlocks > 0) addChunk(firstChunkBlocks);
  }

  int blockSize() {
    return blockSize;
  }

  /** Allocates a block holding {@code floats}; returns the slice to bind. */
  GpuBufferSlice add(float[] floats) {
    int index = blocks;
    int capacity = 0;
    Chunk chunk = null;
    int local = index;
    for (Chunk c : chunks) {
      int size = (int) (c.buffer.size() / blockSize);
      if (local < size) {
        chunk = c;
        break;
      }
      local -= size;
      capacity += size;
    }
    if (chunk == null) {
      chunk = addChunk(chunkBlocks);
      local = index - capacity;
    }
    int at = local * blockSize;
    for (int i = 0; i < floats.length; i++) chunk.mirror.putFloat(at + i * 4, floats[i]);
    chunk.dirtyFrom = Math.min(chunk.dirtyFrom, at);
    chunk.dirtyTo = Math.max(chunk.dirtyTo, at + blockSize);
    blocks++;
    return chunk.buffer.slice(at, blockSize);
  }

  /** Uploads every block added since the last flush. */
  void flush(CommandEncoder encoder) {
    for (Chunk chunk : chunks) {
      if (chunk.dirtyTo < 0) continue;
      ByteBuffer data = chunk.mirror.duplicate().order(ByteOrder.nativeOrder());
      data.position(chunk.dirtyFrom).limit(chunk.dirtyTo);
      encoder.writeToBuffer(
          chunk.buffer.slice(chunk.dirtyFrom, chunk.dirtyTo - chunk.dirtyFrom), data);
      chunk.dirtyFrom = Integer.MAX_VALUE;
      chunk.dirtyTo = -1;
    }
  }

  int blocks() {
    return blocks;
  }

  int buffers() {
    return chunks.size();
  }

  @Override
  public void close() {
    for (Chunk chunk : chunks) {
      chunk.buffer.close();
      MemoryUtil.memFree(chunk.mirror);
    }
    chunks.clear();
    blocks = 0;
  }

  private Chunk addChunk(int count) {
    int bytes = Math.max(1, count) * blockSize;
    GpuBuffer buffer =
        RenderSystem.getDevice()
            .createBuffer(
                () -> label + " #" + chunks.size(),
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                bytes);
    ByteBuffer mirror = MemoryUtil.memCalloc(bytes).order(ByteOrder.nativeOrder());
    Chunk chunk = new Chunk(buffer, mirror);
    chunks.add(chunk);
    return chunk;
  }
}
