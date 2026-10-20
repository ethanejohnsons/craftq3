package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.audio.NullAudioDevice;
import dev.bluevista.craftq3.engine.client.RenderSink;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A session host for tests: a manual (or system) clock, recorded frames, logs and states. */
final class TestHost implements SessionHost {
  final Path install;
  final Path home;
  final ManualClock manual = new ManualClock(1_000);
  final boolean systemClock;
  final List<String> log = new CopyOnWriteArrayList<>();
  final List<SessionState> states = new ArrayList<>();
  final List<CgameFrame> frames = new ArrayList<>();
  final NullAudioDevice audio = new NullAudioDevice(64, false);
  boolean keepFrames = true;
  long framesSeen;
  long quads;

  /** Frames handed to {@code submitImmediate} (loading screens inside a VM call). */
  long immediateFrames;

  /** Quads of the immediate frames. */
  long immediateQuads;

  boolean quitRequested;
  Boolean hostUi;

  TestHost(Path install, Path home, boolean systemClock) {
    this.install = install;
    this.home = home;
    this.systemClock = systemClock;
  }

  @Override
  public RenderSink renderSink() {
    return new RenderSink() {
      @Override
      public void submit(CgameFrame frame) {
        framesSeen++;
        for (CgameFrame.Command command : frame.commands())
          if (command instanceof CgameFrame.Quad) quads++;
        if (keepFrames) frames.add(frame);
      }

      @Override
      public void submitImmediate(CgameFrame frame) {
        immediateFrames++;
        for (CgameFrame.Command command : frame.commands())
          if (command instanceof CgameFrame.Quad) immediateQuads++;
        submit(frame);
      }
    };
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
  public void requestQuit() {
    quitRequested = true;
  }

  @Override
  public Path installRoot() {
    return install;
  }

  @Override
  public Path homeDirectory() {
    return home;
  }

  @Override
  public Clock clock() {
    return systemClock ? Clock.system() : manual;
  }

  @Override
  public java.time.Clock wallClock() {
    return java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
  }

  @Override
  public void log(String line) {
    log.add(line);
  }

  @Override
  public void onStateChanged(SessionState previous, SessionState current) {
    states.add(current);
  }

  @Override
  public void requestHostUi(boolean visible) {
    hostUi = visible;
  }

  String tail(int lines) {
    return String.join("\n", log.subList(Math.max(0, log.size() - lines), log.size()));
  }
}
