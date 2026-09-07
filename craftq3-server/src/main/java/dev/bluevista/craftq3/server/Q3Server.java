package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.fs.GameFileHandles;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.fs.WritableFiles;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import dev.bluevista.craftq3.vm.QvmReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Single-thread local Q3 server. Original qagame owns entities, movement, weapons and match rules.
 */
public final class Q3Server implements AutoCloseable {
  public enum State {
    LOADED,
    RUNNING,
    FAILED,
    CLOSED
  }

  public record ServerCommand(int sequence, int client, String text) {}

  /** Area mask bits set to one hide that BSP area. Overflow is explicit at the Q3 ABI limit. */
  public record EntitySnapshot(
      List<byte[]> entities, BspMap.Bytes areaMask, int visibleEntities, int omittedEntities) {
    public EntitySnapshot {
      entities = entities.stream().map(byte[]::clone).toList();
    }

    @Override
    public List<byte[]> entities() {
      return entities.stream().map(byte[]::clone).toList();
    }
  }

  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final VirtualFileSystem fs;
  private final Consumer<String> output;
  private final Clock clock;
  private final CvarSystem cvars;
  private final ConfigStrings configstrings = new ConfigStrings();
  private final CommandSystem commands;
  private GameFileHandles files;
  private final WritableFiles writes;
  private final QvmInterpreter vm;
  private final GameAbi abi;
  private final EntityWorld entities;
  private final ExternalWorld externalWorld;
  private final MapLoadoutAdmission loadoutAdmission;
  private boolean admitted;
  private final java.util.Set<Integer> externalActors = new java.util.HashSet<>();
  private boolean externalAdmission;
  private final java.util.ArrayDeque<Integer> externalDamage = new java.util.ArrayDeque<>();
  private final int[] externalDamageTimes = new int[ExternalWorld.MAX_DAMAGE + 1];
  private final BotlibHost botlib;
  private final AreaConnectivity areas;
  private final SnapshotVisibility snapshotVisibility;
  private final List<String> entityTokens;
  private final Map<Integer, Long> calls = new LinkedHashMap<>();
  private final ArrayDeque<ServerCommand> serverCommands = new ArrayDeque<>();
  private final String[] userInfo = new String[64];
  private final UserCommand[] userCommands = new UserCommand[64];
  private final boolean[] connected = new boolean[64];
  private final boolean[] entered = new boolean[64];
  private final boolean[] bots = new boolean[64];
  private final int[] botReliableSequence = new int[64];
  private final Map<Integer, List<byte[]>> botSnapshots = new LinkedHashMap<>();
  private final boolean[] snapshotOverflow = new boolean[64];
  private CommandParser.Command context;
  private int token, time, maxClients, commandSequence, frame;
  private int snapshotFlags, restartCount, gameType;
  private State state = State.LOADED;

  public Q3Server(
      VirtualFileSystem fs,
      String mapName,
      BspMap map,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock)
      throws IOException {
    this(fs, mapName, map, writes, output, clock, null);
  }

  /**
   * An explicit profile permits independently verified legacy mods without guessing from stride.
   */
  public Q3Server(
      VirtualFileSystem fs,
      String mapName,
      BspMap map,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock,
      GameAbi profile)
      throws IOException {
    this(fs, mapName, map, writes, output, clock, profile, new CvarSystem());
  }

  /** The local host may retain engine cvars across UI, game and client VM lifetimes. */
  public Q3Server(
      VirtualFileSystem fs,
      String mapName,
      BspMap map,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock,
      GameAbi profile,
      CvarSystem cvars)
      throws IOException {
    this(fs, mapName, map, writes, output, clock, profile, cvars, (ExternalWorld) null);
  }

  public Q3Server(
      VirtualFileSystem fs,
      String mapName,
      ExternalWorld world,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock,
      GameAbi profile,
      CvarSystem cvars)
      throws IOException {
    this(fs, mapName, world.metadata(), writes, output, clock, profile, cvars, world);
  }

  /** Fresh local BSP match with an explicit carried base-game inventory. */
  public Q3Server(
      VirtualFileSystem fs,
      String mapName,
      BspMap map,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock,
      GameAbi profile,
      CvarSystem cvars,
      PlayerLoadout loadout)
      throws IOException {
    this(
        fs,
        mapName,
        MapLoadoutAdmission.create(map, loadout),
        writes,
        output,
        clock,
        profile,
        cvars);
  }

  private Q3Server(
      VirtualFileSystem fs,
      String mapName,
      MapLoadoutAdmission admission,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock,
      GameAbi profile,
      CvarSystem cvars)
      throws IOException {
    this(fs, mapName, admission.map(), writes, output, clock, profile, cvars, null, admission);
  }

  private Q3Server(
      VirtualFileSystem fs,
      String mapName,
      BspMap map,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock,
      GameAbi profile,
      CvarSystem cvars,
      ExternalWorld externalWorld)
      throws IOException {
    this(fs, mapName, map, writes, output, clock, profile, cvars, externalWorld, null);
  }

