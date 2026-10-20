package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.roq.RoqDecoder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Plays the RoQ files of {@code videoMap} stages into textures. Each file is one looping, silent
 * stream shared by every stage that names it (as the original's cinematic handles are). The frame
 * shown is {@code floor(shaderSeconds × frameRate)}, wrapped at the file's length; frames are
 * decoded in order on one worker thread, which skips colour conversion for frames it only passes
 * through, and the render thread only uploads the newest finished frame with {@code
 * writeToTexture}. A stream shows black until its first frame is ready and the missing-texture
 * image when its file is absent or broken. Render-thread methods must not run inside a render pass.
 */
final class Q3VideoMaps implements AutoCloseable {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Frames one worker task decodes at most before handing back, so a jump never blocks long. */
  private static final int MAX_FRAMES_PER_TASK = 64;

  private final Q3TextureCache textures;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "CraftQ3 RoQ");
            thread.setDaemon(true);
            return thread;
          });
  private final Map<String, Stream> streams = new HashMap<>();
  private AssetLookup assets;
  private long frame;

  Q3VideoMaps(Q3TextureCache textures, AssetLookup assets) {
    this.textures = textures;
    this.assets = assets;
  }

  /** The file view new streams read from (the current map's). */
  void assets(AssetLookup assets) {
    this.assets = assets;
  }

  /** Starts a frame: each stream uploads at most once per frame. */
  void beginFrame() {
    frame++;
  }

  /** The texture showing {@code path} at {@code seconds} of shader time. */
  GpuTextureView texture(CommandEncoder encoder, String path, double seconds) {
    Stream stream = streams.get(path);
    if (stream == null) {
      stream = new Stream(path, assets);
      streams.put(path, stream);
    }
    return stream.texture(encoder, seconds);
  }

  /** Streams currently open. */
  int size() {
    return streams.size();
  }

  /** Stops every stream and releases its texture (map change). */
  void clear() {
    streams.values().forEach(Stream::close);
    streams.clear();
  }

  @Override
  public void close() {
    clear();
    worker.shutdownNow();
  }

  /** A decoded frame handed from the worker: absolute index (loops included) and pixels. */
  private record Decoded(long index, Q3Image image) {}

  private final class Stream {
    final String path;
    final AssetLookup source;
    final AtomicBoolean busy = new AtomicBoolean();
    volatile Decoded latest;
    volatile boolean failed;
    volatile int rate = 30;
    // Worker-only decoder state.
    RoqDecoder decoder;
    byte[] file;
    long loopBase;
    long next;
    long length = -1;
    volatile boolean closed;
    // Render-thread texture state.
    GpuTexture texture;
    GpuTextureView view;
    long uploaded = -1;
    long uploadFrame = -1;

    Stream(String path, AssetLookup source) {
      this.path = path;
      this.source = source;
    }

    GpuTextureView texture(CommandEncoder encoder, double seconds) {
      if (failed) return textures.missing(encoder);
      long want = (long) Math.floor(Math.max(0, seconds) * rate);
      Decoded ready = latest;
      if ((ready == null || ready.index() != want) && busy.compareAndSet(false, true))
        worker.execute(() -> advance(want));
      if (ready != null && ready.index() != uploaded && uploadFrame != frame)
        upload(encoder, ready);
      return view != null ? view : textures.black(encoder);
    }

    private void upload(CommandEncoder encoder, Decoded ready) {
      Q3Image image = ready.image();
      if (texture == null
          || texture.getWidth(0) != image.width()
          || texture.getHeight(0) != image.height()) {
        release();
        GpuDevice device = RenderSystem.getDevice();
        texture =
            device.createTexture(
                "CraftQ3 videoMap " + path,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                image.width(),
                image.height(),
                1,
                1);
        view = device.createTextureView(texture);
      }
      Q3TextureCache.writeLevel(encoder, texture, 0, image);
      uploaded = ready.index();
      uploadFrame = frame;
    }

    /** Worker: decodes forward to frame {@code want} (or as far as one task may go). */
    private void advance(long want) {
      try {
        if (closed) return;
        if (decoder == null) open(0);
        // Loop k of a file of known length holds absolute frames k*length .. (k+1)*length-1.
        // Reopen when the wanted frame lies behind the decoder or in a later loop.
        if (length > 0) {
          long loopStart = want / length * length;
          if (want < next - 1 || loopStart > loopBase) open(loopStart);
        } else if (want < next - 1) open(0);
        Decoded result = null;
        RoqDecoder.Video last = null;
        long lastIndex = -1;
        for (int decoded = 0; decoded < MAX_FRAMES_PER_TASK && next <= want; ) {
          Optional<RoqDecoder.Event> event = decoder.next();
          if (event.isEmpty()) {
            if (next == loopBase) throw new IOException("no video frames");
            length = next - loopBase;
            open(next);
            if (want >= next) continue;
            break;
          }
          if (!(event.get() instanceof RoqDecoder.Video video)) continue;
          rate = video.rate();
          last = video;
          lastIndex = next++;
          decoded++;
        }
        if (last != null) result = new Decoded(lastIndex, last.image());
        if (result != null) latest = result;
      } catch (IOException | RuntimeException broken) {
        failed = true;
        LOGGER.warn("CraftQ3: videoMap {} cannot play: {}", path, broken.toString());
      } finally {
        busy.set(false);
      }
    }

    /** Worker: reopens the file at its first frame, which becomes absolute frame {@code base}. */
    private void open(long base) throws IOException {
      if (decoder != null) decoder.close();
      if (file == null)
        file = source.read(path).orElseThrow(() -> new IOException("file not found"));
      decoder = new RoqDecoder(new ByteArrayInputStream(file));
      rate = decoder.frameRate();
      loopBase = base;
      next = base;
    }

    /** Stops the stream; the worker drops its in-memory decoder on its next task, if any. */
    void close() {
      closed = true;
      release();
    }

    private void release() {
      if (view != null) view.close();
      if (texture != null) texture.close();
      view = null;
      texture = null;
      uploaded = -1;
    }
  }
}
