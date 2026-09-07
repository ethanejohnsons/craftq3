import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.BspSceneBuilder;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.render.RenderScene;
import dev.bluevista.craftq3.render.SubmittedGeometry;
import dev.bluevista.craftq3.server.GameAbi;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Original bot decisions; optional spectator input only marks that human observer ready. */
class AuditBotMatch {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException(
          "AuditBotMatch <installation> <map> [qagame.qvm [inventory-header]]");
    int duration = Integer.getInteger("craftq3.audit.matchMilliseconds", 300000);
    int fraglimit = Integer.getInteger("craftq3.audit.fraglimit", 3);
    int seed = Integer.getInteger("craftq3.audit.seed", 42);
    int skill = Integer.getInteger("craftq3.audit.botSkill", 3);
    boolean readyObserver = Boolean.getBoolean("craftq3.audit.readyObserver");
    boolean renderCgame = Boolean.getBoolean("craftq3.audit.matchCgame");
    if (renderCgame && !readyObserver)
      throw new IllegalArgumentException("matchCgame requires the passive readyObserver");
    List<String> names =
        List.of(System.getProperty("craftq3.audit.botNames", "sarge,major,visor").split(",", -1));
    if (duration < 10000
        || duration > 600000
        || fraglimit < 1
        || fraglimit > 20
        || skill < 1
        || skill > 5
        || names.size() < 2
        || names.size() > 7
        || names.stream().anyMatch(name -> !name.matches("[A-Za-z0-9_-]{1,32}")))
      throw new IllegalArgumentException("Invalid bounded match configuration");
    byte[] vm = args.length >= 3 ? bounded(Path.of(args[2]), 16 * 1024 * 1024) : null;
    byte[] inventory = args.length >= 4 ? bounded(Path.of(args[3]), 65536) : null;
    System.out.printf(
        "MATCH SETUP map=%s bots=%s skill=%d seed=%d fraglimit=%d maxMs=%d vm=%s inventory=%s%n",
        args[1],
        names,
        skill,
        seed,
        fraglimit,
        duration,
        args.length >= 3 ? Path.of(args[2]).toAbsolutePath() : "mounted",
        args.length >= 4 ? Path.of(args[3]).toAbsolutePath() : "mounted");
    System.out.println(
        "MATCH OBSERVER readyInput=" + readyObserver + " originalCgame=" + renderCgame);
    try (var packs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      VirtualFileSystem files =
          new VirtualFileSystem() {
            public byte[] read(VirtualPath path) throws java.io.IOException {
              if (vm != null && path.value().equals("vm/qagame.qvm")) return vm.clone();
              if (inventory != null && path.value().equals("botfiles/inv.h"))
                return inventory.clone();
              return packs.read(path);
            }

            public Optional<Origin> which(VirtualPath path) {
              return packs.which(path);
            }

            public List<VirtualPath> list(String directory) {
              return packs.list(directory);
            }

            public List<Origin> searchOrder() {
              return packs.searchOrder();
            }

            public void close() {}
          };
      var bsp = BspReader.read(files.read(new VirtualPath("maps/" + args[1] + ".bsp")));
      try (var server =
          new Q3Server(
              files,
              args[1],
              bsp,
              null,
              System.out::print,
              Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
        for (var entry :
            Map.of(
                    "bot_enable",
                    "1",
                    "g_gametype",
                    "0",
                    "fraglimit",
                    Integer.toString(fraglimit),
                    "timelimit",
                    "0",
                    "g_doWarmup",
                    "0",
                    "nextmap",
                    "map_restart 0")
                .entrySet())
          server.cvars().set(entry.getKey(), entry.getValue(), CvarSystem.Source.ENGINE);
        List<String> requests = new ArrayList<>();
        server
            .commands()
            .register(
                "map_restart",
                command -> {
                  if (!command.arguments().equals(List.of("map_restart", "0")))
                    throw new AssertionError("Unexpected match restart command: " + command.text());
                  requests.add(command.text());
                  System.out.printf(
                      "MATCH ENGINE REQUEST time=%d command=%s%n", server.time(), command.text());
                  server.commands().frameBoundary();
                });
        server.initialize(1000, seed);
        if (readyObserver) {
          server.connect(
              0, Map.of("name", "LifecycleObserver", "model", "sarge", "handicap", "100"));
          server.clientCommand(0, "team spectator");
        }
        for (String name : names)
          if (!server.consoleCommand(CommandParser.tokenize("addbot " + name + " " + skill)))
            throw new AssertionError("Original addbot was not recognized");
        Observation seen =
            new Observation(
                names.size(),
                fraglimit,
                server.abi() == GameAbi.RETAIL_1999 ? 14 : 22,
                readyObserver ? 1 : 0);
        int start = server.time(), limit = start + duration, restartTime = 0;
        String phase = "first match";
        try (var presentation =
            renderCgame ? new Presentation(files, server, args[1], bsp) : null) {
          try {
            while (server.time() < limit) {
              server.runFrame(server.time() + 50);
              seen.sample(server);
              if (presentation != null) presentation.frame(server, seen);
              if (readyObserver
                  && seen.allIntermission
                  && !seen.readySent
                  && server.time() >= seen.firstAllIntermission + 5000) {
                server.clientCommand(0, "score");
                var press = new UserCommand(server.time(), 0, 0, 0, 1, 0, 0, 0, 0);
                if (presentation == null) {
                  server.userCommand(0, press);
                  server.userCommand(0, UserCommand.idle(server.time()));
                } else {
                  presentation.client.userCommand(press);
                  presentation.client.userCommand(UserCommand.idle(server.time()));
                }
                seen.readySent = true;
                System.out.println("MATCH OBSERVER READY time=" + server.time());
              }
              if (!requests.isEmpty() && restartTime == 0) {
                if (!seen.limitReached || !seen.intermission || !seen.allIntermission)
                  throw new AssertionError("Restart preceded verified match completion: " + seen);
                phase = "original requested restart";
                int sequence = seen.sequence;
                server.restart(seed);
                restartTime = server.time();
                seen.restarted(server, sequence);
                if (presentation != null) presentation.frame(server, seen);
                phase = "post-restart match";
              }
              if (restartTime > 0 && server.time() >= restartTime + 15000) break;
              if (server.time() % 10000 == 0)
                System.out.println("MATCH PROGRESS time=" + server.time() + " " + seen);
            }
          } catch (RuntimeException | AssertionError failure) {
            System.out.printf(
                "MATCH CHECKPOINT phase=%s time=%d state=%s observation=%s%n",
                phase, server.time(), server.state(), seen);
            System.out.println("MATCH BOTLIB " + server.botlibStatus());
            if (presentation != null) System.out.println("MATCH CGAME CHECKPOINT " + presentation);
            throw failure;
          }
          System.out.println("MATCH FINAL time=" + server.time() + " " + seen);
          if (presentation != null) {
            System.out.println("MATCH CGAME FINAL " + presentation);
            if (presentation.intermissionFrames == 0
                || presentation.afterRestartFrames < 300
                || presentation.afterRestartWorldViews == 0
                || presentation.afterRestartModels == 0
                || presentation.afterRestartSurfaces == 0)
              throw new AssertionError(
                  "Original cgame did not cover the complete lifecycle: " + presentation);
            System.out.println(
                "Original cgame intermission/restart/continued scene submission PASS; strict"
                    + " game-state assertions follow");
          }
          if (restartTime == 0 || !seen.restartClear || seen.afterRestartMoving < 10)
            throw new AssertionError(
                "No complete original match/restart/continued-play lifecycle: " + seen);
          System.out.println(
              "Original bot fraglimit/intermission/requested restart/continued play PASS");
        }
      }
    }
  }

