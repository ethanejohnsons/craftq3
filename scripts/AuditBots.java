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
 * Opt-in original VM exploration. An unsupported service is a checkpoint, never a bot-play pass.
 */
class AuditBots {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException("AuditBots <installation> <map> [qagame.qvm]");
    int duration = Integer.getInteger("craftq3.audit.botMilliseconds", 10000);
    String botName = System.getProperty("craftq3.audit.botName", "sarge");
    String multipleNames = System.getProperty("craftq3.audit.botNames");
    List<String> botNames =
        multipleNames == null ? List.of(botName) : List.of(multipleNames.split(",", -1));
    int skill = Integer.getInteger("craftq3.audit.botSkill", 3);
    int seed = Integer.getInteger("craftq3.audit.seed", 42);
    int gameType = Integer.getInteger("craftq3.audit.gameType", 0);
    if (gameType < 0 || gameType > 4)
      throw new IllegalArgumentException("Bot audit game type must be 0..4");
    String inventoryHeaderProperty = System.getProperty("craftq3.audit.botInventoryHeader");
    Path inventoryHeader =
        inventoryHeaderProperty == null ? null : Path.of(inventoryHeaderProperty).toAbsolutePath();
    byte[] inventoryReplacement;
    if (inventoryHeader == null) inventoryReplacement = null;
    else {
      try (var input = Files.newInputStream(inventoryHeader)) {
        inventoryReplacement = input.readNBytes(65537);
      }
      if (inventoryReplacement.length > 65536)
        throw new IllegalArgumentException("Audit inventory header exceeds 64 KiB");
    }
    boolean requireCombat = Boolean.getBoolean("craftq3.audit.requireBotCombat");
    boolean requireMovement =
        requireCombat || Boolean.getBoolean("craftq3.audit.requireBotMovement");
    if (duration < 500 || duration > 300000)
      throw new IllegalArgumentException("Bot audit duration must be 500..300000 milliseconds");
    if (botNames.isEmpty()
        || botNames.size() > 7
        || botNames.stream().anyMatch(name -> !name.matches("[A-Za-z0-9_-]{1,32}"))
        || skill < 1
        || skill > 5)
      throw new IllegalArgumentException(
          "Bot audit requires 1..7 bot names and a skill from 1 to 5");
    System.out.printf(
        "Bot audit setup: bots=%s skill=%d seed=%d duration=%d gameType=%d%n",
        botNames, skill, seed, duration, gameType);
    if (inventoryHeader != null)
      System.out.println("QA-only botfiles/inv.h override: " + inventoryHeader);
    try (var packs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      byte[] replacement = args.length == 3 ? Files.readAllBytes(Path.of(args[2])) : null;
      VirtualFileSystem files =
          new VirtualFileSystem() {
            public byte[] read(VirtualPath path) throws java.io.IOException {
              if (inventoryReplacement != null && path.value().equals("botfiles/inv.h"))
                return inventoryReplacement.clone();
              return replacement != null && path.value().equals("vm/qagame.qvm")
                  ? replacement.clone()
                  : packs.read(path);
            }

            public Optional<Origin> which(VirtualPath path) {
              if (inventoryReplacement != null && path.value().equals("botfiles/inv.h"))
                return Optional.of(new Origin("baseq3", inventoryHeader.toString(), false));
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
      try (var server =
          new Q3Server(
              files,
              args[1],
              map,
              null,
              System.out::print,
              Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
        server.cvars().set("bot_enable", "1", CvarSystem.Source.ENGINE);
        server.cvars().set("g_gametype", Integer.toString(gameType), CvarSystem.Source.ENGINE);
        String phase = "GAME_INIT";
        RuntimeException checkpoint = null;
        Map<Integer, Activity> activity = new LinkedHashMap<>();
        int initialTargetHealth = 0, targetHealth = 0, targetDeathTime = 0;
        try {
          server.initialize(1000, seed);
          int startTime = server.time();
          phase = "connect";
          server.connect(0, Map.of("name", "BotAudit", "model", "sarge", "handicap", "100"));
          initialTargetHealth = state(server, 0).getInt(184);
          for (String name : botNames) {
            phase = "addbot " + name;
            if (!server.consoleCommand(CommandParser.tokenize("addbot " + name + " " + skill)))
              throw new AssertionError("Original qagame did not recognize addbot");
          }
          for (int time = startTime + 50; time <= startTime + duration; time += 50) {
            phase = "frame " + time;
            server.runFrame(time);
            targetHealth = state(server, 0).getInt(184);
            if (targetDeathTime == 0 && initialTargetHealth > 0 && targetHealth <= 0)
              targetDeathTime = time;
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
              || activity.size() != botNames.size()
              || activity.values().stream()
                  .anyMatch(value -> value.movingFrames < 10 || value.horizontalDistance < 128))
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

  private static ByteBuffer state(Q3Server server, int client) {
    return ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
  }
}
