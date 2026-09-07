package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.client.input.Q3Input;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.*;
import dev.bluevista.craftq3.server.*;
import java.io.IOException;
import java.nio.*;
import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;

/** Original qagame/cgame loop over host terrain. It owns no Minecraft movement implementation. */
public final class BridgeGame implements AutoCloseable {
  private String quakeRequest;
  private final Pk3FileSystem fs;
  private final dev.bluevista.craftq3.core.fs.GameFileStore settings;
  private dev.bluevista.craftq3.client.input.GameConfig config;
  private boolean initialized, closed;
  private final Consumer<String> output;
  private final java.util.ArrayDeque<String> consoleLines = new java.util.ArrayDeque<>();
  private dev.bluevista.craftq3.client.EngineConsole console;
  private boolean exitRequested;
  private final AudioBackend audio;
  private final Q3Server server;
  private Q3Client client;
  private final Q3Input input;
  private final MinecraftTerrain terrain;
  private final RenderScene scene;
  private final MaterialLibrary materials;
  private int time, remaining;
  private String userInfo;
  private MinecraftCombat combat;
  private BridgeLoadoutStore loadouts;

  public BridgeGame(
      Pk3FileSystem fs,
      dev.bluevista.craftq3.core.fs.GameFileStore settings,
      MinecraftTerrain terrain,
      Vec3 spawn,
      float yaw,
      String name,
      java.util.UUID playerId,
      boolean fresh,
      PlayerLoadout arriving,
      java.util.List<dev.bluevista.craftq3.server.ExternalPickup> pickups,
      AudioBackend audio,
      int width,
      int height,
      Consumer<String> log)
      throws IOException {
    this.fs = fs;
    this.settings = settings;
    output = log;
    this.audio = audio;
    this.terrain = terrain;
    var cvars = new CvarSystem();
    cvars.register("sv_cheats", "1", CvarSystem.SYSTEMINFO | CvarSystem.ROM);
    cvars.register("bot_enable", "0", 0);
    cvars.register("sv_maxclients", "32", 0);
    cvars.register("fraglimit", "0", 0);
    cvars.register("timelimit", "0", 0);
    Map.of(
            "name",
            name,
            "model",
            "sarge/default",
            "headmodel",
            "sarge/default",
            "team_model",
            "sarge/default",
            "team_headmodel",
            "sarge/default")
        .forEach(
            (key, value) -> cvars.register(key, value, CvarSystem.ARCHIVE | CvarSystem.USERINFO));
    var external = ExternalWorld.combat(terrain, spawn, yaw, pickups);
    server =
        new Q3Server(
            fs, "craftq3_bridge", external, null, this::print, Clock.systemUTC(), null, cvars);
    try {
      terrain.beginFrame();
      server.initialize(1000, 42);
      userInfo = cvars.infoString(CvarSystem.USERINFO, 1024);
      server.connect(0, dev.bluevista.craftq3.core.cvar.InfoString.parse(userInfo, 1024));
      PlayerLoadout restored = arriving;
      if (settings != null) {
        loadouts =
            new BridgeLoadoutStore(
                settings,
                playerId,
                BridgeLoadoutStore.hash(
                    fs.read(new dev.bluevista.craftq3.core.fs.VirtualPath("vm/qagame.qvm"))));
        if (restored == null && !fresh) restored = loadouts.load().orElse(null);
      }
      if (restored != null) server.restoreLoadout(restored);
      client = new Q3Client(fs, server, frame -> {}, audio, this::print, null, server.commands());
      client.initialize(0, width, height);
      time = server.time();
      input = new Q3Input(cvars, server.commands(), time);
      var state = state();
      input.angles(
          state.getFloat(152) - state.getInt(56) * 360.0 / 65536,
          state.getFloat(156) - state.getInt(60) * 360.0 / 65536,
          0);
      scene = BspSceneBuilder.build("maps/craftq3_bridge.bsp", external.metadata(), 8);
      materials = MaterialLibrary.load(fs, scene);
      console = new dev.bluevista.craftq3.client.EngineConsole(fs, audio, this::print);
      server.commands().register("disconnect", command -> exitRequested = true);
      server
          .commands()
          .register(
              "quake",
              command -> {
                if (command.arguments().size() != 2) {
                  print("quake <map>: carry your live loadout into a Quake map");
                  return;
                }
                quakeRequest = command.argument(1);
                server.commands().frameBoundary();
              });
      config =
          new dev.bluevista.craftq3.client.input.GameConfig(
              server.commands(), input.bindings(), fs, settings, this::print);
      if (settings != null) {
        config.load();
        server.commands().runFrame(1024);
      }
      if (restored != null) {
        server
            .commands()
            .submit(
                "weapon " + restored.weapon(),
                dev.bluevista.craftq3.core.command.CommandSystem.Execution.APPEND);
        server.commands().runFrame(1024);
        print("Restored your Quake loadout.");
      }
      initialized = true;
      if (!pickups.isEmpty()) print("Loaded " + pickups.size() + " placed Quake pickups.");
      print(
          "Bridge console ready. give all supplies the original QVM loadout; disconnect returns to"
              + " Minecraft.");
    } catch (IOException | RuntimeException failure) {
      try {
        close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private synchronized void print(String text) {
    for (String line : text.split("[\r\n]+")) {
      if (line.isEmpty()) continue;
      consoleLines.addLast(line.length() > 2048 ? line.substring(0, 2048) : line);
      while (consoleLines.size() > 256) consoleLines.removeFirst();
    }
    output.accept(text);
  }

  public void command(String text) {
    print("] " + text);
    server
        .commands()
        .submit(text, dev.bluevista.craftq3.core.command.CommandSystem.Execution.APPEND);
  }

  public java.util.List<String> complete(String prefix) {
    var matches = server.commands().complete(prefix);
    if (matches.size() > 1) print(String.join("  ", matches));
    return matches;
  }

  public synchronized CgameFrame consoleFrame(
      String text, int cursor, int width, int height, int scroll) {
    return console.frame(
        java.util.List.copyOf(consoleLines), text, cursor, width, height, time, scroll);
  }

  public boolean exitRequested() {
    return exitRequested;
  }

  public CvarSystem cvars() {
    return server.cvars();
  }

  public int selectedWeapon() {
    return client.selectedWeapon();
  }

  public MinecraftCombat combat() {
    return combat;
  }

  public void combat(MinecraftCombat combat) {
    this.combat = combat;
  }

  public Q3Input input() {
    return input;
  }

  public Vec3 feet() {
    var ps = state();
    return terrain
        .transform()
        .toMinecraft(new Vec3(ps.getFloat(20), ps.getFloat(24), server.playerBounds(0).min().z()));
  }

  public BridgePlayerShape playerShape() {
    var ps = state();
    return BridgePlayerShape.from(
        server.playerBounds(0),
        new Vec3(ps.getFloat(20), ps.getFloat(24), ps.getFloat(28)),
        ps.getInt(164),
        terrain.transform().quakeUnitsPerBlock());
  }

  /** Player aim is independent of cgame's orbit/death camera direction. */
  public Vec3 viewAngles() {
    var ps = state();
    return new Vec3(ps.getFloat(152), ps.getFloat(156), ps.getFloat(160));
  }

  public String takeQuakeRequest() {
    String request = quakeRequest;
    quakeRequest = null;
    return request;
  }

  public byte[] gameModule() throws IOException {
    return fs.read(new dev.bluevista.craftq3.core.fs.VirtualPath("vm/qagame.qvm"));
  }

  public void transferMessage(String text) {
    print(text);
  }

  public PlayerLoadout loadout() {
    return PlayerLoadout.capture(server.playerState(0), server.time());
  }

  public AudioBackend.Diagnostics audioDiagnostics() {
    return audio.diagnostics();
  }

  public int health() {
    return state().getInt(184);
  }

  public int time() {
    return time;
  }

  public RenderScene scene() {
    return scene;
  }

  public MaterialLibrary materials() {
    return materials;
  }

  public CgameFrame frame(int elapsed, int width, int height) {
    terrain.beginFrame();
    String nextInfo = server.cvars().infoString(CvarSystem.USERINFO, 1024);
    if (!nextInfo.equals(userInfo)) {
      server.updateUserInfo(0, dev.bluevista.craftq3.core.cvar.InfoString.parse(nextInfo, 1024));
      userInfo = nextInfo;
    }
    if (combat != null) {
      var ps = state();
      var center =
          terrain
              .transform()
              .toMinecraft(new Vec3(ps.getFloat(20), ps.getFloat(24), ps.getFloat(28)));
      combat.pump(server, center);
    }
    remaining += Math.clamp(elapsed, 0, 200);
    while (remaining >= 8) {
      time += 8;
      remaining -= 8;
      client.userCommand(
          input.sample(
              time, client.selectedWeapon(), client.sensitivityScale(), state().getInt(56)));
      server.runFrame(time);
      if (combat != null) combat.afterFrame(server);
    }
    return client.frame(time, width, height);
  }

  private ByteBuffer state() {
    return ByteBuffer.wrap(server.playerState(0)).order(ByteOrder.LITTLE_ENDIAN);
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    if (initialized && settings != null) {
      try {
        config.save("q3config.cfg");
      } catch (IOException | RuntimeException error) {
        print("Could not save bridge settings: " + error.getMessage());
      }
    }
    if (initialized && loadouts != null) {
      try {
        var saved = health() > 0 ? loadout() : null;
        loadouts.save(saved);
        BridgeLoadoutSmoke.saved(saved);
      } catch (IOException | RuntimeException error) {
        print("Could not save bridge loadout: " + error.getMessage());
      }
    }
    for (AutoCloseable resource : new AutoCloseable[] {combat, client, server, audio, settings, fs})
      if (resource != null)
        try {
          resource.close();
        } catch (Exception e) {
          if (failure == null) failure = new IOException("Bridge shutdown", e);
          else failure.addSuppressed(e);
        }
    if (failure != null) throw failure;
  }
}
