package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * A synthetic installation (hand-assembled ui and cgame, placeholder maps, configs) and a session
 * over it with the {@link FakeLocalServer}.
 */
final class SyntheticRig implements AutoCloseable {
  final TestHost host;
  final EngineSession session;

  SyntheticRig(Path root, boolean threaded, String... commandLine) throws IOException {
    this(root, threaded, SessionOptions.deterministic(42, FakeLocalServer.FACTORY), commandLine);
  }

  SyntheticRig(Path root, boolean threaded, SessionOptions options, String... commandLine)
      throws IOException {
    Path install = root.resolve("install");
    Path home = root.resolve("home");
    if (!Files.exists(install)) writeInstall(install);
    host = new TestHost(install, home, threaded);
    session =
        EngineSession.start(
            host,
            options
                .withSynchronousServer(!threaded)
                .withCommandLine(CommandLine.parse(commandLine)));
  }

  static void writeInstall(Path install) throws IOException {
    Path base = Files.createDirectories(install.resolve("baseq3"));
    Files.createDirectories(base.resolve("vm"));
    Files.createDirectories(base.resolve("maps"));
    Files.write(base.resolve("vm/ui.qvm"), TestQvm.returning(6));
    Files.write(base.resolve("vm/cgame.qvm"), TestQvm.snapshotCgame());
    for (String map : new String[] {"test", "other", "bad"})
      Files.write(base.resolve("maps/" + map + ".bsp"), new byte[] {'I', 'B', 'S', 'P'});
    Files.writeString(
        base.resolve("default.cfg"),
        "set test_default 1\nbind w +forward\n",
        StandardCharsets.ISO_8859_1);
  }

  /** One frame {@code millis} after the previous one (manual clock only). */
  void step(int millis) {
    host.manual.advance(millis);
    session.frame();
  }

  /** Frames until {@code done} holds; manual clock steps 16 ms, the system clock sleeps. */
  boolean until(BooleanSupplier done, int maxFrames) {
    for (int i = 0; i < maxFrames; i++) {
      if (done.getAsBoolean()) return true;
      if (host.systemClock) {
        try {
          Thread.sleep(4);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
        session.frame();
      } else step(16);
    }
    return done.getAsBoolean();
  }

  void run(int frames) {
    for (int i = 0; i < frames; i++) {
      if (host.systemClock) {
        try {
          Thread.sleep(4);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        session.frame();
      } else step(16);
    }
  }

  FakeLocalServer server() {
    return FakeLocalServer.CREATED.getLast();
  }

  /** The quads of the newest frame (the synthetic cgame draws one per snapshot). */
  long lastFrameQuads() {
    if (host.frames.isEmpty()) return 0;
    return host.frames.getLast().commands().stream()
        .filter(CgameFrame.Quad.class::isInstance)
        .count();
  }

  String tail() {
    return host.tail(40);
  }

  @Override
  public void close() {
    session.close();
  }
}
