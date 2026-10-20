package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-frame dynamic GPU data: uniform blocks, vertices and indices are staged in CPU memory while a
 * frame is prepared, then copied into triple-buffered {@link MappableRingBuffer}s with one map per
 * buffer per frame. Staging memory and rings are reused across frames and only grow.
 */
final class Q3FrameBuffers implements AutoCloseable {
  private static final int INITIAL_UNIFORM_BYTES = 256 * 1024;
  private static final int INITIAL_VERTEX_BYTES = 4 * 1024 * 1024;
  private static final int INITIAL_INDEX_BYTES = 1024 * 1024;

  private final Ring uniforms =
      new Ring("CraftQ3 frame uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE);
  private final Ring vertices =
      new Ring("CraftQ3 frame vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE);
  private final Ring indices =
      new Ring("CraftQ3 frame indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE);
  private final int uniformAlignment;
  private ByteBuffer uniformStaging = allocate(INITIAL_UNIFORM_BYTES);
  private ByteBuffer vertexStaging = allocate(INITIAL_VERTEX_BYTES);
  private ByteBuffer indexStaging = allocate(INITIAL_INDEX_BYTES);
  private boolean uploaded;

  Q3FrameBuffers() {
    uniformAlignment =
        Math.max(16, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
  }

  int uniformAlignment() {
    return uniformAlignment;
  }

  /** Starts staging a new frame. */
  void begin() {
    uniformStaging.clear();
    vertexStaging.clear();
    indexStaging.clear();
    uploaded = false;
  }

  /**
   * Reserves an aligned uniform block of {@code bytes} and returns the staging buffer positioned at
   * it; {@link #lastUniformOffset()} is its offset.
   */
  ByteBuffer uniform(int bytes) {
    int offset = align(uniformStaging.position(), uniformAlignment);
    int end = offset + align(bytes, 16);
    uniformStaging = ensure(uniformStaging, end);
    // Zero the padding so blocks never carry stale data.
    for (int i = uniformStaging.position(); i < offset; i++) uniformStaging.put(i, (byte) 0);
    uniformStaging.limit(uniformStaging.capacity());
    uniformStaging.position(offset);
    lastUniform = offset;
    lastUniformEnd = end;
    return uniformStaging;
  }

  private int lastUniform;
  private int lastUniformEnd;

  int lastUniformOffset() {
    return lastUniform;
  }

  /** Closes the block opened by {@link #uniform}, padding it to its reserved size. */
  void endUniform() {
    while (uniformStaging.position() < lastUniformEnd) uniformStaging.put((byte) 0);
  }

  /** The vertex staging buffer with room for {@code bytes} more; vertices are appended. */
  ByteBuffer vertices(int bytes) {
    vertexStaging = ensure(vertexStaging, vertexStaging.position() + bytes);
    return vertexStaging;
  }

  int vertexBytes() {
    return vertexStaging.position();
  }

  /** The index staging buffer with room for {@code count} more 32-bit indices. */
  ByteBuffer indices(int count) {
    indexStaging = ensure(indexStaging, indexStaging.position() + count * 4);
    return indexStaging;
  }

  int indexCount() {
    return indexStaging.position() / 4;
  }

  /** Copies the staged data into this frame's ring buffers. Call once, before any render pass. */
  void upload() {
    if (uploaded) throw new IllegalStateException("Frame data already uploaded");
    uploads(uniforms, uniformStaging);
    uploads(vertices, vertexStaging);
    uploads(indices, indexStaging);
    uploaded = true;
  }

  GpuBufferSlice uniformSlice(int offset, int length) {
    return uniforms.current.slice(offset, length);
  }

  GpuBuffer vertexBuffer() {
    return vertices.current;
  }

  GpuBuffer indexBuffer() {
    return indices.current;
  }

  /** Rotates the rings. Call once after the frame's passes. */
  void end() {
    uniforms.end();
    vertices.end();
    indices.end();
  }

  /** Ring buffers currently allocated (each holding three GPU buffers). */
  int rings() {
    return uniforms.count() + vertices.count() + indices.count();
  }

  @Override
  public void close() {
    uniforms.close();
    vertices.close();
    indices.close();
    MemoryUtil.memFree(uniformStaging);
    MemoryUtil.memFree(vertexStaging);
    MemoryUtil.memFree(indexStaging);
    uniformStaging = vertexStaging = indexStaging = null;
  }

  private static void uploads(Ring ring, ByteBuffer staging) {
    int length = staging.position();
    ring.prepare(Math.max(length, 16));
    if (length == 0) return;
    ByteBuffer source = staging.duplicate().order(ByteOrder.nativeOrder());
    source.position(0).limit(length);
    try (GpuBufferSlice.MappedView view = ring.current.slice(0, length).map(false, true)) {
      view.data().put(source);
    }
  }

  private static ByteBuffer allocate(int bytes) {
    return MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
  }

  private static ByteBuffer ensure(ByteBuffer buffer, int needed) {
    if (needed <= buffer.capacity()) return buffer;
    int capacity = buffer.capacity();
    while (capacity < needed) capacity *= 2;
    int position = buffer.position();
    ByteBuffer grown = MemoryUtil.memRealloc(buffer, capacity).order(ByteOrder.nativeOrder());
    grown.limit(grown.capacity());
    grown.position(position);
    return grown;
  }

  private static int align(int value, int alignment) {
    return (value + alignment - 1) / alignment * alignment;
  }

  /** One triple-buffered ring that grows by replacement, retiring the old ring at frame end. */
  private static final class Ring implements AutoCloseable {
    private final String label;
    private final int usage;
    private final List<MappableRingBuffer> retired = new ArrayList<>();
    private MappableRingBuffer ring;
    private GpuBuffer current;

    Ring(String label, int usage) {
      this.label = label;
      this.usage = usage;
    }

    void prepare(int bytes) {
      if (ring == null || ring.size() < bytes) {
        int size = ring == null ? 64 * 1024 : ring.size();
        while (size < bytes) size *= 2;
        if (ring != null) retired.add(ring);
        int finalSize = size;
        ring = new MappableRingBuffer(() -> label + " " + finalSize, usage, size);
      }
      current = ring.currentBuffer();
    }

    void end() {
      if (ring != null) ring.rotate();
      retired.forEach(MappableRingBuffer::close);
      retired.clear();
    }

    int count() {
      return (ring == null ? 0 : 1) + retired.size();
    }

    @Override
    public void close() {
      retired.forEach(MappableRingBuffer::close);
      retired.clear();
      if (ring != null) ring.close();
      ring = null;
      current = null;
    }
  }
}
