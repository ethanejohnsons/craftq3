import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.server.GameAbi;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Read-only QA observation of guest player ground contacts and guest mover positions. */
class AuditPlatformRides {
  public static void main(String[] args) throws Exception {
    if (args.length < 3 || args.length > 4)
      throw new IllegalArgumentException(
          "AuditPlatformRides <installation> <map> <events.jsonl> [qagame.qvm]");
    int duration = Integer.getInteger("craftq3.audit.botMilliseconds", 60000);
    int skill = Integer.getInteger("craftq3.audit.botSkill", 3);
    int seed = Integer.getInteger("craftq3.audit.seed", 42);
    List<String> names =
        List.of(
            System.getProperty(
                    "craftq3.audit.botNames", System.getProperty("craftq3.audit.botName", "sarge"))
                .split(",", -1));
    if (duration < 500
        || duration > 300000
        || skill < 1
        || skill > 5
        || names.isEmpty()
        || names.size() > 7
        || names.stream().anyMatch(name -> !name.matches("[A-Za-z0-9_-]{1,32}")))
      throw new IllegalArgumentException(
          "Bounded audit requires 500..300000 ms and 1..7 valid bots");
    var overrides = new HashMap<String, byte[]>();
    if (args.length == 4) overrides.put("vm/qagame.qvm", bounded(Path.of(args[3]), 64 << 20));
    String header = System.getProperty("craftq3.audit.botInventoryHeader");
    if (header != null) overrides.put("botfiles/inv.h", bounded(Path.of(header), 65536));
    try (var packs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3");
        var events = new PrintWriter(Files.newBufferedWriter(Path.of(args[2])))) {
      VirtualFileSystem files =
          new VirtualFileSystem() {
            public byte[] read(VirtualPath path) throws java.io.IOException {
              byte[] replacement = overrides.get(path.value());
              return replacement == null ? packs.read(path) : replacement.clone();
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
      var platformModels = new HashMap<Integer, String>();
      for (var entity : map.entities()) {
        String kind = entity.getOrDefault("classname", "");
        String model = entity.getOrDefault("model", "");
        if ((kind.equals("func_bobbing") || kind.equals("func_plat")) && model.matches("\\*[0-9]+"))
          platformModels.put(Integer.parseInt(model.substring(1)), kind);
      }
      emit(
          events,
          "setup",
          "map",
          args[1],
          "duration",
          duration,
          "seed",
          seed,
          "skill",
          skill,
          "bots",
          names.toString(),
          "qagame",
          args.length == 4 ? args[3] : "pack",
          "inventoryHeader",
          header == null ? "pack" : header);
      try (var server =
          new Q3Server(
              files,
              args[1],
              map,
              null,
              System.out::print,
              Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
        server.cvars().set("bot_enable", "1", CvarSystem.Source.ENGINE);
        server.initialize(1000, seed);
        int start = server.time();
        server.connect(0, Map.of("name", "BotAudit", "model", "sarge", "handicap", "100"));
        for (String name : names)
          if (!server.consoleCommand(CommandParser.tokenize("addbot " + name + " " + skill)))
            throw new AssertionError("Original guest rejected addbot " + name);
        var reader = new EntityReader(server, platformModels);
        var observers = new LinkedHashMap<Integer, Observer>();
        boolean completed = false;
        try {
          for (int time = start + 50; time <= start + duration; time += 50) {
            server.runFrame(time);
            for (int client = 0; client < server.cvars().integer("sv_maxclients"); client++)
              if (server.isBot(client))
                observers
                    .computeIfAbsent(client, c -> new Observer(c, events))
                    .sample(
                        time,
                        ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN),
                        reader);
          }
          completed = true;
        } finally {
          for (var observer : observers.values())
            observer.finish(completed ? "audit-end" : "checkpoint");
          int rides = observers.values().stream().mapToInt(o -> o.rides).sum();
          int boards = observers.values().stream().mapToInt(o -> o.boardings).sum();
          int deaths = observers.values().stream().mapToInt(o -> o.deaths).sum();
          emit(
              events,
              "summary",
              "completed",
              completed,
              "time",
              server.time(),
              "bots",
              observers.size(),
              "boardings",
              boards,
              "qualifiedRides",
              rides,
              "deaths",
              deaths);
          System.out.printf(
              "PLATFORM_RIDE_SUMMARY completed=%s time=%d bots=%d boardings=%d qualifiedRides=%d"
                  + " deaths=%d events=%s%n",
              completed, server.time(), observers.size(), boards, rides, deaths, args[2]);
          if (completed && Boolean.getBoolean("craftq3.audit.requirePlatformRide") && rides == 0)
            throw new AssertionError(
                "No qualified platform ride observed; movement alone is insufficient");
        }
      }
      if (events.checkError())
        throw new java.io.IOException("Could not write ride observation log");
    }
  }

  private static byte[] bounded(Path path, int limit) throws java.io.IOException {
    try (var stream = Files.newInputStream(path)) {
      byte[] data = stream.readNBytes(limit + 1);
      if (data.length > limit)
        throw new IllegalArgumentException("Audit override exceeds limit: " + path);
      return data;
    }
  }

  private static Field field(Object object, String name) throws ReflectiveOperationException {
    Field result = object.getClass().getDeclaredField(name);
    result.setAccessible(true);
    return result;
  }

  record Mover(int entity, int model, String kind, float x, float y, float z) {}

  /**
   * Reflection is confined to this observer; reads the already located guest shared-entity array.
   */
  private static final class EntityReader {
    final Object entities;
    final Field base, count, stride;
    final QvmMemory memory;
    final GameAbi abi;
    final Map<Integer, String> models;

    EntityReader(Q3Server server, Map<Integer, String> models) throws ReflectiveOperationException {
      entities = field(server, "entities").get(server);
      memory = (QvmMemory) field(entities, "memory").get(entities);
      abi = (GameAbi) field(entities, "abi").get(entities);
      base = field(entities, "entities");
      count = field(entities, "count");
      stride = field(entities, "stride");
      this.models = Map.copyOf(models);
    }

    Mover mover(int number) throws IllegalAccessException {
      if (number < 0 || number >= 1022 || number >= count.getInt(entities)) return null;
      int address =
          Math.addExact(base.getInt(entities), Math.multiplyExact(number, stride.getInt(entities)));
      int model = memory.readInt(address + 160);
      if (memory.readInt(address + 4) != 4
          || memory.readInt(address + abi.linked()) == 0
          || !models.containsKey(model)) return null;
      int origin = address + abi.origin();
      return new Mover(
          number,
          model,
          models.get(model),
          memory.readFloat(origin),
          memory.readFloat(origin + 4),
          memory.readFloat(origin + 8));
    }
  }

  private static final class Observer {
    final int client;
    final PrintWriter events;
    int priorHealth, priorFlags, priorMode, boardings, rides, deaths;
    boolean sampled;
    Episode episode;

    Observer(int client, PrintWriter events) {
      this.client = client;
      this.events = events;
    }

    void sample(int time, ByteBuffer state, EntityReader entities) throws IllegalAccessException {
      int health = state.getInt(184), flags = state.getInt(104), mode = state.getInt(4);
      boolean died = sampled && priorHealth > 0 && health <= 0;
      boolean teleport = sampled && ((priorFlags ^ flags) & 4) != 0;
      if (died) {
        deaths++;
        emit(
            events,
            "death",
            "client",
            client,
            "time",
            time,
            "groundEntity",
            state.getInt(68),
            "z",
            state.getFloat(28));
      }
      Mover mover = mode == 0 && health > 0 ? entities.mover(state.getInt(68)) : null;
      if (episode != null
          && (mover == null
              || mover.entity != episode.mover.entity
              || mover.model != episode.mover.model
              || teleport
              || mode != priorMode))
        finish(
            died
                ? "death"
                : teleport ? "teleport" : mover == null ? "lost-ground-contact" : "changed-mover");
      if (mover != null) {
        if (episode == null) {
          episode = new Episode(time, state.getFloat(28), mover);
          boardings++;
          emit(
              events,
              "boarding",
              "client",
              client,
              "time",
              time,
              "entity",
              mover.entity,
              "model",
              mover.model,
              "kind",
              mover.kind,
              "playerX",
              state.getFloat(20),
              "playerY",
              state.getFloat(24),
              "playerZ",
              state.getFloat(28),
              "moverZ",
              mover.z);
        } else episode.sample(time, state.getFloat(28), mover);
        emit(
            events,
            "contact",
            "client",
            client,
            "time",
            time,
            "entity",
            mover.entity,
            "model",
            mover.model,
            "playerZ",
            state.getFloat(28),
            "moverZ",
            mover.z,
            "health",
            health);
      }
      sampled = true;
      priorHealth = health;
      priorFlags = flags;
      priorMode = mode;
    }

    void finish(String reason) {
      if (episode == null) return;
      var e = episode;
      boolean qualified =
          e.samples >= 5
              && e.lastTime - e.start >= 200
              && e.maxMover - e.minMover >= 32
              && e.maxPlayer - e.minPlayer >= 32
              && e.maxError <= 1;
      if (qualified) rides++;
      emit(
          events,
          "departure",
          "client",
          client,
          "entity",
          e.mover.entity,
          "model",
          e.mover.model,
          "start",
          e.start,
          "end",
          e.lastTime,
          "samples",
          e.samples,
          "reason",
          reason,
          "playerDeltaZ",
          e.lastPlayer - e.firstPlayer,
          "moverDeltaZ",
          e.mover.z - e.firstMover,
          "playerRangeZ",
          e.maxPlayer - e.minPlayer,
          "moverRangeZ",
          e.maxMover - e.minMover,
          "maximumStepErrorZ",
          e.maxError,
          "qualifiedRide",
          qualified);
      episode = null;
    }
  }

  private static final class Episode {
    final int start;
    final float firstPlayer, firstMover;
    int lastTime, samples = 1;
    float lastPlayer, minPlayer, maxPlayer, minMover, maxMover;
    double maxError;
    Mover mover;

    Episode(int time, float playerZ, Mover mover) {
      start = lastTime = time;
      firstPlayer = lastPlayer = minPlayer = maxPlayer = playerZ;
      firstMover = minMover = maxMover = mover.z;
      this.mover = mover;
    }

    void sample(int time, float playerZ, Mover current) {
      maxError =
          Math.max(
              maxError, Math.abs(((double) playerZ - lastPlayer) - ((double) current.z - mover.z)));
      minPlayer = Math.min(minPlayer, playerZ);
      maxPlayer = Math.max(maxPlayer, playerZ);
      minMover = Math.min(minMover, current.z);
      maxMover = Math.max(maxMover, current.z);
      lastPlayer = playerZ;
      mover = current;
      lastTime = time;
      samples++;
    }
  }

  private static void emit(PrintWriter writer, String event, Object... values) {
    if (values.length % 2 != 0) throw new IllegalArgumentException("Event keys require values");
    StringJoiner json = new StringJoiner(",", "{\"event\":" + quote(event) + ",", "}");
    for (int i = 0; i < values.length; i += 2) {
      Object value = values[i + 1];
      json.add(
          quote(values[i].toString())
              + ":"
              + (value instanceof Number || value instanceof Boolean
                  ? value
                  : quote(value.toString())));
    }
    writer.println(json);
  }

  private static String quote(String text) {
    return "\""
        + text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }
}