  private static byte[] bounded(Path path, int cap) throws Exception {
    try (var input = Files.newInputStream(path)) {
      byte[] data = input.readNBytes(cap + 1);
      if (data.length > cap)
        throw new IllegalArgumentException("Audit override exceeds cap: " + path);
      return data;
    }
  }

  private static final class Presentation implements AutoCloseable {
    final Q3Client client;
    final RenderScene world;
    final SubmittedGeometry geometry = new SubmittedGeometry();
    int frames, views, quads, intermissionFrames, afterRestartFrames;
    int afterRestartWorldViews, afterRestartModels;
    long surfaces, afterRestartSurfaces;

    Presentation(VirtualFileSystem files, Q3Server server, String mapName, BspMap bsp) {
      world = BspSceneBuilder.build(mapName, bsp, 8);
      try {
        client = new Q3Client(files, server, frame -> {}, new Audio(), System.out::print);
        client.initialize(0, 1280, 720);
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    }

    void frame(Q3Server server, Observation observation) {
      var frame = client.frame(server.time(), 1280, 720);
      for (var command : frame.commands()) {
        if (command instanceof CgameFrame.View view) {
          views++;
          int builtSurfaces = geometry.build(world, view, false).size();
          surfaces += builtSurfaces;
          if (observation.restartClear) {
            afterRestartSurfaces += builtSurfaces;
            if ((view.refdef().rdflags() & CgameFrame.RDF_NOWORLDMODEL) == 0) {
              afterRestartWorldViews++;
              afterRestartModels +=
                  (int)
                      view.entities().stream()
                          .filter(
                              entity ->
                                  entity.type() == CgameFrame.EntityType.MODEL
                                      && entity.model() != null
                                      && entity.visible(false))
                          .count();
            }
          }
        } else if (command instanceof CgameFrame.Quad) quads++;
      }
      frames++;
      if (observation.allIntermission && !observation.restartClear) intermissionFrames++;
      if (observation.restartClear) afterRestartFrames++;
    }

    public void close() throws java.io.IOException {
      client.close();
    }

    public String toString() {
      return "frames="
          + frames
          + " views="
          + views
          + " quads="
          + quads
          + " surfaces="
          + surfaces
          + " intermissionFrames="
          + intermissionFrames
          + " afterRestartFrames="
          + afterRestartFrames
          + " afterRestartWorldViews="
          + afterRestartWorldViews
          + " afterRestartModels="
          + afterRestartModels
          + " afterRestartSurfaces="
          + afterRestartSurfaces
          + " state="
          + client.state()
          + " vmInvocations="
          + client.vmStats().invocations();
    }
  }