  private Q3Server(
      VirtualFileSystem fs,
      String mapName,
      BspMap map,
      WritableFiles writes,
      Consumer<String> output,
      Clock clock,
      GameAbi profile,
      CvarSystem cvars,
      ExternalWorld externalWorld,
      MapLoadoutAdmission admission)
      throws IOException {
    this.externalWorld = externalWorld;
    this.loadoutAdmission = admission;
    this.cvars = java.util.Objects.requireNonNull(cvars);
    if (!cvars.all().isEmpty() && !cvars.byHandle(0).name().equalsIgnoreCase("sv_cheats"))
      throw new IllegalArgumentException("Shared engine cvars must register sv_cheats first");
    this.fs = fs;
    this.writes = writes;
    this.output = output;
    this.clock = clock;
    files = new GameFileHandles(fs, writes);
    commands = new CommandSystem(cvars, fs, output);
    byte[] module = fs.read(new VirtualPath("vm/qagame.qvm"));
    abi = profile == null ? GameAbi.detect(module) : profile;
    vm = new QvmInterpreter(QvmReader.read("qagame", module), this::syscall);
    entities =
        new EntityWorld(
            map,
            vm.memory(),
            abi,
            externalWorld == null ? null : externalWorld.collision(),
            admission == null ? Map.of() : admission.damageModels());
    areas = new AreaConnectivity(map);
    snapshotVisibility = new SnapshotVisibility(map, areas);
    entityTokens = tokenizeEntities(map);
    botlib =
        new BotlibHost(
            fs,
            mapName,
            map,
            abi,
            new BotlibHost.Host() {

              @Override
              public int maxClients() {
                return maxClients;
              }

              @Override
              public void clientCommand(int client, String command) {
                checkBot(client);
                Q3Server.this.clientCommand(client, command);
              }

              @Override
              public void userCommand(int client, UserCommand command) {
                checkBot(client);
                Q3Server.this.userCommand(client, command);
              }

              @Override
              public int snapshotEntity(int client, int index) {
                checkConnected(client);
                if (index < 0 || index > SnapshotVisibility.MAX_ENTITIES)
                  throw new IllegalArgumentException("Invalid bot snapshot entity index");
                if (index == 0) botSnapshots.put(client, entitySnapshot(client).entities());
                var snapshot = botSnapshots.get(client);
                if (snapshot == null || index >= snapshot.size()) return -1;
                return java.nio.ByteBuffer.wrap(snapshot.get(index))
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .getInt();
              }

              @Override
              public String consoleMessage(int client) {
                checkConnected(client);
                for (var command : commandsSince(botReliableSequence[client])) {
                  botReliableSequence[client] = command.sequence();
                  if (command.client() == -1 || command.client() == client) return command.text();
                }
                return null;
              }

              @Override
              public TraceResult trace(TraceRequest request) {
                return entities.trace(request);
              }

              @Override
              public TraceResult entityTrace(int entity, TraceRequest request) {
                return entities.traceEntity(entity, request);
              }

              @Override
              public int pointContents(Vec3 point) {
                return entities.pointContents(point, -1);
              }
            },
            output);
    Arrays.fill(userInfo, "");
    Arrays.fill(userCommands, UserCommand.idle(0));
    // Q3's zero cvar handle is valid and names sv_cheats at engine startup. The original
    // bot diagnostics update zero-initialized vmCvars even when bot_enable is disabled.
    cvars.register("sv_cheats", "0", CvarSystem.SYSTEMINFO | CvarSystem.ROM);
    cvars.register("sv_maxclients", "8", CvarSystem.SERVERINFO | CvarSystem.LATCH);
    cvars.register("sv_running", "1", CvarSystem.ROM);
    cvars.set("sv_running", "1", CvarSystem.Source.ENGINE);
    cvars.register("sv_hostname", "CraftQ3", CvarSystem.SERVERINFO);
    cvars.register("sv_fps", "20", CvarSystem.SERVERINFO);
    cvars.register(
        "mapname",
        mapName.replace("maps/", "").replace(".bsp", ""),
        CvarSystem.SERVERINFO | CvarSystem.ROM);
    cvars.set(
        "mapname", mapName.replace("maps/", "").replace(".bsp", ""), CvarSystem.Source.ENGINE);
    cvars.register("dedicated", "0", CvarSystem.LATCH);
    cvars.register("g_gametype", "0", CvarSystem.SERVERINFO | CvarSystem.LATCH);
    cvars.register("bot_enable", "1", CvarSystem.ROM);
    if (writes == null) {
      cvars.register("g_log", "", 0);
      cvars.set("g_log", "", CvarSystem.Source.ENGINE);
    }
    commands.unknownHandler(
        command -> {
          if (state != State.RUNNING || invoke(9) == 0)
            output.accept("Unknown command: " + command.argument(0));
        });
  }

  public java.util.Optional<ExternalWorld> externalWorld() {
    return externalWorld == null
        ? java.util.Optional.empty()
        : java.util.Optional.of(
            new ExternalWorld(externalWorld.metadata(), entities.externalCollision()));
  }

  public CvarSystem cvars() {
    return cvars;
  }

  public CommandSystem commands() {
    return commands;
  }

  public BotlibHost.Status botlibStatus() {
    return botlib.status();
  }

  public ConfigStrings configstrings() {
    return configstrings;
  }

  public State state() {
    return state;
  }

  public int time() {
    return time;
  }

  public int frameNumber() {
    return frame;
  }

  /** Current engine command cursor; observers can start without replaying expired history. */
  public int currentServerCommandSequence() {
    return commandSequence;
  }

  public int snapshotFlags() {
    return snapshotFlags;
  }

  public int restartCount() {
    return restartCount;
  }

