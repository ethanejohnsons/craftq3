package dev.bluevista.craftq3.client.video;

import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded streaming movie timeline. The host supplies elapsed monotonic time and queues PCM at the
 * supplied sample positions; no device, renderer, native engine or wall clock is borrowed.
 */
public final class RoqPlayback implements AutoCloseable {
  public enum Mode {
    ONCE,
    LOOP,
    HOLD
  }

  public enum State {
    PLAYING,
    ENDED,
    HELD,
    CLOSED,
    FAILED
  }

  @FunctionalInterface
  public interface Source {
    InputStream open() throws IOException;
  }

  /**
   * Callbacks execute on the playback thread. PCM is owned and immutable. begin replaces any old
   * queue, with a cycle origin measured in exact timeline units, and end releases the current
   * output queue. Audio consumers must honor timestamps rather than start a separate voice for each
   * chunk.
   */
  public interface AudioSink {
    void begin(long cycle, long startUnits, int unitsPerSecond);

    void samples(RoqDecoder.Audio audio);

    void end();

    /** All samples for this cycle have been decoded; queued audio can now terminate naturally. */
    default void finish() {}
  }

  private static final int SAMPLE_RATE = 22050, EVENTS_PER_ADVANCE = 256;
  private static final long MAX_MILLIS = 86_400_000;
  private final Source source;
  private final AudioSink audio;
  private final Mode mode;
  private final boolean silent;
  private final int rate, units, prefetchMillis;
  private final java.util.ArrayDeque<RoqDecoder.Video> future = new java.util.ArrayDeque<>();
  private long futureBytes;
  private RoqDecoder decoder;
  private RoqDecoder.Video current;
  private State state = State.PLAYING;
  private long elapsed, cycle, start, videoEnd, audioEnd, duration;
  private boolean eof, audioOpen, catchingUp;

  public RoqPlayback(
      VirtualFileSystem fs, VirtualPath path, Mode mode, boolean silent, AudioSink audio)
      throws IOException {
    this(() -> fs.open(path), mode, silent, audio);
  }

  public RoqPlayback(Source source, Mode mode, boolean silent, AudioSink audio) throws IOException {
    this(source, mode, silent, audio, 0);
  }

  public RoqPlayback(Source source, Mode mode, boolean silent, AudioSink audio, int prefetchMillis)
      throws IOException {
    if (prefetchMillis < 0 || prefetchMillis > 1000)
      throw new IllegalArgumentException("Movie prefetch must be within one second");
    this.prefetchMillis = prefetchMillis;
    this.source = Objects.requireNonNull(source);
    this.mode = Objects.requireNonNull(mode);
    this.audio = Objects.requireNonNull(audio);
    this.silent = silent;
    decoder = new RoqDecoder(source.open());
    rate = decoder.frameRate();
    units = rate / gcd(rate, SAMPLE_RATE) * SAMPLE_RATE;
    try {
      beginAudio();
    } catch (RuntimeException failure) {
      fail(failure);
      throw failure;
    }
  }

  public State state() {
    return state;
  }

  public long cycle() {
    return cycle;
  }

  public int unitsPerSecond() {
    return units;
  }

  public long cycleStartUnits() {
    return start;
  }

  public boolean catchingUp() {
    return catchingUp;
  }

  public Optional<RoqDecoder.Video> video() {
    return Optional.ofNullable(current);
  }

