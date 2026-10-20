package dev.bluevista.craftq3.engine.client.video;

import dev.bluevista.craftq3.engine.audio.SoundSystem;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.QPath;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.roq.RoqDecoder;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * RoQ cinematics for the cgame and ui ({@code trap_CIN_*}): a table of {@value #MAX_HANDLES}
 * movies, each decoded on the calling thread as its clock advances, with its soundtrack queued on
 * the sound system's raw-sample stream (unless {@link #SILENT}).
 *
 * <ul>
 *   <li>{@link #play} opens {@code video/<name>.roq} (a name with a directory is used as is) and
 *       returns a handle, or -1 when the file cannot be opened.
 *   <li>{@link #run} decodes up to the movie's time and returns {@link #FMV_PLAY}; at the end a
 *       looping movie restarts, a held one keeps its last frame and reports {@link #FMV_IDLE}, and
 *       any other reports {@link #FMV_EOF}.
 *   <li>{@link #draw} gives the current frame in framebuffer pixels: extents are in the 640x480
 *       virtual screen, as the original scales them ({@code SCR_AdjustFrom640}).
 * </ul>
 *
 * Single owner thread.
 */
public final class Cinematics implements AutoCloseable {
  /** {@code MAX_VIDEO_HANDLES}. */
  public static final int MAX_HANDLES = 16;

  /** {@code CIN_*} flags. */
  public static final int SYSTEM = 1, LOOP = 2, HOLD = 4, SILENT = 8, SHADER = 16;

  /** {@code e_status} values. */
  public static final int FMV_IDLE = 0, FMV_PLAY = 1, FMV_EOF = 2;

  /** Events one {@link #run} decodes at most, so a stall cannot block the frame. */
  public static final int EVENTS_PER_RUN = 512;

  private static final Log LOG = Log.get(Cinematics.class);

  /** Opens a file of the game file system. */
  @FunctionalInterface
  public interface Source {
    InputStream open(String path) throws IOException;
  }

  private final Source files;
  private final SoundSystem sound;
  private final Consumer<String> output;
  private final Movie[] movies = new Movie[MAX_HANDLES];
  private long nextStream = 1;
  private float volume = SoundSystem.DEFAULT_VOLUME;

  public Cinematics(Source files, SoundSystem sound, Consumer<String> output) {
    this.files = Objects.requireNonNull(files);
    this.sound = sound;
    this.output = Objects.requireNonNull(output);
  }

  /** The soundtrack gain ({@code s_volume}). */
  public void setVolume(float value) {
    volume = Float.isFinite(value) ? Math.clamp(value, 0, 1) : SoundSystem.DEFAULT_VOLUME;
  }

  private final class Movie {
    final String path;
    final int flags;
    final long stream = nextStream++;
    int x, y, width, height;
    RoqDecoder decoder;
    int startTime;
    int rate;
    RoqDecoder.Video pendingVideo;
    RoqDecoder.Video current;
    Q3Image pixels;
    RoqDecoder.Video pixelsOf;
    int status = FMV_PLAY;

    Movie(String path, int flags) {
      this.path = path;
      this.flags = flags;
    }

    void open(int time) throws IOException {
      decoder = new RoqDecoder(files.open(path));
      rate = decoder.frameRate();
      startTime = time;
      pendingVideo = null;
    }

    void closeDecoder() {
      if (decoder == null) return;
      try {
        decoder.close();
      } catch (IOException ignored) {
        // The stream is being discarded.
      }
      decoder = null;
    }
  }

  /** {@code trap_CIN_PlayCinematic(name, x, y, w, h, flags)}. */
  public int play(String name, int x, int y, int width, int height, int flags, int time) {
    int handle = 0;
    while (handle < MAX_HANDLES && movies[handle] != null) handle++;
    if (handle == MAX_HANDLES) {
      LOG.warnOnce("cin-full", "Cinematic table full; cannot play " + name);
      return -1;
    }
    String path = name.replace('\\', '/');
    if (!path.contains("/")) path = "video/" + path;
    if (!path.toLowerCase(Locale.ROOT).endsWith(".roq")) path = QPath.stripExtension(path) + ".roq";
    Movie movie = new Movie(path, flags);
    movie.x = x;
    movie.y = y;
    movie.width = width;
    movie.height = height;
    try {
      movie.open(time);
    } catch (IOException | RuntimeException failure) {
      output.accept("Cannot play cinematic " + name + ": " + failure.getMessage());
      return -1;
    }
    movies[handle] = movie;
    run(handle, time);
    return handle;
  }

  /** {@code trap_CIN_RunCinematic(handle)}. */
  public int run(int handle, int time) {
    Movie movie = movie(handle);
    if (movie == null) return FMV_EOF;
    if (movie.status != FMV_PLAY) return movie.status;
    try {
      long elapsed = Math.max(0, (long) time - movie.startTime);
      for (int events = 0; events < EVENTS_PER_RUN; events++) {
        if (movie.pendingVideo != null) {
          if (movie.pendingVideo.number() * 1000L / movie.rate > elapsed) break;
          movie.current = movie.pendingVideo;
          movie.pendingVideo = null;
        }
        Optional<RoqDecoder.Event> event = movie.decoder.next();
        if (event.isEmpty()) return finish(movie, time);
        switch (event.get()) {
          case RoqDecoder.Video video -> movie.pendingVideo = video;
          case RoqDecoder.Audio audio -> {
            if ((movie.flags & SILENT) == 0 && sound != null)
              sound.rawSamples(audio.sound(), volume);
          }
        }
      }
      return FMV_PLAY;
    } catch (IOException | RuntimeException failure) {
      output.accept("Cinematic " + movie.path + " failed: " + failure.getMessage());
      movie.closeDecoder();
      movie.status = FMV_EOF;
      return FMV_EOF;
    }
  }

  private int finish(Movie movie, int time) throws IOException {
    if (movie.pendingVideo != null) movie.current = movie.pendingVideo;
    movie.pendingVideo = null;
    movie.closeDecoder();
    if ((movie.flags & LOOP) != 0) {
      movie.open(time);
      return FMV_PLAY;
    }
    movie.status = (movie.flags & HOLD) != 0 ? FMV_IDLE : FMV_EOF;
    if (movie.status == FMV_EOF) movie.current = null;
    return movie.status;
  }

  /** {@code trap_CIN_DrawCinematic(handle)}: the frame to add, scaled to the framebuffer. */
  public Optional<CgameFrame.Image> draw(int handle, int framebufferWidth, int framebufferHeight) {
    Movie movie = movie(handle);
    if (movie == null || movie.current == null) return Optional.empty();
    if (movie.pixelsOf != movie.current) {
      movie.pixels = movie.current.image();
      movie.pixelsOf = movie.current;
    }
    float sx = framebufferWidth / 640f, sy = framebufferHeight / 480f;
    float w = movie.width * sx, h = movie.height * sy;
    if (!(w > 0) || !(h > 0)) return Optional.empty();
    try {
      return Optional.of(
          new CgameFrame.Image(movie.stream, movie.x * sx, movie.y * sy, w, h, movie.pixels));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  /** {@code trap_CIN_SetExtents(handle, x, y, w, h)}. */
  public void setExtents(int handle, int x, int y, int width, int height) {
    Movie movie = movie(handle);
    if (movie == null) return;
    movie.x = x;
    movie.y = y;
    movie.width = width;
    movie.height = height;
  }

  /** {@code trap_CIN_StopCinematic(handle)}: always {@link #FMV_EOF}. */
  public int stop(int handle) {
    Movie movie = movie(handle);
    if (movie != null) {
      movie.closeDecoder();
      movies[handle] = null;
    }
    return FMV_EOF;
  }

  /** Movies open. */
  public int active() {
    int count = 0;
    for (Movie movie : movies) if (movie != null) count++;
    return count;
  }

  /** Stops every movie (VM shutdown or restart). */
  public void clear() {
    for (int handle = 0; handle < MAX_HANDLES; handle++) stop(handle);
  }

  @Override
  public void close() {
    clear();
  }

  private Movie movie(int handle) {
    return handle >= 0 && handle < MAX_HANDLES ? movies[handle] : null;
  }
}