  /** Client capacity and game-type changes require a complete map spawn. */
  public boolean requiresMapReload() {
    return cvars.integer("g_gametype") != gameType
        || cvars.integer("sv_maxclients") != maxClients
        || java.util.stream.Stream.of("sv_maxclients", "g_gametype")
            .map(cvars::find)
            .flatMap(java.util.Optional::stream)
            .anyMatch(value -> value.latchedValue().isPresent());
  }

  /** Restarts original game code while keeping clients, configstrings and reliable sequences. */
  public void restart(int randomSeed) throws IOException {
    running();
    if (requiresMapReload())
      throw new IllegalStateException("Latched game type or client capacity requires map reload");
    for (int slot : java.util.Set.copyOf(externalActors)) removeExternalActor(slot);
    externalDamage.clear();
    Arrays.fill(externalDamageTimes, 0);
    try {
      invoke(1, 1);
      files.close();
      files = new GameFileHandles(fs, writes);
      entities.reset();
      areas.reset();
      Arrays.fill(snapshotOverflow, false);
      token = 0;
      vm.reset();
      snapshotFlags ^= 4; // SNAPFLAG_SERVERCOUNT tells cgame that prediction crosses a restart.
      invoke(0, time, randomSeed, 1);
      // Advance initialization frames before admitting clients, without consuming command buffers.
      for (int i = 0; i < 3; i++) settleRestartFrame();
      for (int client = 0; client < maxClients; client++) {
        if (!connected[client]) continue;
        sendCommand(client, "map_restart\n");
        int denied = invoke(2, client, 0, bots[client] ? 1 : 0);
        if (denied != 0)
          throw new IllegalStateException(
              "qagame rejected reconnect: " + text(vm.memory(), denied));
        if (entered[client]) invoke(3, client);
      }
      settleRestartFrame();
      updateServerInfo();
      restartCount++;
    } catch (IOException | RuntimeException failure) {
      state = State.FAILED;
      throw failure;
    }
  }

  private void settleRestartFrame() {
    invoke(8, time);
    time = Math.addExact(time, 100);
    frame++;
  }

  public int entityCount() {
    return entities.count();
  }

  public Map<Integer, Long> syscallCounts() {
    return Map.copyOf(calls);
  }

  public QvmInterpreter.Stats vmStats() {
    return vm.stats();
  }

  public GameAbi abi() {
    return abi;
  }

  public void initialize(int milliseconds, int randomSeed) {
    if (state != State.LOADED) throw new IllegalStateException("Server already initialized");
    Math.addExact(milliseconds, 400);
    time = milliseconds;
    botlib.seed(randomSeed);
    cvars.applyLatchedValues();
    maxClients = Math.clamp(cvars.integer("sv_maxclients"), 1, 64);
    gameType = cvars.integer("g_gametype");
    cvars.set("sv_maxclients", Integer.toString(maxClients), CvarSystem.Source.ENGINE);
    cvars.cheatsEnabled(cvars.integer("sv_cheats") != 0);
    output.accept(
        "CraftQ3 qagame: "
            + abi
            + " ABI; bots "
            + (cvars.integer("bot_enable") == 0 ? "disabled" : "experimental")
            + ".\n");
    invoke(0, milliseconds, randomSeed, 0);
    // Observed map startup runs four 100 ms settling frames before clients can enter.
    // Game simulation precedes bot initialization here, and queued commands stay pending.
    for (int i = 0; i < 4; i++) {
      invoke(8, time);
      if (cvars.integer("bot_enable") != 0) invoke(10, time);
      frame++;
      time = Math.addExact(time, 100);
    }
    state = State.RUNNING;
    updateServerInfo();
  }

  public void connect(int client, Map<String, String> info) {
    connectPending(client, info);
    begin(client, UserCommand.idle(time));
  }

  /** Reserve a human slot and ask original qagame to accept it before sending its gamestate. */
  public void connectPending(int client, Map<String, String> info) {
    connectPending(client, info, true);
  }

  public void connectPending(int client, Map<String, String> info, boolean firstTime) {
    running();
    checkClient(client);
    if (connected[client]) throw new IllegalStateException("Client already connected");
    userInfo[client] = InfoString.encode(info, 1024);
    userCommands[client] = UserCommand.idle(time);
    int denied = invoke(2, client, firstTime ? 1 : 0, 0);
    if (denied != 0)
      throw new IllegalStateException("qagame rejected client: " + text(vm.memory(), denied));
    connected[client] = true;
    entered[client] = false;
  }

  /** Enter only after the remote client has loaded the level and supplied its first usercmd. */
  public void begin(int client, UserCommand firstCommand) {
    running();
    checkConnected(client);
    if (entered[client]) throw new IllegalStateException("Client already entered the world");
    userCommands[client] = java.util.Objects.requireNonNull(firstCommand);
    invoke(3, client);
    entered[client] = connected[client];
    if (client == 0 && loadoutAdmission != null && !admitted) {
      restoreLoadout(loadoutAdmission.loadout());
      admitted = true;
    }
  }

  public boolean isConnected(int client) {
    checkClient(client);
    return connected[client];
  }

  public boolean hasEntered(int client) {
    checkClient(client);
    return entered[client];
  }

  public void disconnect(int client, String reason) {
    running();
    dropClient(client, java.util.Objects.requireNonNull(reason));
  }