  /**
   * Presents the latest due frame and streams ordered PCM ahead of it. Default playback retains one
   * future frame; optional prefetch retains up to 32 MiB of future planar frames. Work is bounded
   * per call; after a long stall, repeat with the same timestamp while catchingUp is true. Normal
   * 30/60 Hz playback does not discard frames or audio samples.
   */
  public State advance(long milliseconds) throws IOException {
    if (milliseconds < elapsed || milliseconds > MAX_MILLIS)
      throw new IllegalArgumentException("Movie clock must be monotonic and within 24 hours");
    if (state == State.CLOSED || state == State.FAILED)
      throw new IllegalStateException("Movie playback is " + state);
    elapsed = milliseconds;
    catchingUp = false;
    if (state != State.PLAYING) return state;
    long target = milliseconds * units / 1000;
    try {
      for (int work = 0; work < EVENTS_PER_ADVANCE; work++) {
        long local = target - start;
        while (!future.isEmpty() && (long) future.getFirst().number() * (units / rate) <= local) {
          current = future.removeFirst();
          futureBytes -= (long) current.width() * current.height() * 3;
        }
        if (eof) {
          if (local < duration) return state;
          endAudio();
          if (mode != Mode.LOOP) {
            state = mode == Mode.HOLD ? State.HELD : State.ENDED;
            if (state == State.ENDED) current = null;
            return state;
          }
          // Skip whole, already-known cycles after a stall, without repeated decoding/reopening.
          long cycles = Math.max(1, local / duration);
          start += cycles * duration;
          cycle += cycles;
          decoder = new RoqDecoder(source.open());
          if (decoder.frameRate() != rate) throw new IOException("RoQ rate changed while looping");
          eof = false;
          current = null;
          videoEnd = audioEnd = 0;
          beginAudio();
        }
        if (!future.isEmpty()
            && (long) future.getLast().number() * (units / rate)
                > target - start + (long) prefetchMillis * units / 1000) return state;
        var event = decoder.next();
        if (event.isEmpty()) {
          decoder.close();
          decoder = null;
          eof = true;
          duration = Math.max(videoEnd, audioEnd);
          if (videoEnd == 0) throw new IOException("RoQ contains no video frames");
          if (!silent) audio.finish();
        } else if (event.get() instanceof RoqDecoder.Video video) {
          long bytes = (long) video.width() * video.height() * 3;
          if (future.size() >= 1024 || futureBytes + bytes > 32L * 1024 * 1024)
            throw new IOException("RoQ video prefetch exceeds memory budget");
          future.addLast(video);
          futureBytes += bytes;
          videoEnd = ((long) video.number() + 1) * (units / rate);
        } else if (event.get() instanceof RoqDecoder.Audio sound) {
          audioEnd = (sound.firstSample() + sound.sound().frames()) * (units / SAMPLE_RATE);
          if (!silent) audio.samples(sound);
        }
      }
      catchingUp = true;
      return state;
    } catch (IOException | RuntimeException failure) {
      fail(failure);
      throw failure;
    }
  }

  private void beginAudio() {
    if (!silent) {
      audioOpen = true;
      audio.begin(cycle, start, units);
    }
  }

  private void endAudio() {
    if (audioOpen) {
      audioOpen = false;
      audio.end();
    }
  }

  private void fail(Exception failure) {
    state = State.FAILED;
    current = null;
    future.clear();
    futureBytes = 0;
    try {
      closeDecoder();
    } catch (IOException cleanup) {
      failure.addSuppressed(cleanup);
    }
    try {
      endAudio();
    } catch (RuntimeException cleanup) {
      failure.addSuppressed(cleanup);
    }
  }

  private void closeDecoder() throws IOException {
    var owned = decoder;
    decoder = null;
    if (owned != null) owned.close();
  }

  @Override
  public void close() throws IOException {
    if (state == State.CLOSED) return;
    state = State.CLOSED;
    current = null;
    future.clear();
    futureBytes = 0;
    IOException failure = null;
    try {
      closeDecoder();
    } catch (IOException cleanup) {
      failure = cleanup;
    }
    try {
      endAudio();
    } catch (RuntimeException cleanup) {
      if (failure == null) throw cleanup;
      failure.addSuppressed(cleanup);
    }
    if (failure != null) throw failure;
  }

  private static int gcd(int a, int b) {
    while (b != 0) {
      int next = a % b;
      a = b;
      b = next;
    }
    return a;
  }
}
