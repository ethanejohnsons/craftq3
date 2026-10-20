package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.game.bot.BotSupport;
import dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A dedicated server over a real installation's game directory, driven by a manual clock. */
final class RealGame implements AutoCloseable {
  final SearchPath searchPath;
  final GameServer server;
  final ManualClock clock = new ManualClock(0);
  final List<String> console = Collections.synchronizedList(new ArrayList<>());
  long now;

  RealGame(Path root, String game, BotSupport bots) throws IOException {
    searchPath = SearchPath.mount(root, game);
    FileViewAssets files = new FileViewAssets(searchPath.view());
    GameServer.Builder builder =
        GameServer.builder()
            .files(files)
            .vmFiles(FsVmFiles.of(searchPath, null, root))
            .clocks(clock, java.time.Clock.systemDefaultZone())
            .seed(42)
            .output(
                line -> {
                  console.add(line);
                  if (console.size() > 20_000) console.removeFirst();
                });
    if (bots != null) builder.bots(bots);
    server = builder.build();
    server.cvars().set("vm_strictSyscalls", "1");
    server.cvars().set("sv_pure", "0");
    if (!game.equals("baseq3")) server.cvars().set("fs_game", game);
  }

  /** Runs {@code millis} of game time in {@code sv_fps} steps as fast as possible. */
  void run(int millis) {
    int step = 1000 / Math.max(1, server.cvars().integer("sv_fps"));
    for (int t = 0; t < millis && server.state() == GameServer.State.RUNNING; t += step) {
      now += step;
      clock.advance(step);
      server.frame(now);
    }
  }

  String tail(int lines) {
    synchronized (console) {
      return String.join(
          "\n", console.subList(Math.max(0, console.size() - lines), console.size()));
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
    searchPath.close();
  }
}