  public void userCommand(int client, UserCommand command) {
    running();
    checkConnected(client);
    if (!entered[client]) throw new IllegalStateException("Client has not entered the world");
    userCommands[client] = command;
    int amount = client == 0 && !externalDamage.isEmpty() ? externalDamage.peekFirst() : 0;
    if (amount != 0 && time < externalDamageTimes[amount]) amount = 0;
    entities.armDamage(amount);
    try {
      invoke(7, client);
    } finally {
      entities.armDamage(0);
    }
    if (amount != 0) {
      externalDamage.removeFirst();
      externalDamageTimes[amount] = time + 100;
    }
  }

  /** Queue one external hit for original qagame armor, pain, death and respawn processing. */
  public void externalDamage(int amount) {
    running();
    if (externalWorld == null
        || amount < 1
        || amount > ExternalWorld.MAX_DAMAGE
        || externalDamage.size() >= 1024)
      throw new IllegalArgumentException("Invalid or excessive external damage");
    externalDamage.addLast(amount);
  }

  /** Restore a base-game checkpoint during explicit admission, before exposing snapshots. */
  public void restoreLoadout(PlayerLoadout loadout) {
    running();
    java.util.Objects.requireNonNull(loadout);
    if ((externalWorld == null
            && (loadoutAdmission == null
                || admitted
                || !loadoutAdmission.loadout().equals(loadout)))
        || !externalActors.isEmpty()
        || !externalDamage.isEmpty())
      throw new IllegalStateException("Loadout restoration requires fresh admission");
    checkConnected(0);
    int ps = entities.playerPointer(0, maxClients);
    var memory = vm.memory();
    if (memory.readInt(ps + 4) != 0
        || memory.readInt(ps + 184) <= 0
        || memory.readInt(ps + 208) != 100)
      throw new IllegalStateException("Unsupported loadout admission state");
    String cheats = cvars.string("sv_cheats");
    boolean enable = cvars.integer("sv_cheats") == 0;
    if (enable) {
      cvars.set("sv_cheats", "1", CvarSystem.Source.ENGINE);
      runFrame(time + 8);
    }
    try {
      if (loadout.holdable() != 0) {
        boolean found = false;
        for (String item : java.util.List.of("Personal Teleporter", "Medkit")) {
          memory.writeInt(ps + 188, 0);
          clientCommand(0, "give " + item);
          if (memory.readInt(ps + 188) == loadout.holdable()) {
            found = true;
            break;
          }
        }
        if (!found) throw new IllegalArgumentException("Unsupported base-game holdable item");
      }
      memory.writeInt(ps + 196, 0);
      for (int i = 0; i < 16; i++) memory.writeInt(ps + 312 + i * 4, 0);
      // Original give/pickup and hurt-trigger paths keep private entity health synchronized.
      clientCommand(0, "give health");
      if (loadout.health() > 100) clientCommand(0, "give Mega Health");
      runFrame(time + 8);
      int baseline = loadout.health() > 100 ? 200 : 100;
      if (memory.readInt(ps + 184) != baseline)
        throw new IllegalStateException("Original healing contract differs");
      if (baseline > loadout.health()) {
        externalDamage.addLast(baseline - loadout.health());
        userCommand(0, UserCommand.idle(time + 8));
        runFrame(time + 8);
      }
      if (memory.readInt(ps + 184) != loadout.health())
        throw new IllegalStateException("Original health restoration failed");
    } finally {
      if (enable) {
        cvars.set("sv_cheats", cheats, CvarSystem.Source.ENGINE);
        runFrame(time + 8);
      }
    }
    memory.writeInt(ps + 196, loadout.armor());
    memory.writeInt(ps + 192, loadout.weapons());
    memory.writeInt(ps + 188, loadout.holdable());
    memory.writeInt(ps + 144, loadout.weapon());
    memory.writeInt(ps + 148, 0);
    memory.writeInt(ps + 44, 0);
    for (int i = 0; i < 16; i++) memory.writeInt(ps + 376 + i * 4, loadout.ammo().get(i));
    for (int i = 1; i <= 6; i++) {
      int duration = loadout.powerupMillis().get(i - 1);
      memory.writeInt(ps + 312 + i * 4, duration == 0 ? 0 : Math.addExact(time, duration));
    }
  }

  /** Add a host impulse to the public player velocity; original QVM movement resolves it. */
  public void externalImpulse(Vec3 impulse) {
    running();
    java.util.Objects.requireNonNull(impulse);
    if (externalWorld == null) throw new IllegalStateException("No external world");
    checkConnected(0);
    if (Math.abs(impulse.x()) > 65536
        || Math.abs(impulse.y()) > 65536
        || Math.abs(impulse.z()) > 65536)
      throw new IllegalArgumentException("Excessive external impulse");
    int ps = entities.playerPointer(0, maxClients);
    if (vm.memory().readInt(ps + 184) <= 0) return;
    var velocity = VmAbi.vector(vm.memory(), ps + 32).add(impulse);
    if (Math.abs(velocity.x()) > 65536
        || Math.abs(velocity.y()) > 65536
        || Math.abs(velocity.z()) > 65536)
      throw new IllegalArgumentException("Excessive external velocity");
    VmAbi.vector(vm.memory(), ps + 32, velocity);
  }

  public void updateUserInfo(int client, Map<String, String> info) {
    running();
    checkConnected(client);
    String next = InfoString.encode(info, 1024);
    if (next.equals(userInfo[client])) return;
    userInfo[client] = next;
    invoke(4, client);
  }

