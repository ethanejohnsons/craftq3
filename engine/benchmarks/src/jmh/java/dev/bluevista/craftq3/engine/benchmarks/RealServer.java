package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.game.GameProgram;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.QvmGameProgram;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.bot.BotlibAdapter;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.Syscalls;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.VmHost;
import dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A dedicated baseq3 server on a real installation with bots, driven by a manual clock as fast as
 * the benchmark calls {@link #frame()}. The qagame instance is captured so the VM benchmark can
 * call it directly.
 */
final class RealServer implements AutoCloseable {
  static final List<String> BOTS =
      List.of("Sarge", "Major", "Visor", "Grunt", "Anarki", "Doom", "Hunter", "Klesk");

  final SearchPath searchPath;
  final GameServer server;
  final ManualClock clock = new ManualClock(0);
  private QvmInstance qagame;
  private long now;

  RealServer(String map, int bots) throws IOException {
    this(map, bots, VmHost.Options.JIT_DEFAULT);
  }

  /** A server whose qagame runs as {@code vm_jit} {@code jit} selects. */
  RealServer(String map, int bots, String jit) throws IOException {
    Path root = RealDataBench.root();
    searchPath = SearchPath.mount(root, SearchPath.BASE_GAME);
    FileViewAssets files = new FileViewAssets(searchPath.view());
    server =
        GameServer.builder()
            .files(files)
            .vmFiles(FsVmFiles.of(searchPath, null, root))
            .clocks(clock, java.time.Clock.systemDefaultZone())
            .seed(42)
            .output(line -> {})
            .bots(new BotlibAdapter())
            .program(() -> capturing(QvmGameProgram.load(files, "auto", clock)))
            .build();
    server.cvars().set(VmHost.Options.JIT_CVAR, jit);
    server.cvars().set("sv_pure", "0");
    server.cvars().set("sv_maxclients", Integer.toString(Math.max(8, bots + 1)));
    server.cvars().set("bot_enable", "1");
    server.cvars().set("bot_nochat", "1");
    server.cvars().set("bot_minplayers", "0");
    server.cvars().set("g_warmup", "0");
    server.cvars().set("g_doWarmup", "0");
    server.cvars().set("fraglimit", "0");
    server.cvars().set("timelimit", "0");
    if (!server.spawn(map, false))
      throw new IllegalStateException("Cannot spawn " + map + ": " + server.dropReason());
    for (int i = 0; i < bots; i++) server.execute("addbot " + BOTS.get(i % BOTS.size()) + " 3");
    // Bots connect, then enter after their spawn delay; let the match settle.
    run(8_000);
    int active = activeBots();
    if (active != bots)
      throw new IllegalStateException("Only " + active + " of " + bots + " bots joined");
  }

  private GameProgram capturing(QvmGameProgram program) {
    return new GameProgram() {
      @Override
      public String name() {
        return program.name();
      }

      @Override
      public AbiProfile profile() {
        return program.profile();
      }

      @Override
      public QvmInstance instantiate(Syscalls syscalls) {
        qagame = program.instantiate(syscalls);
        return qagame;
      }

      @Override
      public QvmInstance instantiate(Syscalls syscalls, QvmInstance.ExecutionMode mode) {
        qagame = program.instantiate(syscalls, mode);
        return qagame;
      }
    };
  }

  /** The running qagame instance. */
  QvmInstance qagame() {
    return qagame;
  }

  /** The current server time, in the qagame's {@code levelTime} terms. */
  int levelTime() {
    return server.levelTime();
  }

  /** Advances one {@code sv_fps} frame: a bot frame and one game frame. */
  int frame() {
    int step = 1000 / Math.max(1, server.cvars().integer("sv_fps"));
    now += step;
    clock.advance(step);
    int ran = server.frame(now);
    if (server.state() != GameServer.State.RUNNING)
      throw new IllegalStateException("Server dropped: " + server.dropReason());
    return ran;
  }

  void run(int millis) {
    int step = 1000 / Math.max(1, server.cvars().integer("sv_fps"));
    for (int t = 0; t < millis; t += step) frame();
  }

  int activeBots() {
    int count = 0;
    for (int c = 0; c < server.maxClients(); c++) {
      ServerClient client = server.client(c);
      if (client.isBot() && client.state() == ServerClient.State.ACTIVE) count++;
    }
    return count;
  }

  @Override
  public void close() throws IOException {
    server.close();
    searchPath.close();
  }
}
