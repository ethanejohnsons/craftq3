package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.bluevista.craftq3.engine.formats.image.ImageLoader;
import dev.bluevista.craftq3.engine.formats.image.Mipmaps;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryUtil;

/**
 * The client-wide Quake III texture cache, shared by every screen and map: each image is uploaded
 * once with its full mip chain (no picmip) and kept while a loaded map or a frame still uses it.
 * Clamping and mip selection are sampler state, so one upload serves every bundle that names the
 * image. Entries carry the generation that last used them; {@link #evictOlderThan} releases images
 * no map of the current generation references.
 */
public final class Q3TextureCache implements AutoCloseable {
  /** Engine images every map needs; never evicted. */
  public static final String WHITE = "$whiteimage";

  public static final String MISSING = "$missing";
  public static final String BLACK = "$black";

  private final Map<String, Entry> entries = new HashMap<>();
  private int generation;
  private long uploads;
  private long uploadedBytes;

  /** One resident image. */
  public static final class Entry {
    final GpuTexture texture;
    final GpuTextureView view;
    final boolean pinned;
    int generation;

    Entry(GpuTexture texture, GpuTextureView view, boolean pinned, int generation) {
      this.texture = texture;
      this.view = view;
      this.pinned = pinned;
      this.generation = generation;
    }

    public GpuTextureView view() {
      return view;
    }

    void close() {
      view.close();
      texture.close();
    }
  }

  /** Starts a new generation (a map load); returns its number. */
  public int nextGeneration() {
    return ++generation;
  }

  public int generation() {
    return generation;
  }

  /** The resident image {@code name}, or null. Touches it for the current generation. */
  public GpuTextureView find(String name) {
    Entry entry = entries.get(name);
    if (entry == null) return null;
    entry.generation = generation;
    return entry.view;
  }

  /** The image {@code name}, decoding it with {@code pixels} on a miss. */
  public GpuTextureView get(CommandEncoder encoder, String name, Supplier<Q3Image> pixels) {
    GpuTextureView found = find(name);
    if (found != null) return found;
    return put(encoder, name, Mipmaps.generate(pixels.get()), false);
  }

  /** Uploads a prepared mip chain as {@code name}, replacing any previous image. */
  public GpuTextureView put(
      CommandEncoder encoder, String name, List<Q3Image> mips, boolean pinned) {
    Entry previous = entries.remove(name);
    if (previous != null) previous.close();
    Entry entry = upload(encoder, name, mips, pinned, generation);
    entries.put(name, entry);
    return entry.view;
  }

  /** The pinned engine images; uploaded on first use. */
  public GpuTextureView white(CommandEncoder encoder) {
    return builtin(encoder, WHITE, ImageLoader.whiteTexture());
  }

  public GpuTextureView black(CommandEncoder encoder) {
    return builtin(encoder, BLACK, new Q3Image(1, 1, new byte[] {0, 0, 0, -1}));
  }

  public GpuTextureView missing(CommandEncoder encoder) {
    return builtin(encoder, MISSING, ImageLoader.missingTexture());
  }

  private GpuTextureView builtin(CommandEncoder encoder, String name, Q3Image image) {
    GpuTextureView found = find(name);
    return found != null ? found : put(encoder, name, Mipmaps.generate(image), true);
  }

  /** Releases unpinned images last used before {@code oldest}; returns how many were released. */
  public int evictOlderThan(int oldest) {
    int released = 0;
    for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
      Entry entry = it.next();
      if (entry.pinned || entry.generation >= oldest) continue;
      entry.close();
      it.remove();
      released++;
    }
    return released;
  }

  public int size() {
    return entries.size();
  }

  public long uploads() {
    return uploads;
  }

  public long uploadedBytes() {
    return uploadedBytes;
  }

  @Override
  public void close() {
    entries.values().forEach(Entry::close);
    entries.clear();
  }

  private Entry upload(
      CommandEncoder encoder, String name, List<Q3Image> mips, boolean pinned, int generation) {
    GpuDevice device = RenderSystem.getDevice();
    Q3Image base = mips.getFirst();
    // Blaze3D sizes level n as (size >> n) without a floor of 1: stop before either side hits 0.
    int levels =
        Math.min(
            mips.size(), 32 - Integer.numberOfLeadingZeros(Math.min(base.width(), base.height())));
    mips = mips.subList(0, Math.max(1, levels));
    GpuTexture texture =
        device.createTexture(
            "CraftQ3 " + name,
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM,
            base.width(),
            base.height(),
            1,
            mips.size());
    try {
      for (int level = 0; level < mips.size(); level++) {
        Q3Image image = mips.get(level);
        writeLevel(encoder, texture, level, image);
        uploadedBytes += (long) image.width() * image.height() * 4;
      }
      uploads++;
      return new Entry(texture, device.createTextureView(texture), pinned, generation);
    } catch (RuntimeException e) {
      texture.close();
      throw e;
    }
  }

  /** Writes one mip level; the pixels are copied before this returns on both backends. */
  static void writeLevel(CommandEncoder encoder, GpuTexture texture, int level, Q3Image image) {
    byte[] rgba = image.rgba();
    ByteBuffer pixels = MemoryUtil.memAlloc(rgba.length);
    try {
      pixels.put(rgba).flip();
      encoder.writeToTexture(texture, pixels, level, 0, 0, 0, image.width(), image.height());
    } finally {
      MemoryUtil.memFree(pixels);
    }
  }
}