  public void clientCommand(int client, String text) {
    running();
    checkConnected(client);
    var previous = context;
    context = CommandParser.tokenize(text);
    try {
      invoke(6, client);
    } finally {
      context = previous;
    }
  }

  /** Dispatches an already-framed host command without consuming either VM's command buffer. */
  public boolean consoleCommand(CommandParser.Command command) {
    running();
    var previous = context;
    context = java.util.Objects.requireNonNull(command);
    try {
      return invoke(9) != 0;
    } finally {
      context = previous;
    }
  }

  public void runFrame(int milliseconds) {
    running();
    if (milliseconds < time || (long) milliseconds - time > 1000)
      throw new IllegalArgumentException("Invalid server frame time");
    time = milliseconds;
    commands.runFrame(1024);
    if (cvars.integer("bot_enable") != 0) invoke(10, time);
    invoke(8, time);
    if (!externalDamage.isEmpty()
        && (!connected[0] || vm.memory().readInt(entities.playerPointer(0, maxClients) + 184) <= 0))
      externalDamage.clear();
    frame++;
    updateServerInfo();
  }

  /** Publish measured network latency for original qagame scoreboards and player state. */
  public void ping(int client, int milliseconds) {
    checkConnected(client);
    if (milliseconds < 0 || milliseconds > 999)
      throw new IllegalArgumentException("Invalid client ping");
    vm.memory()
        .writeInt(
            entities.playerPointer(client, maxClients) + (abi == GameAbi.RETAIL_1999 ? 440 : 452),
            milliseconds);
  }

  /** Mirrors a host-owned living actor into an original qagame client slot. */
  public void externalActor(int slot, BspMap.Bounds bounds, int health) {
    externalActor(slot, bounds, health, "Minecraft target " + slot);
  }

  public void externalActor(int slot, BspMap.Bounds bounds, int health, String hostName) {
    String name = ExternalActorName.clean(hostName);
    running();
    if (externalWorld == null
        || slot < 1
        || slot >= maxClients
        || health < 1
        || health > 100
        || bounds.min().x() >= bounds.max().x()
        || bounds.min().y() >= bounds.max().y()
        || bounds.min().z() >= bounds.max().z())
      throw new IllegalArgumentException("Invalid external actor");
    if (cvars.integer("sv_cheats") == 0)
      throw new IllegalStateException("External actor health synchronization requires sv_cheats");
    new dev.bluevista.craftq3.collision.TraceRequest(bounds.min(), bounds.max(), ZERO, ZERO, 0, -1);
    if (!externalActors.contains(slot)) {
      if (isConnected(slot)) throw new IllegalStateException("External actor slot is occupied");
      // Minecraft owns spawn placement. Suppress only admission-time overlap queries so the
      // temporary Quake spawn cannot telefrag existing actors before the host pose is installed.
      externalAdmission = true;
      try {
        connect(
            slot,
            Map.of("name", name, "model", "sarge/default", "ip", "localhost", "handicap", "100"));
      } finally {
        externalAdmission = false;
      }
      externalActors.add(slot);
    }
    var memory = vm.memory();
    int ps = entities.playerPointer(slot, maxClients), ent = entities.pointer(slot);
    Vec3 origin =
        new Vec3(
            (bounds.min().x() + bounds.max().x()) * .5,
            (bounds.min().y() + bounds.max().y()) * .5,
            (bounds.min().z() + bounds.max().z()) * .5);
    VmAbi.vector(memory, ps + 20, origin);
    VmAbi.vector(memory, ps + 32, ZERO);
    VmAbi.vector(memory, ent + abi.origin(), origin);
    VmAbi.vector(memory, ent + 24, origin);
    entities.externalBounds(slot, bounds);
    entities.link(ent);
    updateUserInfo(
        slot,
        Map.of(
            "name",
            name,
            "model",
            "sarge/default",
            "ip",
            "localhost",
            "handicap",
            Integer.toString(health)));
    clientCommand(slot, "give health");
    memory.writeInt(ps + 184, health);
  }

  public void removeExternalActor(int slot) {
    if (!externalActors.remove(slot)) return;
    entities.externalBounds(slot, null);
    if (isConnected(slot)) disconnect(slot, "Minecraft target removed");
  }

  /** Current original player body bounds in world coordinates, excluding broadphase padding. */
  public BspMap.Bounds playerBounds(int client) {
    checkConnected(client);
    int pointer = entities.pointer(client);
    var memory = vm.memory();
    var origin = VmAbi.vector(memory, pointer + abi.origin());
    return new BspMap.Bounds(
        VmAbi.vector(memory, pointer + abi.mins()).add(origin),
        VmAbi.vector(memory, pointer + abi.maxs()).add(origin));
  }

  public byte[] playerState(int client) {
    checkConnected(client);
    return abi.playerState(vm.memory(), entities.playerPointer(client, maxClients));
  }

  public List<byte[]> entityStates(int client) {
    return entitySnapshot(client).entities();
  }