  private static final class Audio implements AudioBackend {
    int sounds;
    long voices;

    public int register(String name, PcmSound sound) {
      return ++sounds;
    }

    public long play(Playback playback) {
      return ++voices;
    }

    public void updateEntity(int entity, Vec3 origin) {}

    public void beginFrame() {}

    public void submitLoop(Loop loop) {}

    public void endFrame(Listener listener) {}

    public void clearLoops() {}

    public void stop(long voice) {}

    public void stopAll() {}

    public void volume(float gain) {}

    public Diagnostics diagnostics() {
      return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "CPU match audit");
    }

    public void close() {}
  }

  private static final class Observation {
    final int expectedBots, fraglimit, intermissionIndex, observers;
    final Map<Integer, String> strings = new LinkedHashMap<>();
    final Map<Integer, Integer> scores = new LinkedHashMap<>();
    final Map<Integer, Integer> modes = new LinkedHashMap<>();
    boolean limitReached,
        intermission,
        allIntermission,
        restartClear,
        intermissionCleared,
        readySent;
    int sequence, firstLimit, firstIntermission, firstAllIntermission, afterRestartMoving;
    int reliableCount, scoreboardCount, restartCommands;

    Observation(int expectedBots, int fraglimit, int intermissionIndex, int observers) {
      this.expectedBots = expectedBots;
      this.fraglimit = fraglimit;
      this.intermissionIndex = intermissionIndex;
      this.observers = observers;
    }

