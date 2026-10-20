package dev.bluevista.craftq3.client.video;

import dev.bluevista.craftq3.assets.image.Q3Image;
import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.platform.audio.*;
import dev.bluevista.craftq3.render.CgameFrame;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** VM-owned RoQ handles and synchronized native PCM/image presentation. */
public final class Cinematics implements AutoCloseable {
  public static final int SYSTEM = 1, LOOP = 2, HOLD = 4, SILENT = 8, SHADER = 16;
  public static final int IDLE = 0, PLAY = 1, EOF = 2;
  private static final AtomicLong IDENTITIES = new AtomicLong();
  private final VirtualFileSystem fs;
  private final AudioBackend audio;
  private final LongSupplier nanos;
  private final Consumer<String> output;
  private final Map<Integer, Movie> movies = new HashMap<>();
  private boolean closed, frameBound;
  private long frameSerial;

  public record Info(
      long milliseconds,
      int frame,
      long cycle,
      OptionalLong audioStart,
      long submittedSamples,
      long consumedSamples,
      boolean audioClosed) {}

  public Cinematics(
      VirtualFileSystem fs, AudioBackend audio, LongSupplier nanos, Consumer<String> output) {
    this.fs = Objects.requireNonNull(fs);
    this.audio = Objects.requireNonNull(audio);
    this.nanos = Objects.requireNonNull(nanos);
    this.output = Objects.requireNonNull(output);
  }

  /** One presentation sample per host renderer frame, even if a guest repeats RUN/DRAW. */
  public void beginFrame() {
    frameBound = true;
    frameSerial++;
  }

  public int play(String name, int x, int y, int width, int height, int flags, int milliseconds) {
    if (closed) throw new IllegalStateException("Cinematic service closed");
    int handle = 0;
    while (movies.containsKey(handle)) handle++;
    if (handle >= 32) return -1;
    try {
      extents(x, y, width, height);
      if ((flags & ~31) != 0) throw new IllegalArgumentException("Unknown cinematic flags");
      String path = name.contains("/") ? name : "video/" + name;
      if (!path.toLowerCase(Locale.ROOT).endsWith(".roq")) path += ".roq";
      var movie = new Movie(new VirtualPath(path), x, y, width, height, flags, milliseconds);
      movies.put(handle, movie);
      return handle;
    } catch (IOException | RuntimeException failure) {
      output.accept("Cannot play cinematic " + name + ": " + failure.getMessage());
      return -1;
    }
  }

  public int run(int handle, int milliseconds) {
    var movie = movies.get(handle);
    if (movie == null) return EOF;
    try {
      if (movie.playback.state() == RoqPlayback.State.HELD) return IDLE;
      if (frameBound && movie.lastFrame == frameSerial && !movie.playback.catchingUp()) return PLAY;
      movie.lastFrame = frameSerial;
      movie.engineTime = Math.max(movie.engineTime, milliseconds);
      long clock;
      if (movie.queue == null) clock = movie.cycleMillis + movie.engineTime - movie.engineBase;
      else {
        if (movie.queue.failure().isPresent()) throw movie.queue.failure().orElseThrow();
        var started = movie.queue.startedAtNanos();
        clock =
            started.isEmpty()
                ? movie.lastMillis
                : movie.cycleMillis
                    + Math.max(0, (nanos.getAsLong() - started.getAsLong()) / 1_000_000);
        if (movie.queue.closed()
            && (!movie.queue.producerFinished()
                || movie.queue.consumedFrames() != movie.queue.submittedFrames()))
          throw new IOException("Native cinematic audio stopped before completion");
      }
      movie.lastMillis = Math.max(movie.lastMillis, clock);
      var state = movie.playback.advance(movie.lastMillis);
      if (state == RoqPlayback.State.ENDED) return stop(handle);
      return state == RoqPlayback.State.HELD ? IDLE : PLAY;
    } catch (IOException | RuntimeException failure) {
      output.accept("Cinematic playback failed: " + failure.getMessage());
      return stop(handle);
    }
  }