  public EntitySnapshot entitySnapshot(int client) {
    checkConnected(client);
    var memory = vm.memory();
    int player = entities.playerPointer(client, maxClients);
    // These early player/entity fields are shared by the retail and 1.32 guest layouts.
    int playerEntity = memory.readInt(player + 140);
    Vec3 eye = VmAbi.vector(memory, player + 20).add(new Vec3(0, 0, memory.readInt(player + 164)));
    List<SnapshotVisibility.Candidate> candidates = new ArrayList<>();
    for (var entity : entities.linked()) {
      if (externalActors.contains(entity.number())) continue;
      if (memory.readInt(entity.pointer() + abi.linked()) == 0) continue;
      int flags = memory.readInt(entity.pointer() + abi.svFlags()),
          single =
              abi.singleClient() < 0 ? -1 : memory.readInt(entity.pointer() + abi.singleClient());
      candidates.add(
          new SnapshotVisibility.Candidate(
              entity.number(),
              entity.bounds(),
              flags,
              single,
              VmAbi.vector(memory, entity.pointer() + 92),
              VmAbi.vector(memory, entity.pointer() + 104),
              abi.entityStateBytes() > 204 ? memory.readInt(entity.pointer() + 204) : 0));
    }
    var selected = snapshotVisibility.select(eye, playerEntity, candidates);
    List<byte[]> result = new ArrayList<>();
    for (int number : selected.entities()) {
      byte[] state = abi.entityState(memory, entities.pointer(number));
      // The located entity index is authoritative, including temporarily stale guest s.number.
      java.nio.ByteBuffer.wrap(state).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(number);
      result.add(state);
    }
    boolean overflow = selected.omittedEntities() > 0;
    if (overflow && !snapshotOverflow[client])
      output.accept(
          "Snapshot entity limit for client "
              + client
              + ": "
              + selected.visibleEntities()
              + " visible, "
              + selected.omittedEntities()
              + " omitted (Q3 limit "
              + SnapshotVisibility.MAX_ENTITIES
              + ")\n");
    snapshotOverflow[client] = overflow;
    return new EntitySnapshot(
        result, selected.areaMask(), selected.visibleEntities(), selected.omittedEntities());
  }

  public List<ServerCommand> commandsSince(int sequence) {
    if (!serverCommands.isEmpty() && sequence < serverCommands.getFirst().sequence() - 1)
      throw new IllegalStateException("Local client fell behind reliable commands");
    return serverCommands.stream().filter(command -> command.sequence() > sequence).toList();
  }

  private int invoke(int command, int... args) {
    try {
      return vm.invoke(command, args);
    } catch (RuntimeException e) {
      state = State.FAILED;
      throw e;
    }
  }

