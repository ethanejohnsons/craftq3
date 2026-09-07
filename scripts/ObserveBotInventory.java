import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.server.Q3Server;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only runtime observation for bot inventory compatibility. The optional inventory-header
 * override is confined to this authored QA process; no mounted archive is changed. A transparent
 * syscall wrapper records the original trap558 request and delegated result without remapping data.
 */
class ObserveBotInventory {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException(
          "ObserveBotInventory <installation> <map> [qagame.qvm|module.pk3]");
    int duration = Integer.getInteger("craftq3.audit.botMilliseconds", 10000);
    boolean requireCombat = Boolean.getBoolean("craftq3.audit.requireBotCombat");
    boolean requireMovement =
        requireCombat || Boolean.getBoolean("craftq3.audit.requireBotMovement");
    if (duration < 500 || duration > 300000)
      throw new IllegalArgumentException("Bot audit duration must be 500..300000 milliseconds");
    try (var packs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      byte[] replacement = null;
      if (args.length == 3) {
        if (args[2].endsWith(".pk3")) {
          try (var moduleZip = new java.util.zip.ZipFile(args[2])) {
            replacement =
                moduleZip.getInputStream(moduleZip.getEntry("vm/qagame.qvm")).readAllBytes();
          }
        } else replacement = Files.readAllBytes(Path.of(args[2]));
      }
      final byte[] replacementBytes = replacement;
      String headerPath = System.getProperty("craftq3.audit.inventoryHeader");
      byte[] inventoryHeader = headerPath == null ? null : Files.readAllBytes(Path.of(headerPath));
      VirtualFileSystem files =
          new VirtualFileSystem() {
            public byte[] read(VirtualPath path) throws java.io.IOException {
              if (inventoryHeader != null && path.value().equals("botfiles/inv.h"))
                return inventoryHeader.clone();
              return replacementBytes != null && path.value().equals("vm/qagame.qvm")
                  ? replacementBytes.clone()
                  : packs.read(path);
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
      var map = BspReader.read(files.read(new VirtualPath("maps/" + args[1] + ".bsp")));
      var traceWorld = new dev.bluevista.craftq3.collision.BspTraceWorld(map);
      try (var server =
          new Q3Server(
              files,
              args[1],
              map,
              null,
              System.out::print,
              Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
        observe(server);
        server.cvars().set("bot_enable", "1", CvarSystem.Source.ENGINE);
        String phase = "GAME_INIT";
        RuntimeException checkpoint = null;
        Map<Integer, Activity> activity = new LinkedHashMap<>();
        int initialTargetHealth = 0, targetHealth = 0, targetDeathTime = 0;
        try {
          server.initialize(1000, 42);
          int startTime = server.time();
          phase = "connect";
          server.connect(0, Map.of("name", "BotAudit", "model", "sarge", "handicap", "100"));
          initialTargetHealth = state(server, 0).getInt(184);
          phase = "addbot sarge";
          if (!server.consoleCommand(CommandParser.tokenize("addbot sarge 3")))
            throw new AssertionError("Original qagame did not recognize addbot");
          for (int time = startTime + 50; time <= startTime + duration; time += 50) {
            phase = "frame " + time;
            server.runFrame(time);
            targetHealth = state(server, 0).getInt(184);
            if (targetDeathTime == 0 && initialTargetHealth > 0 && targetHealth <= 0)
              targetDeathTime = time;
            if ((time - startTime) % 500 == 0 && server.isBot(1)) {
              var target = state(server, 0);
              var bot = state(server, 1);
              var targetOrigin = vector(target, 20);
              var botOrigin = vector(bot, 20);
              var targetEye =
                  targetOrigin.add(
                      new dev.bluevista.craftq3.core.math.Vec3(0, 0, target.getInt(164)));
              var botEye =
                  botOrigin.add(new dev.bluevista.craftq3.core.math.Vec3(0, 0, bot.getInt(164)));
              var trace =
                  traceWorld.trace(
                      new dev.bluevista.craftq3.collision.TraceRequest(
                          botEye,
                          targetEye,
                          new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0),
                          new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0),
                          1,
                          1));
              boolean inSnapshot =
                  server.entitySnapshot(1).entities().stream()
                      .anyMatch(
                          e -> ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).getInt() == 0);
              System.out.println(
                  "OBS time="
                      + time
                      + " target="
                      + targetOrigin
                      + " bot="
                      + botOrigin
                      + " targetMode="
                      + target.getInt(4)
                      + " targetFlags="
                      + target.getInt(104)
                      + " targetWeapon="
                      + target.getInt(144)
                      + " botWeapon="
                      + bot.getInt(144)
                      + " botAngles="
                      + vector(bot, 152)
                      + " targetVisible="
                      + inSnapshot
                      + " worldFraction="
                      + trace.fraction()
                      + " weapons="
                      + bot.getInt(184 + 8)
                      + " bullets="
                      + bot.getInt(376 + 8)
                      + " health="
                      + bot.getInt(184));
            }
            for (int client = 0; client < server.cvars().integer("sv_maxclients"); client++)
              if (server.isBot(client))
                activity
                    .computeIfAbsent(client, ignored -> new Activity())
                    .sample(server.playerState(client));
          }
          System.out.println(
              "Bot calls completed through "
                  + server.time()
                  + "ms; this audit does not assert autonomous bot gameplay.");
        } catch (RuntimeException failure) {
          checkpoint = failure;
          System.out.println("BOT CHECKPOINT during " + phase + ": " + failure.getMessage());
          for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause())
            System.out.println("  cause: " + cause.getMessage());
        }
        System.out.println("Botlib status: " + server.botlibStatus());
        System.out.println("VM calls: " + server.syscallCounts());
        activity.forEach(
            (client, value) -> System.out.println("Bot client " + client + ": " + value));
        if (requireMovement) {
          if (checkpoint != null)
            throw new AssertionError("Bot movement audit stopped during " + phase, checkpoint);
          if (server.botlibStatus().calls().getOrDefault(549, 0L) < 2
              || activity.values().stream()
                  .noneMatch(value -> value.movingFrames >= 10 && value.horizontalDistance >= 128))
            throw new AssertionError("No sustained bot movement observed: " + activity);
          System.out.println(
              "Bot movement-only assertion PASS; combat/navigation completeness is not asserted.");
        }
        System.out.println(
            "Stationary target: health="
                + initialTargetHealth
                + " -> "
                + targetHealth
                + ", deathTime="
                + targetDeathTime);
        if (requireCombat) {
          if (targetDeathTime == 0
              || activity.values().stream()
                  .noneMatch(value -> value.ammoSpent > 0 && value.score > value.initialScore))
            throw new AssertionError(
                "No original-bot scored kill with ammunition use: " + activity);
          System.out.println(
              "Original bot stationary-target combat assertion PASS; broad match/navigation"
                  + " compatibility is not asserted.");
        }
      }
    }
  }

  private static final class Activity {
    private float x, y;
    private int flags, mode, samples, movingFrames, health, score, initialScore, ammoSpent;
    private final int[] ammo = new int[16];
    private double horizontalDistance;

    void sample(byte[] playerState) {
      var data = ByteBuffer.wrap(playerState).order(ByteOrder.LITTLE_ENDIAN);
      float nextX = data.getFloat(20), nextY = data.getFloat(24);
      int nextMode = data.getInt(4), nextFlags = data.getInt(104);
      double speed = Math.hypot(data.getFloat(32), data.getFloat(36));
      if (samples == 0) initialScore = data.getInt(248);
      boolean continuous =
          samples > 0 && mode == 0 && nextMode == 0 && ((flags ^ nextFlags) & 4) == 0;
      for (int weapon = 0; weapon < ammo.length; weapon++) {
        int nextAmmo = data.getInt(376 + weapon * 4);
        if (continuous && ammo[weapon] >= 0 && nextAmmo >= 0 && nextAmmo < ammo[weapon])
          ammoSpent += ammo[weapon] - nextAmmo;
        ammo[weapon] = nextAmmo;
      }
      // Canonical playerState: normal movement and unchanged teleport bit exclude respawn jumps.
      if (continuous && speed > 1) {
        double distance = Math.hypot(nextX - x, nextY - y);
        if (distance > 0) {
          horizontalDistance += distance;
          movingFrames++;
        }
      }
      samples++;
      x = nextX;
      y = nextY;
      mode = nextMode;
      flags = nextFlags;
      health = data.getInt(184);
      score = data.getInt(248);
    }

    @Override
    public String toString() {
      return "samples="
          + samples
          + ", movingFrames="
          + movingFrames
          + ", horizontalDistance="
          + horizontalDistance
          + ", health="
          + health
          + ", score="
          + score
          + ", ammoSpent="
          + ammoSpent;
    }
  }

  /** Reflection installs a transparent delegate only in this development-owned server instance. */
  private static void observe(Q3Server server) throws Exception {
    var field = Q3Server.class.getDeclaredField("vm");
    field.setAccessible(true);
    var vm = (dev.bluevista.craftq3.vm.QvmInterpreter) field.get(server);
    var calls = dev.bluevista.craftq3.vm.QvmInterpreter.class.getDeclaredField("syscalls");
    calls.setAccessible(true);
    var delegate = (dev.bluevista.craftq3.vm.QvmSyscalls) calls.get(vm);
    int[] last = {0};
    calls.set(
        vm,
        (dev.bluevista.craftq3.vm.QvmSyscalls)
            (memory, call, args) -> {
              int result = delegate.invoke(memory, call, args);
              if (call == 558 && server.time() - last[0] >= 500) {
                last[0] = server.time();
                var inv = new java.util.TreeMap<Integer, Integer>();
                for (int i = 0; i < 256; i++) {
                  int n = memory.readInt(args[1] + i * 4);
                  if (n != 0) inv.put(i, n);
                }
                System.out.println(
                    "WEAPON time=" + server.time() + " selected=" + result + " inventory=" + inv);
              }
              return result;
            });
  }

  private static dev.bluevista.craftq3.core.math.Vec3 vector(ByteBuffer bytes, int offset) {
    return new dev.bluevista.craftq3.core.math.Vec3(
        bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
  }

  private static ByteBuffer state(Q3Server server, int client) {
    return ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
  }
}
