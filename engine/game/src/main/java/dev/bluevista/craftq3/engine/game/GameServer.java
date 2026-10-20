package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandParser;
import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.ConfigSource;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.fs.PureManifest;
import dev.bluevista.craftq3.engine.game.bot.BotHost;
import dev.bluevista.craftq3.engine.game.bot.BotSupport;
import dev.bluevista.craftq3.engine.game.bot.NoBotlib;
import dev.bluevista.craftq3.engine.game.map.BspMapSource;
import dev.bluevista.craftq3.engine.game.map.EntityTokens;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.map.MapSource;
import dev.bluevista.craftq3.engine.game.snapshot.ClientSnapshot;
import dev.bluevista.craftq3.engine.game.snapshot.SnapshotBuilder;
import dev.bluevista.craftq3.engine.game.world.Contents;
import dev.bluevista.craftq3.engine.game.world.EntityWorld;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.game.world.MapVisibility;
import dev.bluevista.craftq3.engine.game.world.ModelCollision;
import dev.bluevista.craftq3.engine.game.world.WorldCollision;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.ConfigstringCommands;
import dev.bluevista.craftq3.engine.vm.QvmException;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.ConfigStrings;
import dev.bluevista.craftq3.engine.vmabi.VmExports.Qagame;
import dev.bluevista.craftq3.engine.vmabi.VmHost;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.VmTrapError;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import dev.bluevista.craftq3.engine.vmabi.service.CommandServices;
import dev.bluevista.craftq3.engine.vmabi.service.CvarServices;
import dev.bluevista.craftq3.engine.vmabi.service.SharedServices;
import dev.bluevista.craftq3.engine.vmabi.service.TimeServices;
import dev.bluevista.craftq3.engine.vmabi.service.VmFiles;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A Quake III server running the original qagame: map spawning with the settling frames, {@code
 * map_restart}, the fixed-step frame loop, configstrings, client slots, reliable server commands,
 * bots and server console commands ({@code sv_init.c}, {@code sv_main.c}, {@code sv_game.c}, {@code
 * sv_client.c}, {@code sv_ccmds.c}).
 *
 * <p><b>Threading.</b> Single-threaded: every method must be called on the thread that owns the
 * server's console ({@link #owner()}); other threads post console text through {@link
 * Console#inbox()}.
 *
 * <p><b>Time.</b> The owner drives the server with {@link #frame(long)} and an explicit clock; game
 * frames run in steps of {@code 1000 / sv_fps} ms with the remainder carried over.
 *
 * <p><b>Errors.</b> No exception escapes a public method. A VM fault or {@code trap_Error} drops
 * the level ({@link State#DROPPED}, {@link #dropReason()}); listeners are told so connections can
 * return to the menu. Map changes and restarts requested from inside a VM call run after it
 * unwinds.
 */
public final class GameServer implements BotHost, AutoCloseable {
  /** Server lifecycle. */
  public enum State {
    /** No level is loaded. */
    IDLE,
    /** A level is spawning (configstrings are not broadcast). */
    LOADING,
    RUNNING,
    /** The level was dropped by an error; see {@link #dropReason()}. */
    DROPPED,
    /** {@link #close()} was called. */
    CLOSED
  }

  /** Connection-layer callbacks, all on the simulation thread. */
  public interface Listener {
    /** A new level is running; connected network clients need its gamestate. */
    default void levelSpawned(GameServer server) {}

    /** {@code map_restart} completed with a new server id; clients keep their gamestate. */
    default void levelRestarted(GameServer server) {}

    /** The level was dropped or shut down; connections should be closed with {@code reason}. */
    default void levelEnded(GameServer server, String reason) {}

    /** Client {@code client} was dropped ({@code SV_DropClient}). */
    default void clientDropped(GameServer server, int client, String reason) {}
  }

  /** The result of {@link #connect}. */
  public record Admission(int client, String denied) {
    public boolean accepted() {
      return denied == null;
    }
  }

  /** {@code SNAPFLAG_NOT_ACTIVE}. */
  public static final int SNAPFLAG_NOT_ACTIVE = 2;

  /** {@code SNAPFLAG_SERVERCOUNT}: toggled at every spawn and restart. */
  public static final int SNAPFLAG_SERVERCOUNT = 4;

  /** {@code SNAPFLAG_RATE_DELAYED}. */
  public static final int SNAPFLAG_RATE_DELAYED = 1;

  /** Settling frames before clients are re-admitted, as the original spawn runs. */
  public static final int SETTLE_FRAMES = 3;

  /** Simulated milliseconds per settling frame. */
  public static final int SETTLE_MSEC = 100;

  /** Largest wall-clock step accepted by one {@link #frame} (a dedicated server's clamp). */
  public static final int MAX_FRAME_MSEC = 5000;

  /** {@code bot_failurePolicy}: what happens to a bot whose botlib calls fail. */
  public static final String BOT_FAILURE_POLICY = "bot_failurePolicy";

  /** {@code bot_failureLimit}: failures before the {@code kick} policy kicks a bot. */
  public static final String BOT_FAILURE_LIMIT = "bot_failureLimit";

  private static final Log LOG = Log.get(GameServer.class);

  private final Console console;
  private final CvarTable cvars;
  private final ServerOutput output;
  private final AssetLookup files;
  private final VmFiles vmFiles;
  private final MapSource maps;
  private final Function<GameMap, WorldCollision> collisionFactory;
  private final GameProgram.Loader programLoader;
  private final BotSupport bots;
  private final Clock clock;
  private final java.time.Clock wallClock;
  private final PureManifest pure;
  private final Random random;
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();
  private final ServerClient[] clients = new ServerClient[Protocol68.MAX_CLIENTS];
  private final ClientSnapshot[] botSnapshots = new ClientSnapshot[Protocol68.MAX_CLIENTS];
  private final Deque<Runnable> deferred = new ArrayDeque<>();

  private State state = State.IDLE;
  private String dropReason = "";
  private int maxClients;

  // Level.
  private GameMap map;
  private MapVisibility visibility;
  private EntityWorld world;
  private SnapshotBuilder snapshots;
  private EntityTokens tokens;
  private ConfigStrings configstrings = new ConfigStrings();
  private List<byte[]> baselines = List.of();
  private GameProgram program;
  private QvmInstance vm;
  private VmHost host;
  private SharedServices services;
  private GameEntities entities;

  // Time and identity.
  private int svsTime;
  private int svTime;
  private int timeResidual;
  private long lastFrameMillis = Long.MIN_VALUE;
  private int serverId;
  private int restartedServerId;
  private int checksumFeed;
  private int snapFlagServerBit;
  private int restartTime;
  private int restartCount;
  private long framesRun;
  private long pastDegradations;
  private boolean restarting;
  private int vmDepth;

  private GameServer(Builder builder) {
    this.console = Objects.requireNonNull(builder.console);
    this.cvars = console.cvars();
    this.output = builder.output;
    this.files = Objects.requireNonNull(builder.files, "files");
    this.vmFiles = Objects.requireNonNull(builder.vmFiles, "vmFiles");
    this.maps = builder.maps != null ? builder.maps : new BspMapSource(files);
    this.collisionFactory =
        builder.collision != null
            ? builder.collision
            : level -> new ModelCollision(CollisionModel.build(level.bsp()), level.sources());
    this.clock = builder.clock;
    this.wallClock = builder.wallClock;
    this.programLoader =
        builder.programLoader != null
            ? builder.programLoader
            : () -> QvmGameProgram.load(files, cvars.string(AbiProfile.OVERRIDE_CVAR), clock);
    this.bots = builder.bots != null ? builder.bots : new NoBotlib();
    this.pure = builder.pure;
    this.random = new Random(builder.seed);
    for (int i = 0; i < clients.length; i++) clients[i] = new ServerClient(i);
    registerCvars();
    ServerCommands.install(this, console);
    console
        .commands()
        .addFallback(
            args ->
                (state == State.RUNNING || state == State.LOADING)
                    && vm != null
                    && callGame(Qagame.GAME_CONSOLE_COMMAND.command()) != 0);
  }

  /** Configures a server. */
  public static Builder builder() {
    return new Builder();
  }

  /** {@link GameServer} configuration. */
  public static final class Builder {
    private Console console;
    private ServerOutput output;
    private AssetLookup files;
    private VmFiles vmFiles;
    private MapSource maps;
    private Function<GameMap, WorldCollision> collision;
    private GameProgram.Loader programLoader;
    private BotSupport bots;
    private Clock clock = Clock.system();
    private java.time.Clock wallClock = java.time.Clock.systemDefaultZone();
    private PureManifest pure;
    private long seed = System.nanoTime();
    private ConsoleOutput consoleOutput = ConsoleOutput.DISCARD;
    private ThreadOwner owner;
    private ConfigSource configSource = ConfigSource.NONE;
    private ConfigSink configSink = ConfigSink.NONE;

    private Builder() {}

    /** Where console text goes (default: discarded). */
    public Builder output(ConsoleOutput value) {
      consoleOutput = Objects.requireNonNull(value);
      return this;
    }

    /** The owning thread's marker (default: a new owner claimed by nobody). */
    public Builder owner(ThreadOwner value) {
      owner = Objects.requireNonNull(value);
      return this;
    }

    /** Script files for {@code exec} and the sink for {@code writeconfig}. */
    public Builder config(ConfigSource source, ConfigSink sink) {
      configSource = Objects.requireNonNull(source);
      configSink = Objects.requireNonNull(sink);
      return this;
    }

    /** The game's read-only files: maps, the qagame module, bot scripts. */
    public Builder files(AssetLookup value) {
      files = Objects.requireNonNull(value);
      return this;
    }

    /** The qagame's {@code trap_FS_*} handles. */
    public Builder vmFiles(VmFiles value) {
      vmFiles = Objects.requireNonNull(value);
      return this;
    }

    /** Where maps come from (default: {@link BspMapSource} over {@link #files}). */
    public Builder maps(MapSource value) {
      maps = Objects.requireNonNull(value);
      return this;
    }

    /**
     * Builds the world collision of a loaded map (default: {@code engine:collision} over its BSP
     * with the map's extra {@code CollisionSource}s composed into world traces).
     */
    public Builder collision(Function<GameMap, WorldCollision> value) {
      collision = Objects.requireNonNull(value);
      return this;
    }

    /** Loads the game module at each spawn (default: {@code vm/qagame.qvm} from the files). */
    public Builder program(GameProgram.Loader value) {
      programLoader = Objects.requireNonNull(value);
      return this;
    }

    /** Botlib (default: none, so the game disables its bots). */
    public Builder bots(BotSupport value) {
      bots = Objects.requireNonNull(value);
      return this;
    }

    /** {@code Sys_Milliseconds} and the time-of-day clock. */
    public Builder clocks(Clock value, java.time.Clock wall) {
      clock = Objects.requireNonNull(value);
      wallClock = Objects.requireNonNull(wall);
      return this;
    }

    /** The pure-server state of the search path, or none for an unrestricted server. */
    public Builder pure(PureManifest value) {
      pure = value;
      return this;
    }

    /** Seeds the random numbers the server draws (checksum feed, game seeds). */
    public Builder seed(long value) {
      seed = value;
      return this;
    }

    public GameServer build() {
      ThreadOwner threadOwner = owner != null ? owner : new ThreadOwner("game server");
      output = new ServerOutput(consoleOutput);
      console = Console.server(threadOwner, output, configSource, configSink);
      return new GameServer(this);
    }
  }

  private void registerCvars() {
    int si = CvarFlags.SERVERINFO, sy = CvarFlags.SYSTEMINFO, rom = CvarFlags.ROM;
    cvars.register("sv_fps", "20", CvarFlags.TEMP);
    cvars.register("sv_timeout", "200", CvarFlags.TEMP);
    cvars.register("sv_zombietime", "2", CvarFlags.TEMP);
    cvars.register("sv_maxclients", "8", si | CvarFlags.LATCH);
    cvars.register("sv_privateClients", "0", si);
    cvars.register("sv_hostname", "noname", si | CvarFlags.ARCHIVE);
    cvars.register("sv_privatePassword", "", CvarFlags.TEMP);
    cvars.register("rconPassword", "", CvarFlags.TEMP);
    cvars.register("g_gametype", "0", si | CvarFlags.LATCH);
    cvars.register("protocol", Integer.toString(Protocol68.PROTOCOL_VERSION), si | rom);
    cvars.register("mapname", "nomap", si | rom);
    cvars.register("sv_keywords", "", si);
    cvars.register("dmflags", "0", si);
    cvars.register("fraglimit", "20", si);
    cvars.register("timelimit", "0", si);
    cvars.register("sv_pure", "1", sy);
    cvars.register("sv_serverid", "0", sy | rom);
    cvars.register("sv_paks", "", sy | rom);
    cvars.register("sv_pakNames", "", sy | rom);
    cvars.register("sv_referencedPaks", "", sy | rom);
    cvars.register("sv_referencedPakNames", "", sy | rom);
    cvars.register("sv_mapChecksum", "", rom);
    cvars.register("sv_allowDownload", "0", si);
    cvars.register("sv_reconnectlimit", "3", 0);
    cvars.register("sv_floodProtect", "1", CvarFlags.ARCHIVE | si);
    cvars.register("sv_minRate", "0", CvarFlags.ARCHIVE | si);
    cvars.register("sv_maxRate", "0", CvarFlags.ARCHIVE | si);
    cvars.register("sv_minPing", "0", CvarFlags.ARCHIVE | si);
    cvars.register("sv_maxPing", "0", CvarFlags.ARCHIVE | si);
    cvars.register("sv_running", "0", rom);
    cvars.register("dedicated", "1", CvarFlags.LATCH);
    cvars.register("bot_enable", "1", CvarFlags.LATCH);
    cvars.register("nextmap", "", 0);
    cvars.register("sv_master1", "", 0);
    cvars.register(VmHost.Options.STRICT_CVAR, "0", 0);
    cvars.register(AbiProfile.OVERRIDE_CVAR, "auto", 0);
    cvars.register(BOT_FAILURE_POLICY, "degrade", 0);
    cvars.register(BOT_FAILURE_LIMIT, "8", 0);
  }

  // ---- accessors -------------------------------------------------------------------------------

  public ThreadOwner owner() {
    return console.owner();
  }

  public Console console() {
    return console;
  }

  public CvarTable cvars() {
    return cvars;
  }

  public State state() {
    return state;
  }

  /** Why the last level was dropped; empty otherwise. */
  public String dropReason() {
    return dropReason;
  }

  public void addListener(Listener listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  /** {@code sv.time}. */
  @Override
  public int levelTime() {
    return svTime;
  }

  /** {@code svs.time}: server time, monotonic across levels. */
  public int serverTime() {
    return svsTime;
  }

  public int serverId() {
    return serverId;
  }

  /** The oldest server id of this level that clients may still be using (before restarts). */
  public int restartedServerId() {
    return restartedServerId;
  }

  public int checksumFeed() {
    return checksumFeed;
  }

  /** {@code svs.snapFlagServerBit}. */
  public int snapFlags() {
    return snapFlagServerBit;
  }

  public int restartCount() {
    return restartCount;
  }

  /** Game frames run since construction. */
  public long framesRun() {
    return framesRun;
  }

  @Override
  public int maxClients() {
    return maxClients;
  }

  public ServerClient client(int number) {
    if (number < 0 || number >= clients.length)
      throw new IllegalArgumentException("Invalid client " + number);
    return clients[number];
  }

  /** The current level's map; throws when no level is loaded. */
  @Override
  public GameMap map() {
    if (map == null) throw new IllegalStateException("No level is loaded");
    return map;
  }

  /** The current level's map, if any. */
  public Optional<GameMap> currentMap() {
    return Optional.ofNullable(map);
  }

  @Override
  public EntityWorld world() {
    if (world == null) throw new IllegalStateException("No level is loaded");
    return world;
  }

  @Override
  public AssetLookup files() {
    return files;
  }

  public Optional<PureManifest> pure() {
    return Optional.ofNullable(pure);
  }

  BotSupport botSupport() {
    return bots;
  }

  /** The running module's ABI, if a level is loaded. */
  public Optional<AbiProfile> profile() {
    return program == null ? Optional.empty() : Optional.of(program.profile());
  }

  /** The syscall dispatcher of the running module (degradation counters), if any. */
  public Optional<VmHost> vmHost() {
    return Optional.ofNullable(host);
  }

  /**
   * Degraded and unknown syscalls of every game module this server has run (what the compatibility
   * sweep requires to be zero).
   */
  public long degradationTotal() {
    return pastDegradations + (host == null ? 0 : host.degradationTotal());
  }

  /** The game's entity arrays, once located. */
  public Optional<GameEntities> entities() {
    return entities == null || !entities.located() ? Optional.empty() : Optional.of(entities);
  }

  /** The configstring {@code index}. */
  public String configstring(int index) {
    return configstrings.get(index);
  }

  /** Every non-empty configstring. */
  public Map<Integer, String> configstrings() {
    return configstrings.snapshot();
  }

  /** The baselines captured after the settling frames (canonical entity states). */
  public List<byte[]> baselines() {
    return Collections.unmodifiableList(baselines);
  }

  ServerClient checkedClient(int number) {
    if (number < 0 || number >= maxClients)
      throw new IllegalArgumentException("Invalid client number " + number);
    return clients[number];
  }

  EntityTokens entityTokens() {
    return tokens;
  }

  // ---- guarded execution ----------------------------------------------------------------------

  /** Runs an outermost operation: catches every failure, drops the level, runs deferred work. */
  private boolean guarded(String what, Runnable action) {
    if (state == State.CLOSED) return false;
    owner().check();
    if (vmDepth > 0) {
      // Re-entered from inside a VM call: run in place, the outer call guards.
      action.run();
      return true;
    }
    try {
      action.run();
      runDeferred();
      return true;
    } catch (RuntimeException failure) {
      fail(what, failure);
      return false;
    }
  }

  private void runDeferred() {
    while (!deferred.isEmpty() && vmDepth == 0) deferred.removeFirst().run();
  }

  /** Runs {@code action} now when outside a VM call, otherwise after the outermost call. */
  private void outsideVm(Runnable action) {
    if (vmDepth == 0) action.run();
    else deferred.addLast(action);
  }

  private void fail(String what, RuntimeException failure) {
    String reason = describe(failure);
    LOG.warn("Level dropped during " + what + ": " + reason, failure);
    output.print("Server dropped: " + reason);
    endLevel(State.DROPPED, reason, false);
  }

  private static String describe(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof VmTrapError trap) return trap.guestMessage();
    return failure.getMessage() == null ? failure.toString() : failure.getMessage();
  }

  /** Calls {@code vmMain}; a fault propagates (the outermost {@link #guarded} drops the level). */
  int callGame(int command, int... args) {
    if (vm == null) throw new IllegalStateException("No game module is running");
    vmDepth++;
    try {
      return vm.vmMain(command, args);
    } finally {
      vmDepth--;
    }
  }

  /** True while a {@code vmMain} call is executing. */
  public boolean inGameCall() {
    return vmDepth > 0;
  }

  // ---- spawn, restart, shutdown ---------------------------------------------------------------

  /**
   * {@code map <name>}: loads and spawns a level (killing bots when {@code killBots}). Returns
   * false when the map cannot be loaded (the current level keeps running) or the spawn failed (the
   * level is {@link State#DROPPED}).
   */
  public boolean spawn(String mapName, boolean killBots) {
    if (vmDepth > 0) {
      deferred.addLast(() -> spawn(mapName, killBots));
      return true;
    }
    GameMap loaded;
    try {
      loaded = maps.load(mapName);
    } catch (IOException | RuntimeException failure) {
      output.print("Can't find map " + mapName + ": " + describe(failure));
      return false;
    }
    return guarded("spawn " + mapName, () -> spawnLevel(loaded, killBots))
        && state == State.RUNNING;
  }

  private void spawnLevel(GameMap loaded, boolean killBots) {
    output.print("------ Server Initialization ------");
    output.print("Server: " + loaded.name());
    shutdownGameProgs();
    snapFlagServerBit ^= SNAPFLAG_SERVERCOUNT;
    cvars.applyLatched();
    resizeClients(Math.clamp(cvars.integer("sv_maxclients"), 1, Protocol68.MAX_CLIENTS));
    cvars.set("sv_maxclients", Integer.toString(maxClients));
    cvars.set("nextmap", "map_restart 0");
    for (int i = 0; i < maxClients; i++) botSnapshots[i] = null;
    configstrings = new ConfigStrings();
    svTime = 0;
    timeResidual = 0;
    restartTime = 0;
    checksumFeed = random.nextInt();
    if (pure != null) pure.restart(checksumFeed);

    map = loaded;
    visibility = new MapVisibility(loaded.bsp());
    WorldCollision collision = collisionFactory.apply(loaded);
    world = new EntityWorld(visibility, collision);
    snapshots = new SnapshotBuilder(world);
    tokens = new EntityTokens(loaded.entityText());
    cvars.set("mapname", loaded.name());
    cvars.set("sv_mapChecksum", Integer.toString(loaded.checksum()));
    serverId = Math.max(serverId + 1, svsTime);
    restartedServerId = serverId;
    cvars.set("sv_serverid", Integer.toString(serverId));
    cvars.set("sv_running", "1");

    state = State.LOADING;
    bots.levelStarted();
    initGameProgs(false);
    for (int i = 0; i < SETTLE_FRAMES; i++) {
      callGame(Qagame.GAME_RUN_FRAME.command(), svTime);
      botFrame(svTime);
      svTime += SETTLE_MSEC;
      svsTime += SETTLE_MSEC;
    }
    createBaselines();
    for (int i = 0; i < maxClients; i++) {
      ServerClient client = clients[i];
      if (client.state.compareTo(ServerClient.State.CONNECTED) < 0) continue;
      boolean bot = client.bot;
      if (bot && killBots) {
        dropClient(i, "");
        continue;
      }
      String denied = gameClientConnect(i, false, bot);
      if (denied != null) {
        dropClient(i, denied);
        continue;
      }
      if (!bot) client.state = ServerClient.State.CONNECTED;
      else {
        client.state = ServerClient.State.ACTIVE;
        entities.setStateNumber(i, i);
        client.nextSnapshotTime = svsTime;
        callGame(Qagame.GAME_CLIENT_BEGIN.command(), i);
      }
    }
    callGame(Qagame.GAME_RUN_FRAME.command(), svTime);
    botFrame(svTime);
    svTime += SETTLE_MSEC;
    svsTime += SETTLE_MSEC;
    framesRun += SETTLE_FRAMES + 1;
    publishPaks();
    setConfigstring(
        ConfigStrings.CS_SYSTEMINFO, cvars.info(CvarFlags.SYSTEMINFO, CvarTable.BIG_INFO_CAPACITY));
    cvars.clearModifiedFlags(CvarFlags.SYSTEMINFO);
    setConfigstring(ConfigStrings.CS_SERVERINFO, cvars.info(CvarFlags.SERVERINFO));
    cvars.clearModifiedFlags(CvarFlags.SERVERINFO);
    state = State.RUNNING;
    dropReason = "";
    lastFrameMillis = Long.MIN_VALUE;
    output.print("-----------------------------------");
    for (Listener listener : listeners) listener.levelSpawned(this);
  }

  private void resizeClients(int count) {
    if (count < maxClients)
      for (int i = count; i < maxClients; i++)
        if (clients[i].state != ServerClient.State.FREE) {
          dropClient(i, "Server capacity changed");
          clients[i].reset();
        }
    maxClients = count;
  }

  private void publishPaks() {
    if (pure != null && cvars.integer("sv_pure") != 0) {
      cvars.set("sv_paks", pure.loadedPakChecksums());
      cvars.set("sv_pakNames", pure.loadedPakNames());
    } else {
      cvars.set("sv_paks", "");
      cvars.set("sv_pakNames", "");
    }
    cvars.set("sv_referencedPaks", pure == null ? "" : pure.referencedPakChecksums());
    cvars.set("sv_referencedPakNames", pure == null ? "" : pure.referencedPakNames());
  }

  /** {@code SV_InitGameProgs}: loads the module, binds its imports and calls {@code GAME_INIT}. */
  private void initGameProgs(boolean restart) {
    if (!restart || program == null) {
      try {
        program = programLoader.load();
      } catch (IOException failure) {
        throw new IllegalStateException(
            "Cannot load the game module: " + describe(failure), failure);
      }
    }
    VmHost.Options options = VmHost.Options.fromCvars(cvars);
    services =
        SharedServices.create(
            VmModule.QAGAME,
            output,
            new TimeServices(clock, wallClock),
            console.buffer(),
            CvarServices.WritePolicy.ORIGINAL_FORCED,
            vmFiles,
            args -> {});
    QagameHost bindings = new QagameHost(this, program.profile());
    if (host != null) pastDegradations += host.degradationTotal();
    host = bindings.build(services, options);
    vm = program.instantiate(host);
    vm.owner().claim();
    entities = new GameEntities(vm.memory(), program.profile());
    world.attach(entities);
    tokens.rewind();
    LOG.info(
        "qagame "
            + program.name()
            + " ("
            + program.profile()
            + " ABI) for "
            + map.name()
            + (restart ? " (restart)" : ""));
    callGame(Qagame.GAME_INIT.command(), svTime, random.nextInt() & 0x7fffffff, restart ? 1 : 0);
  }

  /** {@code SV_ShutdownGameProgs}. */
  private void shutdownGameProgs() {
    if (vm == null) return;
    try {
      if (!vm.faulted()) callGame(Qagame.GAME_SHUTDOWN.command(), 0);
    } catch (RuntimeException failure) {
      LOG.warn("qagame shutdown failed: " + describe(failure));
    } finally {
      releaseVm();
    }
  }

  private void releaseVm() {
    try {
      vmFiles.closeAll();
    } catch (IOException failure) {
      LOG.warn("Closing qagame files: " + failure.getMessage());
    }
    if (services != null) services.print().flush();
    if (host != null) pastDegradations += host.degradationTotal();
    vm = null;
    host = null;
    services = null;
    if (entities != null) entities.reset();
  }

  /** {@code SV_CreateBaseline}: the linked entities' states after the settling frames. */
  private void createBaselines() {
    List<byte[]> result = new ArrayList<>();
    if (entities.located())
      for (int e = 1; e < entities.count(); e++) {
        if (!entities.linked(e)) continue;
        entities.setStateNumber(e, e);
        result.add(entities.canonicalState(e));
      }
    baselines = List.copyOf(result);
  }

  /**
   * {@code map_restart [delay]}: with a delay (and no warmup) only announces the restart in {@code
   * CS_WARMUP}; otherwise restarts the game in place, keeping clients and configstrings, or
   * respawns the map when latched {@code sv_maxclients}/{@code g_gametype} changed.
   */
  public void mapRestart(int delaySeconds) {
    outsideVm(() -> guarded("map_restart", () -> restartLevel(delaySeconds)));
  }

  private static final int CS_WARMUP = 5;

  private void restartLevel(int delay) {
    if (state != State.RUNNING) {
      output.print("Server is not running.");
      return;
    }
    if (restartTime != 0) return;
    if (delay != 0 && cvars.integer("g_doWarmup") == 0) {
      restartTime = svTime + delay * 1000;
      setConfigstring(CS_WARMUP, Integer.toString(restartTime));
      return;
    }
    if (latchedChange("sv_maxclients") || latchedChange("g_gametype")) {
      output.print("variable change -- restarting.");
      String name = map.name();
      GameMap reload;
      try {
        reload = maps.load(name);
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot reload " + name + ": " + failure.getMessage());
      }
      spawnLevel(reload, false);
      return;
    }
    snapFlagServerBit ^= SNAPFLAG_SERVERCOUNT;
    serverId = Math.max(serverId + 1, svsTime);
    cvars.set("sv_serverid", Integer.toString(serverId));
    state = State.LOADING;
    restarting = true;
    try {
      callGame(Qagame.GAME_SHUTDOWN.command(), 1);
      try {
        vmFiles.closeAll();
      } catch (IOException failure) {
        LOG.warn("Closing qagame files: " + failure.getMessage());
      }
      // Divergence: the original keeps stale sector links and portal counts across the VM reset;
      // the reset game relinks everything it spawns, so starting from a clear world is equivalent
      // for live entities and avoids clipping against zeroed guest memory.
      world.clear();
      visibility.closeAllPortals();
      for (int i = 0; i < maxClients; i++) botSnapshots[i] = null;
      initGameProgs(true);
      for (int i = 0; i < SETTLE_FRAMES; i++) {
        callGame(Qagame.GAME_RUN_FRAME.command(), svTime);
        svTime += SETTLE_MSEC;
        svsTime += SETTLE_MSEC;
      }
      state = State.RUNNING;
      restarting = false;
      for (int i = 0; i < maxClients; i++) {
        ServerClient client = clients[i];
        if (client.state.compareTo(ServerClient.State.CONNECTED) < 0) continue;
        addServerCommand(client, "map_restart\n");
        String denied = gameClientConnect(i, false, client.bot);
        if (denied != null) {
          dropClient(i, denied);
          output.print("map_restart: dropped client " + i + " - denied!");
          continue;
        }
        if (client.state == ServerClient.State.ACTIVE) enterWorld(i, client.lastUsercmd);
      }
      callGame(Qagame.GAME_RUN_FRAME.command(), svTime);
      svTime += SETTLE_MSEC;
      svsTime += SETTLE_MSEC;
      framesRun += SETTLE_FRAMES + 1;
      restartCount++;
    } finally {
      restarting = false;
      if (state == State.LOADING) state = State.RUNNING;
    }
    for (Listener listener : listeners) listener.levelRestarted(this);
  }

  private boolean latchedChange(String name) {
    return cvars.find(name).flatMap(c -> c.latchedString()).isPresent();
  }

  /** {@code killserver}: ends the level and frees every client. */
  public void shutdown(String reason) {
    outsideVm(
        () -> {
          if (state == State.RUNNING || state == State.LOADING)
            guarded("shutdown", () -> endLevel(State.IDLE, reason, true));
          else if (state == State.DROPPED) state = State.IDLE;
        });
  }

  private void endLevel(State next, String reason, boolean graceful) {
    for (int i = 0; i < clients.length; i++) {
      ServerClient client = clients[i];
      if (client.state == ServerClient.State.FREE) continue;
      // The original's ERR_DROP leaves remote clients to time out; telling them is kinder and lets
      // a connected session return to its menu at once.
      if (client.state != ServerClient.State.ZOMBIE && !client.bot)
        addServerCommand(
            client,
            "disconnect \""
                + (graceful ? reason : "Server crashed: " + reason).replace('"', '\'')
                + "\"");
    }
    for (Listener listener : listeners) listener.levelEnded(this, reason);
    if (graceful) shutdownGameProgs();
    else releaseVm();
    for (ServerClient client : clients) client.reset();
    for (int i = 0; i < botSnapshots.length; i++) botSnapshots[i] = null;
    deferred.clear();
    state = next;
    dropReason = next == State.DROPPED ? reason : "";
    cvars.set("sv_running", "0");
    map = null;
    world = null;
    snapshots = null;
    tokens = null;
    program = null;
  }

  @Override
  public void close() {
    if (state == State.CLOSED) return;
    if (vmDepth == 0 && (state == State.RUNNING || state == State.LOADING))
      guarded("close", () -> endLevel(State.IDLE, "Server shutdown", true));
    bots.close();
    state = State.CLOSED;
  }

  // ---- frames ---------------------------------------------------------------------------------

  /**
   * Advances the server to {@code nowMillis} ({@code SV_Frame}): executes the console buffer,
   * publishes changed serverinfo/systeminfo, updates pings, runs the bot frame and as many {@code
   * sv_fps} game frames as the elapsed time allows, checks timeouts and builds bot snapshots.
   *
   * @return game frames run
   */
  public int frame(long nowMillis) {
    int[] ran = {0};
    guarded("frame", () -> ran[0] = runFrame(nowMillis));
    return ran[0];
  }

  private int runFrame(long now) {
    console.frame();
    if (state != State.RUNNING) {
      lastFrameMillis = now;
      return 0;
    }
    if (lastFrameMillis == Long.MIN_VALUE) lastFrameMillis = now;
    long elapsed = Math.clamp(now - lastFrameMillis, 0, MAX_FRAME_MSEC);
    lastFrameMillis = now;
    timeResidual += (int) elapsed;
    if (cvars.integer("sv_fps") < 1) cvars.set("sv_fps", "10");
    int frameMsec = Math.max(1, 1000 / cvars.integer("sv_fps"));
    if (restartTime != 0 && svTime >= restartTime) {
      restartTime = 0;
      console.buffer().add("map_restart 0\n");
      console.frame();
      if (state != State.RUNNING) return 0;
    }
    if (svsTime > 0x70000000) {
      output.print("Restarting server due to time wrapping");
      String name = map.name();
      svsTime = 0;
      spawn(name, false);
      return 0;
    }
    syncInfoStrings();
    calcPings();
    if (timeResidual < frameMsec) return 0;
    botFrame(svTime);
    int ran = 0;
    GameMap level = map;
    while (timeResidual >= frameMsec && state == State.RUNNING && map == level) {
      timeResidual -= frameMsec;
      svsTime += frameMsec;
      svTime += frameMsec;
      callGame(Qagame.GAME_RUN_FRAME.command(), svTime);
      ran++;
      framesRun++;
    }
    if (state != State.RUNNING) return ran;
    checkTimeouts();
    buildBotSnapshots();
    return ran;
  }

  private void botFrame(int time) {
    if (cvars.integer("bot_enable") == 0 || vm == null) return;
    callGame(Qagame.BOTAI_START_FRAME.command(), time);
  }

  private void syncInfoStrings() {
    if (cvars.takeModified(CvarFlags.SERVERINFO))
      setConfigstring(ConfigStrings.CS_SERVERINFO, cvars.info(CvarFlags.SERVERINFO));
    if (cvars.takeModified(CvarFlags.SYSTEMINFO))
      setConfigstring(
          ConfigStrings.CS_SYSTEMINFO,
          cvars.info(CvarFlags.SYSTEMINFO, CvarTable.BIG_INFO_CAPACITY));
  }

  /** {@code SV_CalcPings}: publishes each active human client's ping into its player state. */
  private void calcPings() {
    if (entities == null || !entities.located()) return;
    for (int i = 0; i < maxClients; i++) {
      ServerClient client = clients[i];
      if (client.state != ServerClient.State.ACTIVE) {
        client.ping = 999;
        continue;
      }
      if (client.bot || (entities.svFlags(i) & Contents.SVF_BOT) != 0) {
        client.ping = 0;
        continue;
      }
      entities.setPing(i, client.ping);
    }
  }

  /** {@code SV_CheckTimeouts}: drops silent network clients and frees expired zombies. */
  private void checkTimeouts() {
    int dropPoint = svsTime - 1000 * Math.max(1, cvars.integer("sv_timeout"));
    int zombiePoint = svsTime - 1000 * Math.max(0, cvars.integer("sv_zombietime"));
    for (int i = 0; i < maxClients; i++) {
      ServerClient client = clients[i];
      if (client.lastPacketTime > svsTime) client.lastPacketTime = svsTime;
      if (client.state == ServerClient.State.ZOMBIE && client.lastPacketTime < zombiePoint) {
        client.reset();
        continue;
      }
      if (client.bot || client.local) continue;
      if (client.state.compareTo(ServerClient.State.CONNECTED) >= 0
          && client.lastPacketTime < dropPoint) {
        if (++client.timeoutCount > 5) {
          sendServerCommandToAll("print \"" + client.name + " timed out\n\"");
          dropClient(i, "timed out");
          client.reset();
        }
      } else client.timeoutCount = 0;
    }
  }

  private void buildBotSnapshots() {
    if (entities == null || !entities.located()) return;
    for (int i = 0; i < maxClients; i++) {
      ServerClient client = clients[i];
      if (!client.bot || client.state != ServerClient.State.ACTIVE) continue;
      botSnapshots[i] = snapshots.build(i, svTime);
    }
  }

  /** Builds client {@code client}'s snapshot of the current frame. */
  public ClientSnapshot snapshot(int client) {
    if (snapshots == null || entities == null || !entities.located())
      throw new IllegalStateException("No level is running");
    checkedClient(client);
    return snapshots.build(client, svTime);
  }

  // ---- configstrings and commands ------------------------------------------------------------

  /** {@code SV_SetConfigstring}: stores and, while the level runs, broadcasts the change. */
  public void setConfigstring(int index, String value) {
    if (index < 0 || index >= ConfigStrings.MAX_STRINGS)
      throw new IllegalArgumentException("SV_SetConfigstring: bad index " + index);
    String text = value == null ? "" : value;
    if (configstrings.get(index).equals(text)) return;
    configstrings.set(index, text);
    if (state != State.RUNNING && !restarting) return;
    List<String> commands = ConfigstringCommands.outbound(index, text);
    for (int i = 0; i < maxClients; i++) {
      ServerClient client = clients[i];
      if (client.state.compareTo(ServerClient.State.PRIMED) < 0) continue;
      if (index == ConfigStrings.CS_SERVERINFO
          && entities != null
          && entities.located()
          && (entities.svFlags(i) & Contents.SVF_NOSERVERINFO) != 0) continue;
      for (String command : commands) addServerCommand(client, command);
    }
  }

  /** {@code SV_AddServerCommand}: appends to the client's reliable window; overflow drops it. */
  void addServerCommand(ServerClient client, String text) {
    client.reliableSequence++;
    if (client.reliableSequence - client.reliableAcknowledge == ServerClient.MAX_RELIABLE + 1) {
      output.print("===== pending server commands =====");
      for (int i = client.reliableAcknowledge + 1; i <= client.reliableSequence; i++) {
        String pending = client.reliable[i & (ServerClient.MAX_RELIABLE - 1)];
        output.print("cmd " + i + ": " + (pending == null ? "" : pending.strip()));
      }
      output.print("cmd " + client.reliableSequence + ": " + text.strip());
      dropClient(client.number(), "Server command overflow");
      return;
    }
    client.reliable[client.reliableSequence & (ServerClient.MAX_RELIABLE - 1)] = text;
  }

  /** {@code SV_SendServerCommand(NULL, ...)}: to every client at least primed. */
  public void sendServerCommandToAll(String text) {
    if (text.startsWith("print")) output.print("broadcast: " + text.replace("\n", "\\n"));
    for (int i = 0; i < maxClients; i++)
      if (clients[i].state.compareTo(ServerClient.State.PRIMED) >= 0)
        addServerCommand(clients[i], text);
  }

  /** {@code SV_SendServerCommand(client, ...)}. */
  public void sendServerCommand(int client, String text) {
    addServerCommand(checkedClient(client), text);
  }

  /** {@code trap_SendServerCommand}: -1 broadcasts, other invalid numbers are ignored. */
  void gameSendServerCommand(int client, String text) {
    if (text.length() >= Protocol68.MAX_STRING_CHARS - 1) {
      LOG.warnOnce("server-command-length", "Dropping an oversized game server command");
      return;
    }
    if (client == -1) sendServerCommandToAll(text);
    else if (client >= 0 && client < maxClients) addServerCommand(clients[client], text);
  }

  /** {@code trap_DropClient}. */
  void gameDropClient(int client, String reason) {
    if (client < 0 || client >= maxClients) return;
    dropClient(client, reason);
  }

  /** {@code trap_SetUserinfo}. */
  void setUserinfo(int client, String value) {
    ServerClient slot = checkedClient(client);
    slot.userinfo = value == null ? "" : value;
    slot.name = InfoText.valueForKey(slot.userinfo, "name");
  }

  /** {@code trap_LocateGameData}. */
  void locateGameData(
      int gEnts, int numGEntities, int sizeofGEntity, int clientsPtr, int sizeofClient) {
    entities.locate(gEnts, numGEntities, sizeofGEntity, clientsPtr, sizeofClient, maxClients);
  }

  // ---- clients --------------------------------------------------------------------------------

  /** {@code GAME_CLIENT_CONNECT}: null when accepted, else the game's reason. */
  private String gameClientConnect(int client, boolean firstTime, boolean bot) {
    int denied =
        callGame(Qagame.GAME_CLIENT_CONNECT.command(), client, firstTime ? 1 : 0, bot ? 1 : 0);
    if (denied == 0) return null;
    try {
      String reason = vm.memory().readString(denied, Protocol68.MAX_STRING_CHARS);
      return reason.isEmpty() ? "Connection refused" : reason;
    } catch (QvmException outside) {
      return "Connection refused";
    }
  }

  /**
   * Admits a network client ({@code SV_DirectConnect} after the challenge check): picks a slot
   * (from {@code sv_privateClients} unless the private password matches), asks the game and sets
   * the slot {@link ServerClient.State#CONNECTED}. {@code reuse} is the slot of the same address
   * and qport reconnecting, or -1.
   *
   * @param userinfo the client's userinfo (protocol, qport and challenge already removed)
   * @param address the address for the {@code ip} key ({@code localhost} for loopback)
   */
  public Admission connect(String userinfo, String address, boolean local, int reuse) {
    Admission[] result = {new Admission(-1, "Server is not running.")};
    guarded("connect", () -> result[0] = admit(userinfo, address, local, reuse));
    return result[0];
  }

  private Admission admit(String userinfo, String address, boolean local, int reuse) {
    if (state != State.RUNNING) return new Admission(-1, "Server is not running.");
    String info = InfoText.setValueForKey(userinfo, "ip", address, Protocol68.MAX_INFO_STRING);
    int slot = -1;
    if (reuse >= 0 && reuse < maxClients && clients[reuse].state != ServerClient.State.FREE) {
      if (clients[reuse].state != ServerClient.State.ZOMBIE)
        callGame(Qagame.GAME_CLIENT_DISCONNECT.command(), reuse);
      slot = reuse;
    } else {
      String password = InfoText.valueForKey(info, "password");
      String privatePassword = cvars.string("sv_privatePassword");
      int start =
          !privatePassword.isEmpty() && privatePassword.equals(password)
              ? 0
              : Math.clamp(cvars.integer("sv_privateClients"), 0, maxClients);
      for (int i = start; i < maxClients && slot < 0; i++)
        if (clients[i].state == ServerClient.State.FREE) slot = i;
      if (slot < 0 && local) {
        int botCount = 0;
        for (int i = start; i < maxClients; i++) if (clients[i].bot) botCount++;
        if (botCount == maxClients - start && maxClients > start) {
          slot = maxClients - 1;
          dropClient(slot, "only bots on server");
          clients[slot].reset();
        }
      }
      if (slot < 0) return new Admission(-1, "Server is full.");
    }
    ServerClient client = clients[slot];
    client.reset();
    client.local = local;
    client.address = address;
    client.userinfo = info;
    client.lastUsercmd = UserCmd.idle(0);
    String denied = gameClientConnect(slot, true, false);
    if (denied != null) {
      client.reset();
      return new Admission(-1, denied);
    }
    userinfoChanged(client, address);
    client.state = ServerClient.State.CONNECTED;
    client.nextSnapshotTime = svsTime;
    client.lastPacketTime = svsTime;
    client.lastConnectTime = svsTime;
    output.print("Client " + slot + " connecting: " + client.name);
    return new Admission(slot, null);
  }

  /** {@code SV_UserinfoChanged}: name, rate, handicap, snaps and the {@code ip} key. */
  private void userinfoChanged(ServerClient client, String address) {
    client.name = InfoText.valueForKey(client.userinfo, "name");
    if (client.local) client.rate = 99999;
    else {
      String rate = InfoText.valueForKey(client.userinfo, "rate");
      client.rate = rate.isEmpty() ? 3000 : Math.clamp(parseInt(rate), 1000, 90000);
    }
    String handicap = InfoText.valueForKey(client.userinfo, "handicap");
    if (!handicap.isEmpty()) {
      int value = parseInt(handicap);
      if (value <= 0 || value > 100 || handicap.length() > 4)
        client.userinfo =
            InfoText.setValueForKey(client.userinfo, "handicap", "100", Protocol68.MAX_INFO_STRING);
    }
    String snaps = InfoText.valueForKey(client.userinfo, "snaps");
    if (!snaps.isEmpty()) {
      int value = Math.clamp(parseInt(snaps), 1, Math.max(1, cvars.integer("sv_fps")));
      client.snapshotMsec = 1000 / value;
    } else client.snapshotMsec = 50;
    if (InfoText.valueForKey(client.userinfo, "ip").isEmpty() && address != null)
      client.userinfo =
          InfoText.setValueForKey(
              client.userinfo,
              "ip",
              client.local ? "localhost" : address,
              Protocol68.MAX_INFO_STRING);
  }

  private static int parseInt(String text) {
    int i = 0, sign = 1, value = 0;
    String t = text.strip();
    if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '+'))
      sign = t.charAt(i++) == '-' ? -1 : 1;
    for (; i < t.length() && Character.isDigit(t.charAt(i)); i++) {
      value = value * 10 + (t.charAt(i) - '0');
      if (value > 100_000_000) break;
    }
    return sign * value;
  }

  /** The gamestate has been queued for {@code client}; it enters on its first user command. */
  public void gamestateSent(int client) {
    ServerClient slot = checkedClient(client);
    if (slot.state == ServerClient.State.CONNECTED || slot.state == ServerClient.State.ACTIVE)
      slot.state = ServerClient.State.PRIMED;
  }

  /** Records that a packet from {@code client} arrived (timeouts). */
  public void packetReceived(int client) {
    checkedClient(client).lastPacketTime = svsTime;
  }

  /** The connection's measured round-trip time, published by {@code SV_CalcPings}. */
  public void setPing(int client, int milliseconds) {
    checkedClient(client).ping = Math.clamp(milliseconds, 0, 999);
  }

  /**
   * {@code SV_UserMove} after the connection's sequencing: a primed client enters the world with
   * the first command, then every command newer than the last runs {@code GAME_CLIENT_THINK}.
   */
  public void userMove(int client, List<UserCmd> commands) {
    if (commands.isEmpty()) return;
    guarded(
        "usercmd",
        () -> {
          ServerClient slot = checkedClient(client);
          if (state != State.RUNNING) return;
          if (slot.state == ServerClient.State.PRIMED) enterWorld(client, commands.getFirst());
          if (slot.state != ServerClient.State.ACTIVE) return;
          int last = commands.getLast().serverTime();
          for (UserCmd command : commands) {
            if (command.serverTime() > last) continue;
            if (command.serverTime() <= slot.lastUsercmd.serverTime()) continue;
            clientThink(client, command);
            if (slot.state != ServerClient.State.ACTIVE) return;
          }
        });
  }

  /** {@code SV_ClientEnterWorld}. */
  private void enterWorld(int client, UserCmd first) {
    ServerClient slot = clients[client];
    slot.state = ServerClient.State.ACTIVE;
    entities.setStateNumber(client, client);
    slot.nextSnapshotTime = svsTime;
    slot.lastUsercmd = first;
    callGame(Qagame.GAME_CLIENT_BEGIN.command(), client);
  }

  /** {@code SV_ClientThink}. */
  private void clientThink(int client, UserCmd command) {
    ServerClient slot = clients[client];
    slot.lastUsercmd = command;
    if (slot.state != ServerClient.State.ACTIVE) return;
    callGame(Qagame.GAME_CLIENT_THINK.command(), client);
  }

  /**
   * {@code SV_ExecuteClientCommand} for the server-level {@code userinfo} and {@code disconnect}
   * commands and everything the game handles. With {@code floodCheck}, game commands faster than
   * one per second are ignored ({@code sv_floodProtect}).
   */
  public void clientCommand(int client, String text, boolean floodCheck) {
    guarded("client command", () -> executeClientCommand(client, text, floodCheck));
  }

  private void executeClientCommand(int client, String text, boolean floodCheck) {
    ServerClient slot = checkedClient(client);
    if (slot.state == ServerClient.State.FREE || slot.state == ServerClient.State.ZOMBIE) return;
    CommandArgs args = CommandParser.tokenize(text);
    String name = args.argv(0).toLowerCase(Locale.ROOT);
    switch (name) {
      case "userinfo" -> {
        slot.userinfo = args.argv(1);
        userinfoChanged(slot, slot.address);
        if (state == State.RUNNING && vm != null)
          callGame(Qagame.GAME_CLIENT_USERINFO_CHANGED.command(), client);
        return;
      }
      case "disconnect" -> {
        dropClient(client, "disconnected");
        return;
      }
      default -> {}
    }
    boolean ok = true;
    if (floodCheck
        && slot.state == ServerClient.State.ACTIVE
        && cvars.integer("sv_floodProtect") != 0
        && svsTime < slot.nextReliableTime) ok = false;
    if (floodCheck) slot.nextReliableTime = svsTime + 1000;
    if (!ok || state != State.RUNNING || vm == null) return;
    CommandServices.ArgsScope scope = services.commands().withArgs(args);
    try {
      callGame(Qagame.GAME_CLIENT_COMMAND.command(), client);
    } finally {
      scope.close();
    }
  }

  /** Drops a client from outside the game ({@code kick}, a connection error, a timeout). */
  public void drop(int client, String reason) {
    guarded("drop", () -> dropClient(client, reason));
  }

  /** {@code SV_DropClient}. */
  void dropClient(int client, String reason) {
    ServerClient slot = clients[client];
    if (slot.state == ServerClient.State.ZOMBIE || slot.state == ServerClient.State.FREE) return;
    boolean bot = slot.bot;
    sendServerCommandToAll("print \"" + slot.name + "^7 " + reason + "\n\"");
    if (vm != null && !vm.faulted()) callGame(Qagame.GAME_CLIENT_DISCONNECT.command(), client);
    addServerCommand(slot, "disconnect \"" + reason + "\"");
    if (bot) freeBot(client);
    slot.userinfo = "";
    slot.name = "";
    if (bot) slot.reset();
    else {
      slot.state = ServerClient.State.ZOMBIE;
      slot.lastPacketTime = svsTime;
    }
    for (Listener listener : listeners) listener.clientDropped(this, client, reason);
  }

  // ---- bots -----------------------------------------------------------------------------------

  /** {@code SV_BotAllocateClient}: the first free slot, active at once, or -1. */
  int botAllocateClient() {
    for (int i = 0; i < maxClients; i++) {
      ServerClient slot = clients[i];
      if (slot.state != ServerClient.State.FREE) continue;
      slot.reset();
      slot.bot = true;
      slot.state = ServerClient.State.ACTIVE;
      slot.lastPacketTime = svsTime;
      slot.rate = 16384;
      slot.snapshotMsec = 0;
      if (entities != null && entities.located()) entities.setStateNumber(i, i);
      botSnapshots[i] = null;
      return i;
    }
    return -1;
  }

  /** {@code SV_BotFreeClient}. */
  void botFreeClient(int client) {
    if (client < 0 || client >= maxClients)
      throw new IllegalArgumentException("SV_BotFreeClient: bad clientNum " + client);
    freeBot(client);
    clients[client].reset();
  }

  private void freeBot(int client) {
    ServerClient slot = clients[client];
    slot.state = ServerClient.State.FREE;
    slot.name = "";
    if (entities != null && entities.located())
      entities.setSvFlags(client, entities.svFlags(client) & ~Contents.SVF_BOT);
    botSnapshots[client] = null;
    bots.clientFreed(client);
  }

  @Override
  public void botClientCommand(int client, String command) {
    executeClientCommand(client, command, false);
  }

  @Override
  public void botUserCommand(int client, UserCmd command) {
    if (client < 0 || client >= maxClients) return;
    clientThink(client, command);
  }

  @Override
  public int snapshotEntity(int client, int index) {
    ClientSnapshot snapshot = botSnapshots[checkedClient(client).number()];
    return snapshot == null ? -1 : snapshot.entityNumber(index);
  }

  @Override
  public String consoleMessage(int client) {
    ServerClient slot = checkedClient(client);
    slot.lastPacketTime = svsTime;
    if (slot.reliableAcknowledge == slot.reliableSequence) return null;
    slot.reliableAcknowledge++;
    String text = slot.reliable[slot.reliableAcknowledge & (ServerClient.MAX_RELIABLE - 1)];
    return text == null || text.isEmpty() ? null : text;
  }

  @Override
  public void botFailed(int client, RuntimeException failure) {
    if (client < 0 || client >= maxClients) return;
    ServerClient slot = clients[client];
    if (!slot.bot) return;
    slot.botDegraded = true;
    slot.botFailures++;
    LOG.warnOnce(
        "bot-failure:" + client,
        "Botlib failed for bot " + client + " (" + slot.name + "): " + describe(failure));
    if ("kick".equalsIgnoreCase(cvars.string(BOT_FAILURE_POLICY))
        && slot.botFailures == Math.max(1, cvars.integer(BOT_FAILURE_LIMIT)))
      deferred.addLast(() -> dropClient(client, "was kicked (botlib failures)"));
  }

  @Override
  public void print(String text) {
    for (String line : text.split("\n", -1)) if (!line.isEmpty()) output.print(line);
  }

  // ---- console --------------------------------------------------------------------------------

  /** Executes console text now (with the command buffer), as an operator would type it. */
  public void execute(String text) {
    guarded(
        "console",
        () -> {
          console.buffer().add(text.endsWith("\n") ? text : text + "\n");
          console.buffer().execute();
        });
  }

  /** Runs {@code text} as one command with output captured ({@code rcon}). */
  public String executeRedirected(String text) {
    output.beginRedirect();
    try {
      guarded("rcon", () -> console.commands().execute(text));
    } catch (RuntimeException unexpected) {
      output.print("Command failed: " + describe(unexpected));
    }
    return output.endRedirect();
  }

  ServerOutput output() {
    return output;
  }

  /** The client whose name matches (colour codes and case ignored), or -1. */
  public int clientByName(String name) {
    String wanted = cleanName(name);
    for (int i = 0; i < maxClients; i++) {
      ServerClient slot = clients[i];
      if (slot.state == ServerClient.State.FREE) continue;
      if (slot.name.equalsIgnoreCase(name) || cleanName(slot.name).equalsIgnoreCase(wanted))
        return i;
    }
    return -1;
  }

  static String cleanName(String name) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '^' && i + 1 < name.length() && name.charAt(i + 1) != '^') {
        i++;
        continue;
      }
      if (c >= 32 && c < 127) out.append(c);
    }
    return out.toString();
  }

  /** Reads client {@code client}'s {@code persistant[PERS_SCORE]}. */
  public int score(int client) {
    return entities.playerInt(client, PlayerStateLayout.PERSISTANT);
  }
}