  private int syscall(QvmMemory memory, int call, int[] a) {
    calls.merge(call, 1L, Long::sum);
    var intrinsic = VmIntrinsics.invoke(memory, call, a);
    if (intrinsic.isPresent()) return intrinsic.getAsInt();
    try {
      if (call >= 200) return botlib.invoke(memory, call, a);
      return switch (call) {
        case 0 -> {
          output.accept(text(memory, a[0]));
          yield 0;
        }
        case 1 -> throw new IllegalStateException("qagame error: " + text(memory, a[0]));
        case 2 -> time;
        case 3 -> {
          var variable = cvars.register(text(memory, a[1]), text(memory, a[2]), a[3]);
          VmAbi.cvar(memory, a[0], variable);
          yield 0;
        }
        case 4 -> {
          if (a[0] != 0) {
            int handle = memory.readInt(a[0]);
            try {
              VmAbi.cvar(memory, a[0], cvars.byHandle(handle));
            } catch (IllegalArgumentException e) {
              throw new IllegalArgumentException(
                  "Invalid qagame cvar handle " + handle + " at guest address " + a[0], e);
            }
          }
          yield 0;
        }
        case 5 -> {
          var changed = cvars.set(text(memory, a[0]), text(memory, a[1]), CvarSystem.Source.VM);
          if (changed == CvarSystem.Change.PROTECTED)
            throw new IllegalArgumentException("qagame attempted to modify a protected host cvar");
          yield 0;
        }
        case 6 -> cvars.integer(text(memory, a[0]));
        case 7 -> {
          VmAbi.string(memory, a[1], cvars.string(text(memory, a[0])), a[2]);
          yield 0;
        }
        case 8 -> commandContext().arguments().size();
        case 9 -> {
          VmAbi.string(memory, a[1], commandContext().argument(a[0]), a[2]);
          yield 0;
        }
        case 10 -> openFile(memory, a);
        case 11 -> {
          VmAbi.range(memory, a[0], a[1]);
          byte[] data = files.read(a[2], a[1]);
          memory.writeBytes(a[0], data);
          yield data.length;
        }
        case 12 -> files.write(a[2], memory.readBytes(a[0], a[1]));
        case 13 -> {
          files.close(a[0]);
          yield 0;
        }
        case 14 -> {
          if (a[0] < 0 || a[0] > 2)
            throw new IllegalArgumentException("Invalid console execution mode");
          commands.submit(text(memory, a[1]), CommandSystem.Execution.values()[a[0]]);
          yield 0;
        }
        case 15 -> {
          entities.locate(a[0], a[1], a[2], a[3], a[4], maxClients);
          yield 0;
        }
        case 16 -> {
          dropClient(a[0], text(memory, a[1]));
          yield 0;
        }
        case 17 -> {
          sendCommand(a[0], text(memory, a[1]));
          yield 0;
        }
        case 18 -> {
          configstrings.set(a[0], text(memory, a[1]));
          yield 0;
        }
        case 19 -> {
          VmAbi.string(memory, a[1], configstrings.get(a[0]), a[2]);
          yield 0;
        }
        case 20 -> {
          checkClient(a[0]);
          VmAbi.string(memory, a[1], userInfo[a[0]], a[2]);
          yield 0;
        }
        case 21 -> {
          checkClient(a[0]);
          String info = text(memory, a[1]);
          InfoString.parse(info, 1024);
          userInfo[a[0]] = info;
          yield 0;
        }
        case 22 -> {
          VmAbi.string(memory, a[0], cvars.infoString(CvarSystem.SERVERINFO, 1024), a[1]);
          yield 0;
        }
        case 23 -> {
          entities.brushModel(a[0], text(memory, a[1]));
          yield 0;
        }
        case 24 -> {
          var request =
              new TraceRequest(
                  VmAbi.vector(memory, a[1]),
                  VmAbi.vector(memory, a[4]),
                  a[2] == 0 ? ZERO : VmAbi.vector(memory, a[2]),
                  a[3] == 0 ? ZERO : VmAbi.vector(memory, a[3]),
                  a[6],
                  a[5]);
          VmAbi.trace(memory, a[0], entities.trace(request));
          yield 0;
        }
        case 25 -> entities.pointContents(VmAbi.vector(memory, a[0]), a[1]);
        case 26, 27 ->
            areas.visible(VmAbi.vector(memory, a[0]), VmAbi.vector(memory, a[1]), call == 27)
                ? 1
                : 0;
        case 28 -> {
          int number = entities.number(a[0]);
          var touched = entities.areas(a[0]);
          if (touched.size() == 2) areas.adjust(touched.get(0), touched.get(1), a[1] != 0);
          else if (touched.size() > 2)
            throw new IllegalStateException(
                "Area portal entity "
                    + number
                    + " touches areas "
                    + touched
                    + "; mins="
                    + VmAbi.vector(memory, a[0] + abi.absmin())
                    + "; maxs="
                    + VmAbi.vector(memory, a[0] + abi.absmax()));
          yield 0;
        }
        case 29 -> areas.connected(a[0], a[1]) ? 1 : 0;
        case 30 -> {
          entities.link(a[0]);
          yield 0;
        }
        case 31 -> {
          entities.unlink(a[0]);
          yield 0;
        }
        case 32 -> {
          var selected =
              externalAdmission
                  ? List.<Integer>of()
                  : entities.entitiesInBox(
                      VmAbi.vector(memory, a[0]), VmAbi.vector(memory, a[1]), a[3]);
          VmAbi.range(memory, a[2], Math.multiplyExact(a[3], 4));
          for (int i = 0; i < selected.size(); i++) memory.writeInt(a[2] + i * 4, selected.get(i));
          yield selected.size();
        }
        case 33 ->
            entities.contact(VmAbi.vector(memory, a[0]), VmAbi.vector(memory, a[1]), a[2]) ? 1 : 0;
        case 34 -> allocateBot();
        case 35 -> {
          freeBot(a[0]);
          yield 0;
        }
        case 36 -> {
          checkClient(a[0]);
          userCommands[a[0]].write(memory, a[1], abi);
          yield 0;
        }
        case 37 -> {
          boolean available = token < entityTokens.size();
          VmAbi.string(memory, a[0], available ? entityTokens.get(token++) : "", a[1]);
          yield available ? 1 : 0;
        }
        case 38 -> listFiles(memory, a);
        case 41 -> realTime(memory, a[0]);
        case 42 -> {
          for (int i = 0; i < 3; i++)
            VmAbi.floating(
                memory, a[0] + i * 4, (float) Math.rint(VmAbi.floating(memory, a[0] + i * 4)));
          yield 0;
        }
        case 45 -> {
          if (a[2] < 0 || a[2] > 2) throw new IllegalArgumentException("Invalid file seek mode");
          files.seek(a[0], a[1], GameFileHandles.Seek.values()[a[2]]);
          yield 0;
        }
        default ->
            throw new UnsupportedOperationException(
                "qagame syscall "
                    + call
                    + " is not implemented (botlib, capsules, and debug polygons remain pending)");
      };
    } catch (IOException e) {
      throw new IllegalStateException(
          "qagame filesystem syscall " + call + ": " + e.getMessage(), e);
    }
  }

  private int openFile(QvmMemory memory, int[] a) throws IOException {
    if (a[2] < 0 || a[2] > 3) throw new IllegalArgumentException("Invalid game file mode");
    var path = new VirtualPath(text(memory, a[0]));
    if (a[1] == 0) {
      if (a[2] != 0) throw new IllegalArgumentException("A file size query must use read mode");
      try {
        var opened = files.open(path, GameFileHandles.Mode.READ);
        files.close(opened.handle());
        return opened.length();
      } catch (NoSuchFileException e) {
        return -1;
      }
    }
    memory.writeInt(a[1], 0);
    try {
      var opened = files.open(path, GameFileHandles.Mode.values()[a[2]]);
      memory.writeInt(a[1], opened.handle());
      return opened.length();
    } catch (NoSuchFileException e) {
      return -1;
    }
  }