    void sample(Q3Server server) {
      for (int index = 2; index < 32; index++) {
        String value = server.configstrings().get(index);
        if (!value.equals(strings.put(index, value)))
          System.out.printf("MATCH CS time=%d index=%d value=%s%n", server.time(), index, value);
      }
      if (!restartClear && server.configstrings().get(intermissionIndex).equals("1")) {
        intermission = true;
        if (firstIntermission == 0) firstIntermission = server.time();
      }
      int bots = 0, inIntermission = 0;
      for (int client = 0; client < server.cvars().integer("sv_maxclients"); client++) {
        if (!server.isBot(client)) continue;
        bots++;
        var state = ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
        int score = state.getInt(248), mode = state.getInt(4);
        Integer previousScore = scores.put(client, score), previousMode = modes.put(client, mode);
        if (previousScore == null
            || previousScore != score
            || previousMode == null
            || previousMode != mode)
          System.out.printf(
              "MATCH PLAYER time=%d client=%d score=%d mode=%d health=%d%n",
              server.time(), client, score, mode, state.getInt(184));
        if (!restartClear && score >= fraglimit) {
          limitReached = true;
          if (firstLimit == 0) firstLimit = server.time();
        }
        if (mode == 5) inIntermission++;
        if (restartClear && mode == 0 && Math.hypot(state.getFloat(32), state.getFloat(36)) > 1)
          afterRestartMoving++;
      }
      if (bots != expectedBots) throw new AssertionError("Bot count changed: " + bots);
      if (!restartClear && inIntermission == expectedBots) {
        allIntermission = true;
        if (firstAllIntermission == 0) firstAllIntermission = server.time();
      }
      drain(server);
    }

    void drain(Q3Server server) {
      for (var command : server.commandsSince(sequence)) {
        reliableCount++;
        sequence = command.sequence();
        String text = command.text();
        if (text.startsWith("scores ")
            || text.startsWith("map_restart")
            || text.toLowerCase(java.util.Locale.ROOT).contains("fraglimit")) {
          System.out.printf(
              "MATCH RELIABLE time=%d seq=%d client=%d text=%s%n",
              server.time(), sequence, command.client(), text.stripTrailing());
          if (text.startsWith("scores ")) scoreboardCount++;
          if (text.startsWith("map_restart")) restartCommands++;
        }
      }
    }

    void restarted(Q3Server server, int previousSequence) {
      intermissionCleared =
          server.configstrings().get(intermissionIndex).isEmpty()
              || server.configstrings().get(intermissionIndex).equals("0");
      if (server.restartCount() != 1 || server.snapshotFlags() != 4)
        throw new AssertionError(
            "Restart did not reset server state: count="
                + server.restartCount()
                + " flags="
                + server.snapshotFlags()
                + " intermission="
                + server.configstrings().get(intermissionIndex));
      for (int client = 0; client < server.cvars().integer("sv_maxclients"); client++) {
        if (!server.isBot(client)) continue;
        var state = ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
        if (state.getInt(248) != 0 || state.getInt(4) != 0)
          throw new AssertionError("Bot not reset into normal play: " + client);
      }
      drain(server);
      if (restartCommands != expectedBots + observers || sequence <= previousSequence)
        throw new AssertionError("Missing retained reliable restart commands");
      restartClear = true;
      System.out.printf(
          "MATCH RESTARTED time=%d count=%d flags=%d bots=%d intermissionCleared=%s%n",
          server.time(),
          server.restartCount(),
          server.snapshotFlags(),
          expectedBots,
          intermissionCleared);
    }

    public String toString() {
      return "scores="
          + scores
          + " modes="
          + modes
          + " limitTime="
          + firstLimit
          + " intermissionTime="
          + firstIntermission
          + " allIntermissionTime="
          + firstAllIntermission
          + " restartClear="
          + restartClear
          + " intermissionCleared="
          + intermissionCleared
          + " postRestartMoving="
          + afterRestartMoving
          + " reliable="
          + reliableCount
          + " scoreboards="
          + scoreboardCount
          + " restartCommands="
          + restartCommands
          + " observerReady="
          + readySent;
    }
  }
}
