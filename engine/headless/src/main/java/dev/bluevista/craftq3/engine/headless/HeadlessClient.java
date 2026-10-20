package dev.bluevista.craftq3.engine.headless;

import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.audio.NullAudioDevice;
import dev.bluevista.craftq3.engine.client.RenderSink;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.session.EngineSession;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.SessionHost;
import dev.bluevista.craftq3.engine.session.SessionOptions;
import dev.bluevista.craftq3.engine.session.SessionState;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A scripted Quake III client without a window: an {@link EngineSession} with a {@link
 * RecordingRenderSink} and a {@link NullAudioDevice}, driven frame by frame. With a manual clock
 * (the default) time only advances in {@link #frame()} steps and the local server is stepped on the
 * calling thread, so a scripted run is reproducible and runs as fast as the CPU allows; with the
 * system clock it runs in real time with the server on {@code cq3-sim}.
 */
public final class HeadlessClient implements AutoCloseable {
  /**
   * How a headless client runs.
   *
   * @param home the writable home root, or null for none
   * @param seed the session seed (0 draws one)
   * @param manualClock time advances only with frames (and the server is stepped synchronously)
   * @param frameMillis client frame length
   * @param keepFrames newest frames the sink keeps
   * @param log receives every console line (may be null)
   */
  public record Options(
      Path install,
      String game,
      Path home,
      long seed,
      boolean manualClock,
      int frameMillis,
      int keepFrames,
      List<String> commandLine,
      LocalServer.Factory servers,
      boolean netEnabled,
      Consumer<String> log) {
    public Options {
      Objects.requireNonNull(install);
      Objects.requireNonNull(game);
      if (frameMillis < 1 || frameMillis > EngineSession.MAX_FRAME_MILLIS)
        throw new IllegalArgumentException(
            "frameMillis must be 1.." + EngineSession.MAX_FRAME_MILLIS);
      commandLine = List.copyOf(commandLine);
    }

    public Options withGame(String value) {
      return new Options(
          install,
          value,
          home,
          seed,
          manualClock,
          frameMillis,
          keepFrames,
          commandLine,
          servers,
          netEnabled,
          log);
    }

    public Options withHome(Path value) {
      return new Options(
          install,
          game,
          value,
          seed,
          manualClock,
          frameMillis,
          keepFrames,
          commandLine,
          servers,
          netEnabled,
          log);
    }

    public Options withSeed(long value) {
      return new Options(
          install,
          game,
          home,
          value,
          manualClock,
          frameMillis,
          keepFrames,
          commandLine,
          servers,
          netEnabled,
          log);
    }

    public Options withManualClock(boolean value) {
      return new Options(
          install,
          game,
          home,
          seed,
          value,
          frameMillis,
          keepFrames,
          commandLine,
          servers,
          netEnabled,
          log);
    }

    public Options withFrameMillis(int value) {
      return new Options(
          install,
          game,
          home,
          seed,
          manualClock,
          value,
          keepFrames,
          commandLine,
          servers,
          netEnabled,
          log);
    }

    public Options withKeepFrames(int value) {
      return new Options(
          install,
          game,
          home,
          seed,
          manualClock,
          frameMillis,
          value,
          commandLine,
          servers,
          netEnabled,
          log);
    }

    public Options withCommandLine(List<String> value) {
      return new Options(
          install,
          game,
          home,
          seed,
          manualClock,
          frameMillis,
          keepFrames,
          value,
          servers,
          netEnabled,
          log);
    }

    public Options withServers(LocalServer.Factory value) {
      return new Options(
          install,
          game,
          home,
          seed,
          manualClock,
          frameMillis,
          keepFrames,
          commandLine,
          value,
          netEnabled,
          log);
    }

    public Options withNetEnabled(boolean value) {
      return new Options(
          install,
          game,
          home,
          seed,
          manualClock,
          frameMillis,
          keepFrames,
          commandLine,
          servers,
          value,
          log);
    }

    public Options withLog(Consumer<String> value) {
      return new Options(
          install,
          game,
          home,
          seed,
          manualClock,
          frameMillis,
          keepFrames,
          commandLine,
          servers,
          netEnabled,
          value);
    }
  }

  /** Defaults: baseq3, no home, seed 42, manual clock, 16 ms frames, the engine:game server. */
  public static Options options(Path install) {
    return new Options(
        install,
        "baseq3",
        null,
        42,
        true,
        16,
        0,
        List.of(),
        dev.bluevista.craftq3.engine.session.GameLocalServer.FACTORY,
        false,
        null);
  }

  private final Options options;
  private final ManualClock manual = new ManualClock(1_000);
  private final RecordingRenderSink sink;
  private final NullAudioDevice audio = new NullAudioDevice(96, false);
  private final List<String> lines = new ArrayList<>();
  private final List<SessionState> states = new ArrayList<>();
  private final EngineSession session;

  private HeadlessClient(Options options) throws IOException {
    this.options = options;
    this.sink = new RecordingRenderSink(options.keepFrames());
    SessionOptions sessionOptions =
        (options.manualClock()
                ? SessionOptions.deterministic(options.seed(), options.servers())
                : SessionOptions.defaults(options.servers()).withSeed(options.seed()))
            .withDecodeImages(false)
            .withWriteConfig(false)
            .withNetEnabled(options.netEnabled())
            .withCommandLine(options.commandLine());
    session = EngineSession.start(new Host(), sessionOptions);
  }

  public static HeadlessClient start(Options options) throws IOException {
    return new HeadlessClient(options);
  }

  private final class Host implements SessionHost {
    @Override
    public RenderSink renderSink() {
      return sink;
    }

    @Override
    public AudioDevice audioDevice() {
      return audio;
    }

    @Override
    public int windowWidth() {
      return 640;
    }

    @Override
    public int windowHeight() {
      return 480;
    }

    @Override
    public Path installRoot() {
      return options.install();
    }

    @Override
    public String game() {
      return options.game();
    }

    @Override
    public Path homeDirectory() {
      return options.home();
    }

    @Override
    public Clock clock() {
      return options.manualClock() ? manual : Clock.system();
    }

    @Override
    public java.time.Clock wallClock() {
      return options.manualClock()
          ? java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
          : java.time.Clock.systemDefaultZone();
    }

    @Override
    public void log(String line) {
      synchronized (lines) {
        lines.add(line);
        if (lines.size() > 20_000) lines.subList(0, 10_000).clear();
      }
      if (options.log() != null) options.log().accept(line);
    }

    @Override
    public void onStateChanged(SessionState previous, SessionState current) {
      states.add(current);
    }
  }

  public EngineSession session() {
    return session;
  }

  public RecordingRenderSink frames() {
    return sink;
  }

  public NullAudioDevice audio() {
    return audio;
  }

  public SessionState state() {
    return session.state();
  }

  /** Every state the session entered, in order. */
  public List<SessionState> states() {
    return List.copyOf(states);
  }

  /** Appends console text. */
  public void command(String text) {
    session.command(text);
  }

  /** One client frame, {@code frameMillis} after the previous one. */
  public void frame() {
    if (options.manualClock()) manual.advance(options.frameMillis());
    else LockSupport.parkNanos(options.frameMillis() * 1_000_000L);
    session.frame();
  }

  /** Runs frames for {@code millis} of (manual or real) time. */
  public void run(int millis) {
    if (options.manualClock()) {
      for (int t = 0; t < millis; t += options.frameMillis()) frame();
      return;
    }
    long end = System.nanoTime() + millis * 1_000_000L;
    while (System.nanoTime() < end) frame();
  }

  /** Frames until {@code done} holds or {@code timeoutMillis} of time passed. */
  public boolean runUntil(BooleanSupplier done, int timeoutMillis) {
    if (options.manualClock()) {
      for (int t = 0; t < timeoutMillis; t += options.frameMillis()) {
        if (done.getAsBoolean()) return true;
        frame();
      }
      return done.getAsBoolean();
    }
    long end = System.nanoTime() + timeoutMillis * 1_000_000L;
    while (System.nanoTime() < end) {
      if (done.getAsBoolean()) return true;
      frame();
    }
    return done.getAsBoolean();
  }

  /** Frames until the session is in {@code state}. */
  public boolean runUntil(SessionState state, int timeoutMillis) {
    return runUntil(() -> session.state() == state, timeoutMillis);
  }

  public void key(int key, boolean down) {
    session.keyEvent(key, down);
  }

  /** Presses and releases {@code key} with a frame in between. */
  public void tap(int key) {
    session.keyEvent(key, true);
    frame();
    session.keyEvent(key, false);
  }

  public void mouse(int dx, int dy) {
    session.mouseEvent(dx, dy);
  }

  /** The console lines printed so far (the newest 10 000 at least). */
  public List<String> console() {
    synchronized (lines) {
      return List.copyOf(lines);
    }
  }

  /** The newest {@code count} console lines, joined. */
  public String tail(int count) {
    synchronized (lines) {
      return String.join("\n", lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }
  }

  /** True when any console line contains {@code text}. */
  public boolean printed(String text) {
    synchronized (lines) {
      for (String line : lines) if (line.contains(text)) return true;
    }
    return false;
  }

  @Override
  public void close() {
    session.close();
  }
}