  private int listFiles(QvmMemory memory, int[] a) {
    String directory = text(memory, a[0]), extension = text(memory, a[1]);
    if (directory.equals("$modlist"))
      throw new UnsupportedOperationException(
          "QVM mod-directory enumeration is not implemented yet");
    String prefix = directory.isEmpty() ? "" : new VirtualPath(directory).value() + "/";
    var listed = fs.list(directory).stream().map(path -> path.value().substring(prefix.length()));
    List<String> names =
        extension.equals("/")
            ? listed
                .filter(name -> name.contains("/"))
                .map(name -> name.substring(0, name.indexOf('/')))
                .distinct()
                .sorted()
                .toList()
            : listed.filter(name -> !name.contains("/") && name.endsWith(extension)).toList();
    VmAbi.range(memory, a[2], a[3]);
    int used = 0, count = 0;
    for (String name : names) {
      if ((long) used + name.length() + 1 > a[3]) break;
      VmAbi.string(memory, a[2] + used, name, name.length() + 1);
      used += name.length() + 1;
      count++;
    }
    if (used < a[3]) memory.writeByte(a[2] + used, 0);
    return count;
  }

  private int realTime(QvmMemory memory, int address) {
    ZonedDateTime now = ZonedDateTime.now(clock);
    if (address != 0) {
      int[] fields = {
        now.getSecond(),
        now.getMinute(),
        now.getHour(),
        now.getDayOfMonth(),
        now.getMonthValue() - 1,
        now.getYear() - 1900,
        now.getDayOfWeek().getValue() % 7,
        now.getDayOfYear() - 1,
        now.getZone().getRules().isDaylightSavings(now.toInstant()) ? 1 : 0
      };
      VmAbi.range(memory, address, 36);
      for (int i = 0; i < fields.length; i++) memory.writeInt(address + i * 4, fields[i]);
    }
    return (int) now.toEpochSecond();
  }

  private int allocateBot() {
    for (int client = 0; client < maxClients; client++) {
      if (connected[client]) continue;
      connected[client] = true;
      entered[client] = true;
      bots[client] = true;
      userInfo[client] = "";
      userCommands[client] = UserCommand.idle(time);
      snapshotOverflow[client] = false;
      botReliableSequence[client] = commandSequence;
      botSnapshots.remove(client);
      if (client < entities.count()) {
        int flags = entities.pointer(client) + abi.svFlags();
        vm.memory().writeInt(flags, vm.memory().readInt(flags) | 8);
      }
      // Original qagame now performs its own ClientConnect and ClientBegin callbacks.
      return client;
    }
    return -1;
  }

  private void freeBot(int client) {
    checkClient(client);
    if (!bots[client]) {
      if (connected[client]) throw new IllegalArgumentException("Cannot free a human as a bot");
      return;
    }
    if (client < entities.count()) {
      entities.unlink(entities.pointer(client));
      int flags = entities.pointer(client) + abi.svFlags();
      vm.memory().writeInt(flags, vm.memory().readInt(flags) & ~8);
    }
    connected[client] = entered[client] = bots[client] = false;
    userInfo[client] = "";
    userCommands[client] = UserCommand.idle(time);
    botReliableSequence[client] = commandSequence;
    botSnapshots.remove(client);
    botlib.clearClient(client);
  }

  public boolean isBot(int client) {
    checkClient(client);
    return bots[client];
  }

  private void checkBot(int client) {
    checkConnected(client);
    if (!bots[client])
      throw new IllegalArgumentException("Bot service requires an allocated bot client");
  }

  private void dropClient(int client, String reason) {
    checkClient(client);
    if (connected[client]) {
      externalActors.remove(client);
      entities.externalBounds(client, null);
      invoke(5, client);
      if (bots[client]) freeBot(client);
      else connected[client] = entered[client] = false;
      output.accept("Q3 client " + client + " disconnected: " + reason);
    }
  }

  private void sendCommand(int client, String text) {
    if (client != -1) checkClient(client);
    if (text.length() >= 1024)
      throw new IllegalArgumentException("Server command exceeds Q3 limit");
    serverCommands.addLast(new ServerCommand(++commandSequence, client, text));
    if (serverCommands.size() > 256) serverCommands.removeFirst();
  }

  private void updateServerInfo() {
    configstrings.set(0, cvars.infoString(CvarSystem.SERVERINFO, 1024));
    configstrings.set(1, cvars.infoString(CvarSystem.SYSTEMINFO, 8192));
  }

  private CommandParser.Command commandContext() {
    return context != null ? context : commands.current();
  }

  private static String text(QvmMemory memory, int address) {
    return address == 0 ? "" : memory.readCString(address, 8192);
  }

  private void checkClient(int client) {
    if (client < 0 || client >= maxClients)
      throw new IllegalArgumentException("Invalid client " + client);
  }

  private void checkConnected(int client) {
    checkClient(client);
    if (!connected[client]) throw new IllegalStateException("Client is not connected");
  }

  private void running() {
    if (state != State.RUNNING) throw new IllegalStateException("Q3 server is " + state);
  }

  private static List<String> tokenizeEntities(BspMap map) {
    List<String> tokens = new ArrayList<>();
    for (var entity : map.entities()) {
      tokens.add("{");
      entity.forEach(
          (key, value) -> {
            tokens.add(key);
            tokens.add(value);
          });
      tokens.add("}");
    }
    return List.copyOf(tokens);
  }

  @Override
  public void close() throws IOException {
    if (state == State.CLOSED) return;
    try {
      if (state == State.RUNNING) invoke(1, 0);
    } finally {
      state = State.CLOSED;
      botlib.close();
      files.close();
    }
  }
}