  /** Cinematic extents use Quake's 640x480 virtual screen, independently of image dimensions. */
  public Optional<CgameFrame.Image> draw(int handle, int framebufferWidth, int framebufferHeight) {
    var movie = movies.get(handle);
    if (movie == null) return Optional.empty();
    var video = movie.playback.video();
    if (video.isEmpty()) return Optional.empty();
    if (movie.lastVideo != video.get()) {
      movie.lastVideo = video.get();
      movie.pixels = video.get().image();
    }
    return Optional.of(
        new CgameFrame.Image(
            movie.identity,
            movie.x * framebufferWidth / 640f,
            movie.y * framebufferHeight / 480f,
            movie.width * framebufferWidth / 640f,
            movie.height * framebufferHeight / 480f,
            movie.pixels));
  }

  public void setExtents(int handle, int x, int y, int width, int height) {
    var movie = movies.get(handle);
    if (movie == null) return;
    extents(x, y, width, height);
    movie.x = x;
    movie.y = y;
    movie.width = width;
    movie.height = height;
  }

  public Optional<Info> info(int handle) {
    var movie = movies.get(handle);
    if (movie == null) return Optional.empty();
    var queue = movie.queue;
    return Optional.of(
        new Info(
            movie.lastMillis,
            movie.playback.video().map(RoqDecoder.Video::number).orElse(-1),
            movie.playback.cycle(),
            queue == null ? OptionalLong.empty() : queue.startedAtNanos(),
            queue == null ? 0 : queue.submittedFrames(),
            queue == null ? 0 : queue.consumedFrames(),
            queue != null && queue.closed()));
  }

  public int active() {
    return movies.size();
  }

  public int stop(int handle) {
    var movie = movies.remove(handle);
    if (movie != null) {
      try {
        movie.playback.close();
      } catch (IOException | RuntimeException failure) {
        output.accept("Cinematic cleanup failed: " + failure.getMessage());
      }
    }
    return EOF;
  }

  /**
   * Release all guest handles during a VM/renderer restart without closing the borrowed backend.
   */
  public void clear() {
    for (int handle : List.copyOf(movies.keySet())) stop(handle);
  }

  @Override
  public void close() {
    clear();
    closed = true;
  }

  private static void extents(int x, int y, int width, int height) {
    if (Math.abs((long) x) > 32768
        || Math.abs((long) y) > 32768
        || width < 1
        || height < 1
        || width > 32768
        || height > 32768) throw new IllegalArgumentException("Invalid cinematic extents");
  }

  private final class Movie implements RoqPlayback.AudioSink {
    final long identity = IDENTITIES.incrementAndGet();
    final RoqPlayback playback;
    int x, y, width, height, engineTime, engineBase;
    long voice, cycleMillis, lastMillis, primedAt;
    long lastFrame = Long.MIN_VALUE;
    RoqAudioStream queue;
    final VirtualPath path;
    RoqDecoder.Video lastVideo;
    Q3Image pixels;

    Movie(VirtualPath path, int x, int y, int width, int height, int flags, int milliseconds)
        throws IOException {
      this.path = path;
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
      engineTime = engineBase = milliseconds;
      var mode =
          (flags & LOOP) != 0
              ? RoqPlayback.Mode.LOOP
              : (flags & HOLD) != 0 ? RoqPlayback.Mode.HOLD : RoqPlayback.Mode.ONCE;
      boolean silent = (flags & SILENT) != 0;
      playback = new RoqPlayback(() -> fs.open(path), mode, silent, this, silent ? 0 : 500);
      try {
        playback.advance(0);
      } catch (IOException | RuntimeException failure) {
        try {
          playback.close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    @Override
    public void begin(long cycle, long startUnits, int units) {
      queue = null;
      voice = 0;
      cycleMillis = (startUnits * 1000 + units - 1) / units;
      engineBase = engineTime;
      primedAt = lastMillis;
    }

    @Override
    public void samples(RoqDecoder.Audio audioChunk) {
      var sound = audioChunk.sound();
      if (queue == null) {
        if (lastMillis > Math.max(cycleMillis, primedAt))
          throw new IllegalStateException(
              "Cinematic audio begins beyond the startup prefetch window");
        var format = new PcmStream.Format(sound.sampleRate(), sound.channels(), sound.bits());
        try {
          queue = new RoqAudioStream(() -> fs.open(path), format);
        } catch (IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
        voice = audio.stream(queue, 1);
        if (voice == 0) throw new IllegalStateException("Native cinematic stream unavailable");
      }
      // The separate bounded producer supplies PCM, including EOF before a silent video tail.
    }

    @Override
    public void end() {
      if (voice != 0) audio.stop(voice);
      if (queue != null) queue.close();
    }
  }
}
