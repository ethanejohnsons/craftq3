package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.map.MapSource;
import dev.bluevista.craftq3.engine.vmabi.service.VmFiles;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A server over the synthetic corridor map and the Java test game. */
final class Harness {
  final FakeGame game = new FakeGame();
  final List<String> console = new ArrayList<>();
  final ManualClock clock = new ManualClock(0);
  final GameServer server;
  long now;

  Harness() {
    this(TestMaps::corridor);
  }

  Harness(Supplier<GameMap> map) {
    this(map, null);
  }

  Harness(Supplier<GameMap> map, dev.bluevista.craftq3.engine.fs.PureManifest pure) {
    this(map, pure, new NoFiles());
  }

  /** Over the corridor map with the given game files. */
  Harness(VmFiles files) {
    this(TestMaps::corridor, null, files);
  }

  Harness(Supplier<GameMap> map, dev.bluevista.craftq3.engine.fs.PureManifest pure, VmFiles files) {
    MapSource maps =
        new MapSource() {
          @Override
          public boolean exists(String name) {
            return name.equals("corridor") || name.equals("other");
          }

          @Override
          public GameMap load(String name) throws IOException {
            if (!exists(name)) throw new NoSuchFileException(name);
            GameMap m = map.get();
            return new GameMap(name, m.bsp(), m.entityText(), m.checksum(), m.aas(), m.sources());
          }
        };
    server =
        GameServer.builder()
            .files(new MemoryAssets())
            .vmFiles(files)
            .maps(maps)
            .program(() -> game)
            .bots(new dev.bluevista.craftq3.engine.game.bot.NoBotlib())
            .clocks(clock, java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
            .seed(1)
            .pure(pure)
            .output(console::add)
            .build();
    server.cvars().set("vm_strictSyscalls", "1");
  }

  Harness spawn() {
    if (!server.spawn("corridor", false)) throw new AssertionError("spawn failed: " + console);
    return this;
  }

  /** Advances {@code millis} of host time in 50 ms steps. */
  int run(int millis) {
    int frames = 0;
    for (int t = 0; t < millis; t += 50) {
      now += 50;
      clock.advance(50);
      frames += server.frame(now);
    }
    return frames;
  }

  /** Every reliable command still in client {@code n}'s window. */
  List<String> reliable(int n) {
    ServerClient client = server.client(n);
    List<String> result = new ArrayList<>();
    for (int s = client.reliableAcknowledge() + 1; s <= client.reliableSequence(); s++)
      result.add(client.reliableCommand(s));
    return result;
  }

  /** {@link VmFiles} for a module that finds no files. */
  static final class NoFiles implements VmFiles {
    @Override
    public int length(String path) {
      return -1;
    }

    @Override
    public Opened open(String path, Mode mode) throws IOException {
      throw new NoSuchFileException(path);
    }

    @Override
    public byte[] read(int handle, int length) throws IOException {
      throw new IOException("bad handle");
    }

    @Override
    public int write(int handle, byte[] data) throws IOException {
      throw new IOException("bad handle");
    }

    @Override
    public boolean seek(int handle, int offset, Origin origin) throws IOException {
      throw new IOException("bad handle");
    }

    @Override
    public void close(int handle) throws IOException {
      throw new IOException("bad handle");
    }

    @Override
    public List<String> list(String directory, String extension) {
      return List.of();
    }
  }
}
