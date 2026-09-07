package dev.bluevista.craftq3.fabric.game;

import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.client.DemoCgameSource;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.client.RemoteCgameSource;
import dev.bluevista.craftq3.client.RemoteServerClock;
import dev.bluevista.craftq3.client.RemoteSystemInfo;
import dev.bluevista.craftq3.client.UiBrowser;
import dev.bluevista.craftq3.client.UiHost;
import dev.bluevista.craftq3.client.demo.DemoCatalog;
import dev.bluevista.craftq3.client.demo.DemoPlayer;
import dev.bluevista.craftq3.client.demo.LocalDemoFeed;
import dev.bluevista.craftq3.client.demo.Protocol68DemoRecorder;
import dev.bluevista.craftq3.client.input.GameConfig;
import dev.bluevista.craftq3.client.input.Q3Input;
import dev.bluevista.craftq3.client.net.RemoteAddress;
import dev.bluevista.craftq3.client.net.RemoteConnection;
import dev.bluevista.craftq3.client.net.ServerBrowser;
import dev.bluevista.craftq3.client.video.Cinematics;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.fs.DemoFileStore;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.BspSceneBuilder;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.render.MaterialLibrary;
import dev.bluevista.craftq3.render.RenderScene;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Original UI and local or remote game, retaining engine settings, input and audio across maps. */
public final class QuakeSession implements AutoCloseable {
  private final Pk3FileSystem fs;
  private final GameFileStore savedFiles;
  private final AudioBackend audio;
  private final DemoFileStore demoFiles;
  private final dev.bluevista.craftq3.assets.fs.DownloadCache downloadCache;
  private final dev.bluevista.craftq3.client.net.Pk3Downloads downloads;
  private dev.bluevista.craftq3.server.net.Protocol68Host host;
  private dev.bluevista.craftq3.platform.net.DatagramListener listener;
  private DemoPlayer demo;
  private DemoCgameSource demoSource;
  private boolean demoContentView;
  private Protocol68DemoRecorder recording;
  private DemoFileStore.AtomicOutputStream recordingFile;
  private LocalDemoFeed localRecording;
  private final Cinematics cinematics;
  private String cinematicRequest;
  private int cinematic = -1, cinematicFlags;
  private boolean cinematicSkip, cinematicFailure;
  private String demoRequest, recordRequest, demoName = "", recordingName = "";
  private boolean stopRecordRequested;
  private int demoGeneration, demoInputBase;
  private long demoEpoch;
  private static final RenderScene MENU_SCENE =
      new RenderScene("ui", List.of(), new RenderScene.Camera(new Vec3(0, 0, 0), 0, 0, 90));
  private final CvarSystem cvars = new CvarSystem();
  private final CommandSystem commands;
  private final ServerBrowser browser;
  private MaterialLibrary menuMaterials;
  private RenderScene world = MENU_SCENE;
  private MaterialLibrary materials;
  private int menuCatcher, generation;
  private MapRequest mapRequest;
  private boolean disconnectRequested, bridgeRequested;
  private String errorMessage = "";
  private String connectRequest, remoteAddress = "", remoteMessage = "";
  private CompletableFuture<InetSocketAddress> resolution;
  private Thread resolver;
  private RemoteConnection remote;
  private RemoteCgameSource remoteSource;
  private final RemoteServerClock remoteClock = new RemoteServerClock();
  private long remoteEpoch;
  private int remoteGeneration, remoteInputBase, connectPackets;
  private boolean remoteAnglesSeeded, remoteContentView, remotePure;
  private List<Integer> remotePakChecksums = List.of();
  private int presentationWidth, presentationHeight;

  private record MapRequest(
      String name, boolean cheats, boolean singlePlayer, int due, boolean restart) {}

  private final ArrayDeque<String> console = new ArrayDeque<>();
  private Q3Server server;
  private dev.bluevista.craftq3.server.PlayerLoadout arrivingLoadout;
  private Q3Client client;
  private Q3Ui ui;
  private dev.bluevista.craftq3.client.EngineConsole engineConsole;
  private CgameFrame uiFrame;
  private Q3Input input;
  private GameConfig config;
  private String userInfo;
  private int time = 1000, uiTime;
  private boolean closed, initialized, exitRequested;

  public QuakeSession(
      Pk3FileSystem fs,
      GameFileStore savedFiles,
      AudioBackend audio,
      RenderScene world,
      MaterialLibrary materials,
      String playerName,
      int width,
      int height)
      throws IOException {
    this(fs, savedFiles, audio, world, materials, playerName, width, height, null);
  }

  public QuakeSession(
      Pk3FileSystem fs,
      GameFileStore savedFiles,
      AudioBackend audio,
      RenderScene world,
      MaterialLibrary materials,
      String playerName,
      int width,
      int height,
      DemoFileStore demoFiles)
      throws IOException {
    this(fs, savedFiles, audio, world, materials, playerName, width, height, demoFiles, null);
  }

  public QuakeSession(
      Pk3FileSystem fs,
      GameFileStore savedFiles,
      AudioBackend audio,
      RenderScene world,
      MaterialLibrary materials,
      String playerName,
      int width,
      int height,
      DemoFileStore demoFiles,
      dev.bluevista.craftq3.assets.fs.DownloadCache downloadCache)
      throws IOException {
    this(
        fs,
        savedFiles,
        audio,
        world,
        materials,
        playerName,
        width,
        height,
        demoFiles,
        downloadCache,
        null,
        null);
  }

  public QuakeSession(
      Pk3FileSystem fs,
      GameFileStore savedFiles,
      AudioBackend audio,
      RenderScene world,
      MaterialLibrary materials,
      String playerName,
      int width,
      int height,
      DemoFileStore demoFiles,
      dev.bluevista.craftq3.assets.fs.DownloadCache downloadCache,
      dev.bluevista.craftq3.server.PlayerLoadout arriving,
      byte[] expectedModule)
      throws IOException {
    this.downloadCache = downloadCache;
    if (downloadCache != null) downloadCache.mountCached(fs);
    if (arriving != null
        && (world == null
            || !java.util.Arrays.equals(fs.read(new VirtualPath("vm/qagame.qvm")), expectedModule)))
      throw new IllegalArgumentException(
          "Carried loadout requires a map and the same qagame module.");
    arrivingLoadout = arriving;
    this.demoFiles = demoFiles;
    this.fs = fs;
    this.savedFiles = savedFiles;
    this.audio = audio;
    cinematics =
        new Cinematics(
            fs,
            audio,
            System::nanoTime,
            message -> {
              cinematicFailure = true;
              print(message);
            });
    presentationWidth = width;
    presentationHeight = height;
    cvars.register("sv_cheats", "0", CvarSystem.SYSTEMINFO | CvarSystem.ROM);
    this.downloads = new dev.bluevista.craftq3.client.net.Pk3Downloads(fs, downloadCache, cvars);
    cvars.register("sv_running", "0", CvarSystem.ROM);
    cvars.register("net_enabled", "1", CvarSystem.ARCHIVE);
    cvars.register("net_ip", "0.0.0.0", CvarSystem.ARCHIVE);
    cvars.register("net_port", "27960", CvarSystem.ARCHIVE);
    cvars.register("dedicated", "0", CvarSystem.LATCH);
    cvars.register("sv_pure", "1", CvarSystem.SYSTEMINFO);
    cvars.register("sv_hostname", "CraftQ3", CvarSystem.SERVERINFO);
    cvars.register("sv_maxclients", "8", CvarSystem.SERVERINFO | CvarSystem.LATCH);
    cvars.register("sv_fps", "20", CvarSystem.SERVERINFO);
    cvars.register("timescale", "1", CvarSystem.SYSTEMINFO | CvarSystem.CHEAT);
    cvars.register("com_cameraMode", "0", CvarSystem.CHEAT);
    cvars.register("cl_freezeDemo", "0", CvarSystem.TEMP);
    cvars.register("timedemo", "0", 0);
    cvars.register("cl_demoPlaying", "0", CvarSystem.ROM);
    cvars.register("cl_demoRecording", "0", CvarSystem.ROM);
    cvars.register("cl_demoName", "", CvarSystem.ROM);
    cvars.register("cl_cinematic", "", CvarSystem.ROM);
    cvars.register("nextmap", "", 0);
    commands = new CommandSystem(cvars, fs, this::print);
    browser = new ServerBrowser(cvars, savedFiles, this::print);
    input = new Q3Input(cvars, commands, time);
    Map.of(
            "name",
            playerName,
            "model",
            "sarge",
            "headmodel",
            "sarge",
            "team_model",
            "sarge",
            "team_headmodel",
            "sarge",
            "rate",
            "25000",
            "snaps",
            "20",
            "handicap",
            "100",
            "color1",
            "4",
            "color2",
            "5")
        .forEach(
            (name, value) -> cvars.register(name, value, CvarSystem.ARCHIVE | CvarSystem.USERINFO));
    installEngineCommands(commands);
    disconnectedCommands();
    config = new GameConfig(commands, input.bindings(), fs, savedFiles, this::print);
    try {
      menuMaterials = MaterialLibrary.load(fs, MENU_SCENE);
      this.materials = menuMaterials;
      if (!GameplaySmoke.captureEnabled() && !Boolean.getBoolean("craftq3.lifecycleCapture")) {
        config.load();
        commands.runFrame(1024);
      }
      if (world != null) startGame(world, materials, false, false, width, height);
      ui = createUi();
      engineConsole = new dev.bluevista.craftq3.client.EngineConsole(fs, audio, this::print);
      ui.initialize(width, height);
      routeCommands();
      if (client == null) ui.setMenu(Q3Ui.Menu.MAIN);
      initialized = true;
    } catch (IOException | RuntimeException e) {
      try {
        close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  private Q3Ui createUi() throws IOException {
    return new Q3Ui(
        fs,
        cvars,
        commands,
        input.bindings(),
        audio,
        frame -> {},
        this::print,
        new UiHost() {
          public UiBrowser browser() {
            return browser;
          }

          public List<String> demoFiles() {
            return ownedDemoNames();
          }

          public ClientState clientState() {
            if (cinematicPlaying()) return new ClientState(9, 0, -1, "", "", "");
            if (demo != null)
              return new ClientState(
                  demo.clock().state().active() ? 8 : 7,
                  0,
                  demo.playback()
                      .gameState()
                      .map(ServerMessageCodec.GameState::clientNumber)
                      .orElse(-1),
                  demoName,
                  "",
                  "");
            if (networked()) return remoteClientState();
            return client == null
                ? new ClientState(1, 0, -1, "", "", errorMessage)
                : new ClientState(8, 0, 0, "localhost", "", "");
          }

          public String configString(int index) {
            if (demo != null)
              return demoSource == null ? "" : demoSource.configStrings().getOrDefault(index, "");
            if (remote != null) return remoteConfigString(index);
            return server == null ? "" : server.configstrings().get(index);
          }

          public int keyCatcher() {
            return client == null ? menuCatcher : client.keyCatcher();
          }

          public void keyCatcher(int value) {
            if (value < 0 || value > 15)
              throw new IllegalArgumentException("Invalid key catcher mask");
            menuCatcher = value;
            if (client != null) client.keyCatcher(value);
          }

          public void clearKeys() {
            input.releaseAll(inputTime());
          }
        });
  }

  private void closeUi() throws IOException {
    if (ui != null) {
      Q3Ui previous = ui;
      ui = null;
      previous.close();
    }
  }

  private void reloadMenuAssets(int width, int height) throws IOException {
    closeUi();
    menuMaterials = MaterialLibrary.load(fs, MENU_SCENE);
    engineConsole = new dev.bluevista.craftq3.client.EngineConsole(fs, audio, this::print);
    ui = createUi();
    ui.initialize(width, height);
    routeCommands();
  }

  private void disconnectedCommands() {
    routeCommands();
  }

  private void routeCommands() {
    routeCommands(commands);
    if (server != null) routeCommands(server.commands());
  }

  /** Game, client and UI commands share the local engine dispatch path and argument scope. */
  private void routeCommands(CommandSystem buffer) {
    buffer.unknownHandler(
        command -> {
          if (ui != null && ui.state() == Q3Ui.State.RUNNING && ui.consoleCommand(command, uiTime))
            return;
          if (client != null) client.consoleCommand(command);
          else print("Unknown command: " + command.argument(0));
        });
  }

  private void installEngineCommands(CommandSystem buffer) {
    browser.registerCommands(buffer);
    buffer.register(
        "cinematic",
        command -> {
          if (command.arguments().size() < 2 || command.arguments().size() > 3) {
            print("cinematic <movie> [0|1 (hold)|2 (loop)]");
            return;
          }
          String option = command.argument(2);
          int flags =
              switch (option) {
                case "", "0" -> Cinematics.SYSTEM;
                case "1" -> Cinematics.SYSTEM | Cinematics.HOLD;
                case "2" -> Cinematics.SYSTEM | Cinematics.LOOP;
                default -> -1;
              };
          if (flags < 0) {
            print("Invalid cinematic option: " + option);
            return;
          }
          cinematicRequest = command.argument(1);
          cinematicFlags = flags;
          buffer.frameBoundary();
        });

    buffer.register(
        "minecraft",
        command -> {
          if (command.arguments().size() != 1) {
            print("minecraft: carry your local Quake loadout into the open Minecraft world");
            return;
          }
          bridgeRequested = true;
          buffer.frameBoundary();
        });
    buffer.register(
        "demo",
        command -> {
          if (command.arguments().size() != 2) {
            print("demo <name>");
            return;
          }
          demoRequest = command.argument(1);
          buffer.frameBoundary();
        });
    buffer.register(
        "record",
        command -> {
          if (command.arguments().size() > 2) {
            print("record [name]");
            return;
          }
          recordRequest = command.argument(1);
          buffer.frameBoundary();
        });
    buffer.register(
        "stoprecord",
        command -> {
          stopRecordRequested = true;
          buffer.frameBoundary();
        });
    buffer.register(
        "connect",
        command -> {
          if (command.arguments().size() != 2) {
            print("connect <host[:port]>");
            return;
          }
          try {
            RemoteAddress.parse(command.argument(1));
          } catch (IllegalArgumentException invalid) {
            print("Invalid server address: " + invalid.getMessage());
            return;
          }
          connectRequest = command.argument(1);
          buffer.frameBoundary();
        });
    for (String name : List.of("map", "devmap", "spmap", "spdevmap"))
      buffer.register(
          name,
          command -> {
            if (command.arguments().size() != 2)
              throw new IllegalArgumentException(name + " <map>");
            mapRequest =
                new MapRequest(
                    mapPath(command.argument(1)),
                    name.contains("dev"),
                    name.startsWith("sp"),
                    time,
                    false);
            buffer.frameBoundary();
          });
    buffer.register(
        "map_restart",
        command -> {
          if (server == null) throw new IllegalStateException("No local server is running");
          if (command.arguments().size() > 2)
            throw new IllegalArgumentException("map_restart [seconds]");
          int delay = command.arguments().size() == 2 ? Integer.parseInt(command.argument(1)) : 5;
          if (delay < 0 || delay > 3600)
            throw new IllegalArgumentException("Invalid restart delay");
          mapRequest =
              new MapRequest(
                  world.mapName(),
                  cvars.integer("sv_cheats") != 0,
                  cvars.integer("g_gametype") == 2,
                  Math.addExact(time, delay * 1000),
                  true);
          // An immediate restart must be processed after this VM/command invocation unwinds.
          if (delay == 0) buffer.frameBoundary();
        });
    buffer.register(
        "disconnect",
        command -> {
          disconnectRequested = true;
          buffer.frameBoundary();
        });
    buffer.register(
        "quit",
        command -> {
          exitRequested = true;
          buffer.frameBoundary();
        });
  }

  private static String mapPath(String name) {
    String value = name;
    if (!value.toLowerCase(java.util.Locale.ROOT).startsWith("maps/")) value = "maps/" + value;
    if (!value.toLowerCase(java.util.Locale.ROOT).endsWith(".bsp")) value += ".bsp";
    return new VirtualPath(value).value();
  }

  private void applyRequests(int width, int height) {
    if (exitRequested) return;
    if (cinematicSkip) {
      cinematicSkip = false;
      finishCinematic(true);
    }
    if (cinematicRequest != null) {
      String name = cinematicRequest;
      cinematicRequest = null;
      try {
        beginCinematic(name, cinematicFlags);
      } catch (IOException | RuntimeException failure) {
        print("Cannot start cinematic: " + failure.getMessage());
        if (!cinematicPlaying() && !playing()) ui.setMenu(Q3Ui.Menu.MAIN);
      }
    }
    if (stopRecordRequested) {
      stopRecordRequested = false;
      finishRecording();
    }
    if (recordRequest != null) {
      String name = recordRequest;
      recordRequest = null;
      try {
        beginRecording(name);
      } catch (IOException | RuntimeException failure) {
        recordingFailure(failure);
      }
    }
    if (demoRequest != null) {
      String name = demoRequest;
      demoRequest = null;
      mapRequest = null;
      connectRequest = null;
      try {
        closeGame();
        beginDemo(name, width, height);
      } catch (IOException | RuntimeException failure) {
        remoteFailure(failure);
      }
    }
    if (disconnectRequested) {
      disconnectRequested = false;
      mapRequest = null;
      connectRequest = null;
      try {
        closeGame();
      } catch (IOException e) {
        print("Game cleanup: " + e.getMessage());
      }
      ui.setMenu(Q3Ui.Menu.MAIN);
    }
    if (connectRequest != null) {
      String address = connectRequest;
      connectRequest = null;
      mapRequest = null;
      try {
        closeGame();
        beginConnection(address);
      } catch (IOException | RuntimeException failure) {
        remoteFailure(failure);
      }
    }
    if (mapRequest == null || time < mapRequest.due()) return;
    MapRequest request = mapRequest;
    mapRequest = null;
    try {
      if (request.restart() && server != null && !server.requiresMapReload()) {
        input.releaseAll(time);
        ui.setMenu(Q3Ui.Menu.NONE);
        server.restart(
            GameplaySmoke.captureEnabled() || LifecycleSmoke.enabled()
                ? 42
                : (int) System.nanoTime());
        time = server.time();
        input.releaseAll(time);
        cvars.set("cl_paused", "0", CvarSystem.Source.ENGINE);
        return;
      }
      if (networked()) closeGame();
      var bsp = BspReader.read(fs.read(new VirtualPath(request.name())));
      var nextWorld = BspSceneBuilder.build(request.name(), bsp, 8);
      var nextMaterials = MaterialLibrary.load(fs, nextWorld);
      // Parse the complete new world before releasing a running game.
      closeGame(host != null && !request.singlePlayer() && cvars.integer("net_enabled") != 0);
      ui.setMenu(Q3Ui.Menu.NONE);
      startGame(nextWorld, nextMaterials, request.cheats(), request.singlePlayer(), width, height);
      errorMessage = "";
    } catch (IOException | RuntimeException e) {
      print("Could not start " + request.name() + ": " + e.getMessage());
      errorMessage = e.getMessage() == null ? "Could not start map" : e.getMessage();
      if (client == null
          || client.state() != Q3Client.State.RUNNING
          || (server != null && server.state() != Q3Server.State.RUNNING)) {
        try {
          closeGame();
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
        ui.setMenu(Q3Ui.Menu.MAIN);
      }
    }
  }

  private static VirtualPath cinematicPath(String name) {
    String value = name.contains("/") ? name : "video/" + name;
    if (!value.toLowerCase(java.util.Locale.ROOT).endsWith(".roq")) value += ".roq";
    return new VirtualPath(value);
  }

  private void beginCinematic(String name, int flags) throws IOException {
    var path = cinematicPath(name);
    // Check the opening frame before disconnecting a healthy match or replacing another movie.
    boolean openingFrame = false;
    try (var decoder = new RoqDecoder(fs.open(path))) {
      for (int work = 0; work < 256; work++) {
        var event = decoder.next();
        if (event.isEmpty()) break;
        if (event.get() instanceof RoqDecoder.Video) {
          openingFrame = true;
          break;
        }
      }
    }
    if (!openingFrame) throw new IOException("Movie has no opening video frame");
    closeGame();
    mapRequest = null;
    connectRequest = null;
    ui.setMenu(Q3Ui.Menu.NONE);
    audio.stopAll();
    cinematicFailure = false;
    cinematic = cinematics.play(path.value(), 0, 0, 640, 480, flags, uiTime);
    if (cinematic < 0) throw new IOException("Movie playback could not start");
    cvars.set("cl_cinematic", path.value(), CvarSystem.Source.ENGINE);
    input.releaseAll(inputTime());
  }

  private void finishCinematic(boolean continueMap) {
    if (!cinematicPlaying()) return;
    cinematics.stop(cinematic);
    cinematic = -1;
    cinematicSkip = false;
    cvars.set("cl_cinematic", "", CvarSystem.Source.ENGINE);
    input.releaseAll(inputTime());
    ui.setMenu(Q3Ui.Menu.MAIN);
    if (continueMap && !cinematicFailure) {
      String next = cvars.string("nextmap");
      cvars.set("nextmap", "", CvarSystem.Source.ENGINE);
      if (!next.isBlank()) commands.submit(next + "\n", CommandSystem.Execution.INSERT);
    }
  }

  public boolean cinematicPlaying() {
    return cinematic >= 0;
  }

  public java.util.Optional<Cinematics.Info> cinematicInfo() {
    return cinematics.info(cinematic);
  }

  /** Movies consume gameplay input; ordinary keys and primary click request exit at a boundary. */
  public boolean cinematicKey(int key, boolean down) {
    if (!cinematicPlaying()) return false;
    if (down && (key >= 0 && key < 128 || key == 178)) cinematicSkip = true;
    return true;
  }

  private CgameFrame cinematicFrame(int width, int height) {
    cinematics.beginFrame();
    if (cinematics.run(cinematic, uiTime) == Cinematics.EOF) {
      finishCinematic(!cinematicFailure);
      return ui.frame(uiTime, width, height);
    }
    uiFrame = null;
    audio.beginFrame();
    var commands =
        cinematics
            .draw(cinematic, width, height)
            .<List<CgameFrame.Command>>map(List::of)
            .orElseGet(List::of);
    audio.volume(Math.clamp(cvars.number("s_volume"), 0, 1));
    audio.endFrame(
        new AudioBackend.Listener(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1)));
    return new CgameFrame(commands, dev.bluevista.craftq3.render.SceneAssets.EMPTY, uiTime);
  }

  private void startGame(
      RenderScene nextWorld,
      MaterialLibrary nextMaterials,
      boolean cheats,
      boolean singlePlayer,
      int width,
      int height)
      throws IOException {
    cvars.set("sv_cheats", cheats ? "1" : "0", CvarSystem.Source.ENGINE);
    cvars.set("cl_paused", "0", CvarSystem.Source.ENGINE);
    if (singlePlayer) cvars.set("g_gametype", "2", CvarSystem.Source.ENGINE);
    if (arrivingLoadout != null && cvars.integer("dedicated") != 0)
      throw new IllegalStateException(
          "Set dedicated 0 before entering a map with a carried loadout.");
    server =
        arrivingLoadout == null
            ? new Q3Server(
                fs,
                nextWorld.mapName(),
                nextWorld.bsp(),
                savedFiles,
                this::print,
                Clock.systemUTC(),
                null,
                cvars)
            : new Q3Server(
                fs,
                nextWorld.mapName(),
                nextWorld.bsp(),
                savedFiles,
                this::print,
                Clock.systemUTC(),
                null,
                cvars,
                arrivingLoadout);
    installEngineCommands(server.commands());
    server.commands().savedFiles(savedFiles);
    server.initialize(
        time,
        GameplaySmoke.captureEnabled() || Boolean.getBoolean("craftq3.lifecycleCapture")
            ? 42
            : (int) System.nanoTime());
    time = server.time();
    client =
        cvars.integer("dedicated") == 0
            ? new Q3Client(fs, server, frame -> {}, audio, this::print, null, commands)
            : null;
    if (client != null) {
      userInfo = cvars.infoString(CvarSystem.USERINFO, 1024);
      server.connect(0, InfoString.parse(userInfo, 1024));
      time = server.time();
      arrivingLoadout = null;
      client.initialize(0, width, height);
      var state = playerState();
      input.releaseAll(time);
      input.angles(
          state.getFloat(152) - state.getInt(56) * (360.0 / 65536),
          state.getFloat(156) - state.getInt(60) * (360.0 / 65536),
          state.getFloat(160) - state.getInt(64) * (360.0 / 65536));
    }
    routeCommands();
    if (!singlePlayer && cvars.integer("net_enabled") != 0) startHosting();
    world = nextWorld;
    materials = nextMaterials;
    generation++;
  }

  private void startHosting() throws IOException {
    if (host != null) {
      host.replaceGame(server, System.nanoTime() / 1_000_000);
      return;
    }
    int port = cvars.integer("net_port");
    String bind = cvars.string("net_ip");
    if (Boolean.getBoolean("craftq3.audit.loopbackHost")) {
      bind = "127.0.0.1";
      port = 0;
    }
    var next =
        new dev.bluevista.craftq3.platform.net.DatagramListener(
            new InetSocketAddress(InetAddress.ofLiteral(bind), port));
    try {
      host =
          new dev.bluevista.craftq3.server.net.Protocol68Host(
              server, fs, Math.max(1, time), 0, this::print);
      listener = next;
      print("Hosting Quake on " + listener.localAddress());
    } catch (RuntimeException | Error failure) {
      next.close();
      throw failure;
    }
  }

  public boolean hosting() {
    return host != null && listener != null;
  }

  public boolean keepsRunning() {
    return cinematicPlaying()
        || networked()
        || hosting() && (host.hasClients() || cvars.integer("dedicated") != 0);
  }

  public java.util.Optional<InetSocketAddress> hostedAddress() throws IOException {
    return listener == null
        ? java.util.Optional.empty()
        : java.util.Optional.of(listener.localAddress());
  }

  public List<dev.bluevista.craftq3.server.net.Protocol68Host.Client> hostedClients() {
    return host == null ? List.of() : host.clients();
  }

  private void hostTick() {
    if (!hosting()) return;
    long now = System.nanoTime() / 1_000_000;
    try {
      for (int i = 0; i < 64; i++) {
        var packet = listener.poll();
        if (packet.isEmpty()) break;
        host.receive(packet.orElseThrow().peer(), packet.orElseThrow().payload(), now);
      }
      host.tick(now);
      flushHost();
    } catch (IOException | RuntimeException failure) {
      print("Hosting failed: " + failure.getMessage());
      try {
        closeHosting();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      if (server != null && server.state() == Q3Server.State.FAILED) remoteFailure(failure);
    }
  }

  private void flushHost() throws IOException {
    flushHost(128);
  }

  private void flushHost(int limit) throws IOException {
    for (int i = 0; i < limit; i++) {
      var packet = host.peekPacket();
      if (packet.isEmpty()) break;
      var value = packet.orElseThrow();
      if (!listener.send(value.peer(), value.payload())) break;
      host.packetSent(System.nanoTime() / 1_000_000);
    }
  }

  private void closeHosting() throws IOException {
    if (host == null && listener == null) return;
    try {
      if (host != null && listener != null) {
        host.shutdown(System.nanoTime() / 1_000_000);
        flushHost(1024);
      }
    } finally {
      try {
        if (host != null) host.close();
      } finally {
        host = null;
        if (listener != null) {
          var socket = listener;
          listener = null;
          socket.close();
        }
      }
    }
  }

  private void beginConnection(String address) {
    var target = RemoteAddress.parse(address);
    remoteAddress = address;
    remoteMessage = "Resolving server address";
    errorMessage = "";
    cvars.set("com_errorMessage", "", CvarSystem.Source.ENGINE);
    remoteEpoch = System.nanoTime();
    remoteInputBase = time;
    connectPackets = 0;
    remoteGeneration = 0;
    remoteClock.reset();
    var answer = new CompletableFuture<InetSocketAddress>();
    resolution = answer;
    resolver =
        Thread.ofVirtual()
            .name("CraftQ3-server-address")
            .start(
                () -> {
                  try {
                    var addresses = InetAddress.getAllByName(target.host());
                    InetAddress selected =
                        java.util.Arrays.stream(addresses)
                            .filter(Inet4Address.class::isInstance)
                            .findFirst()
                            .orElse(addresses[0]);
                    answer.complete(new InetSocketAddress(selected, target.port()));
                  } catch (Exception failure) {
                    answer.completeExceptionally(failure);
                  }
                });
    ui.setMenu(Q3Ui.Menu.NONE);
    print("Connecting to " + address);
  }

  private int remoteMilliseconds() {
    return Math.toIntExact((System.nanoTime() - remoteEpoch) / 1_000_000);
  }

  /** Screen ticks also keep a remote connection alive while the framebuffer is minimized. */
  public void networkTick(int width, int height) {
    if (closed) return;
    browser.pump();
    hostTick();
    if (!networked()) return;
    presentationWidth = Math.max(1, width);
    presentationHeight = Math.max(1, height);
    try {
      if (resolution != null) {
        if (!resolution.isDone()) {
          if (remoteMilliseconds() >= 15000)
            throw new IllegalStateException("Server address lookup timed out");
          return;
        }
        var peer = resolution.join();
        userInfo = cvars.infoString(CvarSystem.USERINFO, 1024);
        remote =
            RemoteConnection.open(
                peer,
                InfoString.parse(userInfo, 1024),
                java.util.concurrent.ThreadLocalRandom.current().nextInt(),
                java.util.concurrent.ThreadLocalRandom.current().nextInt(65536),
                remoteMilliseconds());
        resolution = null;
        resolver = null;
        remoteMessage = "Requesting server challenge";
      }
      var result = remote.pump(remoteMilliseconds());
      if (!remoteClock.state().active()) connectPackets += result.sentDatagrams();
      for (var event : result.events()) {
        switch (event.kind()) {
          case PRINT -> {
            remoteMessage = displayMessage(event.diagnostic());
            print(event.diagnostic());
          }
          case STATE -> remoteMessage = event.diagnostic();
          case REJECTED -> print("Network packet: " + event.diagnostic());
          case MESSAGE -> {
            if (recording != null) {
              try {
                recording.accept(
                    event.received().demoRecord().orElseThrow(), event.received().message());
                remote
                    .session()
                    .orElseThrow()
                    .fullSnapshots(recording.state() == Protocol68DemoRecorder.State.WAITING);
              } catch (IOException | RuntimeException failure) {
                recordingFailure(failure);
              }
            }
            for (var operation : event.received().message().operations()) {
              if (operation
                  instanceof dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState) {
                downloads.close();
                remoteClock.reset();
              } else if (operation instanceof ServerMessageCodec.Download packet) {
                downloads.accept(packet, remote.session().orElseThrow()::command);
              } else if (operation
                  instanceof dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame frame) {
                remoteClock.snapshot(frame.current().time(), frame.current().flags());
              }
            }
          }
          default -> {}
        }
      }
      if (result.state() == RemoteConnection.State.FAILED
          || result.state() == RemoteConnection.State.TIMED_OUT)
        throw new IllegalStateException(remote.diagnostic());
      var wire = remote.session().orElse(null);
      if (wire != null) {
        downloads.pump(wire::command, wire::beginDownload, uiTime);
        if (wire.gameStateMessageSequence() != remoteGeneration)
          startRemoteGame(Math.max(1, width), Math.max(1, height));
        if (downloads.busy())
          remoteMessage =
              downloads.verifying() ? "Verifying downloaded PK3" : "Downloading server content";
      }
    } catch (IOException | RuntimeException failure) {
      remoteFailure(failure);
    }
  }

  private List<String> ownedDemoNames() {
    if (demoFiles == null) return List.of();
    try {
      return demoFiles.list().stream().map(VirtualPath::value).toList();
    } catch (IOException failure) {
      print("Could not list demos: " + failure.getMessage());
      return List.of();
    }
  }

  private List<String> allDemoNames() {
    var names = new java.util.ArrayList<>(ownedDemoNames());
    for (var path : fs.list("demos")) {
      String name = path.value().substring("demos/".length());
      if (!name.contains("/")) names.add(name);
    }
    return List.copyOf(names);
  }

  private static String demoPath(String requested) {
    String name = requested;
    if (name.regionMatches(true, 0, "demos/", 0, 6)) name = name.substring(6);
    var path = new VirtualPath(name);
    if (path.value().contains("/"))
      throw new IllegalArgumentException("Demo names must be flat filenames");
    return path.value();
  }

  private void beginDemo(String requested, int width, int height) throws IOException {
    cvars.set("sv_cheats", "1", CvarSystem.Source.ENGINE);
    fs.pureServerPaks(List.of());
    String name = demoPath(requested);
    if (name.endsWith(".dm3"))
      name =
          DemoCatalog.resolveAlias(name, allDemoNames())
              .orElseThrow(() -> new IOException("Protocol43 .dm3 playback is not implemented"));
    else if (!name.endsWith(".dm_68")) name += ".dm_68";
    java.io.InputStream stream =
        demoFiles == null ? null : demoFiles.openRead(new VirtualPath(name)).orElse(null);
    if (stream == null)
      stream = new java.io.ByteArrayInputStream(fs.read(new VirtualPath("demos/" + name)));
    demoInputBase = time;
    demoEpoch = System.nanoTime();
    demo = new DemoPlayer(stream);
    demoName = name;
    cvars.set("cl_demoPlaying", "1", CvarSystem.Source.ENGINE);
    cvars.set("cl_demoName", name, CvarSystem.Source.ENGINE);
    demo.prime();
    startDemoGame(width, height);
    print("Playing demo " + name);
  }

  private void startDemoGame(int width, int height) throws IOException {
    var game = demo.playback().gameState().orElseThrow();
    String map = InfoString.parse(game.configstrings().getOrDefault(0, ""), 8192).get("mapname");
    if (map == null || map.isEmpty()) throw new IOException("Demo omitted map name");
    String path = mapPath(map);
    demoContentView = true;
    if (client != null) client.close();
    client = null;
    closeUi();
    audio.resetAssets();
    fs.restartView(game.checksumFeed());
    var bsp = BspReader.read(fs.read(new VirtualPath(path)));
    var nextWorld = BspSceneBuilder.build(path, bsp, 8);
    var nextMaterials = MaterialLibrary.load(fs, nextWorld);
    demoSource = new DemoCgameSource(demo.playback());
    client = new Q3Client(fs, demoSource, cvars, commands, frame -> {}, audio, this::print);
    client.initialize(game.clientNumber(), width, height);
    reloadMenuAssets(width, height);
    client.userCommand(UserCommand.idle(0));
    input.releaseAll(inputTime());
    ui.setMenu(Q3Ui.Menu.NONE);
    routeCommands();
    world = nextWorld;
    materials = nextMaterials;
    demoGeneration = demo.playback().generation();
    generation++;
  }

  private CgameFrame demoFrame(int milliseconds, int width, int height) {
    try {
      var frameTime =
          demo.frame(
              milliseconds,
              cvars.integer("cl_timeNudge"),
              cvars.number("timescale"),
              cvars.integer("cl_freezeDemo") != 0,
              cvars.integer("timedemo") != 0,
              (int) (System.nanoTime() / 1_000_000));
      if (demo.playback().ended()) {
        var benchmark = demo.clock().timedemo();
        int duration = (int) (System.nanoTime() / 1_000_000) - benchmark.start();
        if (benchmark.frames() > 0 && duration > 0)
          print(
              String.format(
                  java.util.Locale.ROOT,
                  "%d frames, %.3f seconds: %.1f fps",
                  benchmark.frames(),
                  duration / 1000.0,
                  benchmark.frames() * 1000.0 / duration));
        print("Demo finished: " + demo.playback().endReason());
        closeGame();
        String nextDemo = cvars.string("nextdemo");
        if (!nextDemo.isEmpty()) {
          cvars.set("nextdemo", "", CvarSystem.Source.ENGINE);
          commands.submit(nextDemo + "\n", CommandSystem.Execution.APPEND);
        }
        ui.setMenu(Q3Ui.Menu.MAIN);
        return ui.frame(uiTime, width, height);
      }
      if (demoGeneration != demo.playback().generation()) startDemoGame(width, height);
      if (frameTime.isEmpty()) return ui.drawConnectScreen(false);
      time = frameTime.getAsInt();
      var frame = client.frame(time, width, height);
      uiFrame = menuVisible() ? ui.frame(uiTime, width, height) : null;
      return frame;
    } catch (IOException | RuntimeException failure) {
      remoteFailure(failure);
      return ui.frame(uiTime, width, height);
    }
  }

  private void beginRecording(String requested) throws IOException {
    if (recording != null) {
      print("Already recording " + recordingName);
      return;
    }
    if (!playing()
        || demo != null
        || downloads.busy()
        || networked() && !remoteClock.state().active())
      throw new IllegalStateException("Recording requires an active game");
    if (demoFiles == null) throw new IOException("Demo storage is unavailable");
    String name = requested.isEmpty() ? "demo0000.dm_68" : demoPath(requested);
    if (requested.isEmpty()) {
      var used = new java.util.HashSet<>(ownedDemoNames());
      int number = 0;
      while (used.contains(name) && number < 10000) name = "demo%04d.dm_68".formatted(++number);
      if (number == 10000) throw new IOException("No free automatic demo name");
    }
    if (!name.endsWith(".dm_68")) name += ".dm_68";
    recordingFile = demoFiles.openAtomicWrite(new VirtualPath(name));
    recordingName = name;
    try {
      recording = new Protocol68DemoRecorder(recordingFile, recordingFile.byteLimit());
    } catch (RuntimeException failure) {
      recordingFile.close();
      recordingFile = null;
      throw failure;
    }
    if (remote != null) {
      var wire = remote.session().orElseThrow();
      var game = wire.gameState().orElseThrow();
      recording.start(
          new ServerMessageCodec.GameState(
              wire.serverCommandSequence(),
              remoteSource.configStrings(),
              game.baselines(),
              game.clientNumber(),
              game.checksumFeed()),
          wire.messageAcknowledge(),
          wire.reliableSequence());
      wire.fullSnapshots(true);
    } else {
      localRecording = new LocalDemoFeed(server, 0, 0);
      recording.start(localRecording.initialGameState(), localRecording.nextSequence(), 0);
    }
    cvars.set("cl_demoRecording", "1", CvarSystem.Source.ENGINE);
    cvars.set("cl_demoName", name, CvarSystem.Source.ENGINE);
    print("Recording " + name);
  }

  private void captureLocalRecording() {
    if (recording == null || localRecording == null) return;
    try {
      var next = localRecording.capture();
      if (next.isPresent())
        recording.accept(next.orElseThrow().record(), next.orElseThrow().message());
    } catch (IOException | RuntimeException failure) {
      recordingFailure(failure);
    }
  }

  private void finishRecording() {
    if (recording == null) return;
    try {
      recording.finish();
      if (recording.state() == Protocol68DemoRecorder.State.FINISHED
          && recording.recordsWritten() > 0) {
        recordingFile.commit();
        print("Saved demo " + recordingName);
      }
    } catch (IOException | RuntimeException failure) {
      print("Could not finish demo: " + failure.getMessage());
    } finally {
      try {
        recording.close();
      } catch (IOException failure) {
        print("Demo close: " + failure.getMessage());
      }
      recording = null;
      recordingFile = null;
      localRecording = null;
      if (remote != null) remote.session().ifPresent(wire -> wire.fullSnapshots(false));
      cvars.set("cl_demoRecording", "0", CvarSystem.Source.ENGINE);
      cvars.set("cl_demoName", "", CvarSystem.Source.ENGINE);
    }
  }

  private void recordingFailure(Exception failure) {
    print("Demo recording: " + failure.getMessage());
    finishRecording();
  }

  private static List<Integer> serverPakChecksums(RemoteSystemInfo.Settings settings) {
    return checksumList(settings, "sv_paks");
  }

  private static List<Integer> checksumList(RemoteSystemInfo.Settings settings, String field) {
    String text = settings.values().getOrDefault(field, "").trim();
    if (text.isEmpty()) return List.of();
    String[] words = text.split("\\s+");
    if (words.length > 1024) throw new IllegalArgumentException("Too many server PK3 checksums");
    var checksums = new java.util.ArrayList<Integer>(words.length);
    for (String word : words) checksums.add(Integer.parseInt(word));
    return List.copyOf(checksums);
  }

  private void validateRemoteContent(String systemInfo) {
    var settings = RemoteSystemInfo.parse(systemInfo);
    settings.requireGame(fs.gameDirectory());
    if (remoteContentView
        && (remotePure != settings.pure()
            || !remotePakChecksums.equals(serverPakChecksums(settings))))
      throw new UnsupportedOperationException(
          "Server changed its PK3 requirements without a new gamestate; reconnect to reload"
              + " content");
  }

  private void startRemoteGame(int width, int height) throws IOException {
    var wire = remote.session().orElseThrow();
    var game = wire.initialGameState().orElseThrow();
    var content = RemoteSystemInfo.parse(game.configstrings().getOrDefault(1, ""));
    content.requireGame(fs.gameDirectory());
    List<Integer> checksums = serverPakChecksums(content);
    if (!downloads.prepare(
        wire.gameStateMessageSequence(),
        content,
        wire::command,
        wire::beginDownload,
        this::finishRecording,
        uiTime)) return;
    String name = InfoString.parse(game.configstrings().getOrDefault(0, ""), 8192).get("mapname");
    if (name == null || name.isEmpty())
      throw new IllegalArgumentException("Server omitted its map name");
    String path = mapPath(name);
    // Cleanup must restore a usable local presentation even if retiring an old VM fails.
    remoteContentView = true;
    if (client != null) client.close();
    client = null;
    closeUi();
    audio.resetAssets();
    remotePure = content.pure();
    remotePakChecksums = checksums;
    fs.pureServerPaks(checksums);
    fs.restartView(game.checksumFeed());
    var bsp = BspReader.read(fs.read(new VirtualPath(path)));
    var nextWorld = BspSceneBuilder.build(path, bsp, 8);
    var nextMaterials = MaterialLibrary.load(fs, nextWorld);
    var settings = new RemoteSystemInfo(cvars, this::print);
    remoteSource =
        new RemoteCgameSource(
            wire,
            remote::userCommand,
            remote::snapshotPing,
            text -> {
              validateRemoteContent(text);
              settings.apply(text);
            });
    client = new Q3Client(fs, remoteSource, cvars, commands, frame -> {}, audio, this::print);
    client.initialize(game.clientNumber(), width, height);
    reloadMenuAssets(width, height);
    if (remotePure) {
      for (String module : List.of("vm/cgame.qvm", "vm/ui.qvm")) {
        if (fs.which(new VirtualPath(module)).filter(origin -> origin.archive()).isEmpty())
          throw new IllegalStateException(
              "Pure server requires an installed PK3 containing " + module);
      }
    }
    wire.command("cp " + wire.serverId() + " " + fs.referencedPureChecksums());
    client.userCommand(UserCommand.idle(0));
    remoteGeneration = wire.gameStateMessageSequence();
    remoteAnglesSeeded = false;
    input.releaseAll(inputTime());
    ui.setMenu(Q3Ui.Menu.NONE);
    routeCommands();
    world = nextWorld;
    materials = nextMaterials;
    generation++;
  }

  private CgameFrame remoteFrame(int width, int height) {
    try {
      var clock = remoteClock.frame(remoteMilliseconds(), cvars.integer("cl_timeNudge"), 1);
      if (client == null || clock.isEmpty()) {
        audio.beginFrame();
        ui.frame(uiTime, width, height);
        var frame = ui.drawConnectScreen(false);
        audio.endFrame(
            new AudioBackend.Listener(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1)));
        uiFrame = null;
        return frame;
      }
      var wire = remote.session().orElseThrow();
      var player =
          ByteBuffer.wrap(wire.snapshot().orElseThrow().player()).order(ByteOrder.LITTLE_ENDIAN);
      if (!remoteAnglesSeeded) {
        input.angles(
            player.getFloat(152) - player.getInt(56) * (360.0 / 65536),
            player.getFloat(156) - player.getInt(60) * (360.0 / 65536),
            player.getFloat(160) - player.getInt(64) * (360.0 / 65536));
        remoteAnglesSeeded = true;
      }
      time = clock.getAsInt();
      String nextInfo = cvars.infoString(CvarSystem.USERINFO, 1024);
      if (!nextInfo.equals(userInfo)) {
        wire.command("userinfo \"" + nextInfo + "\"");
        userInfo = nextInfo;
      }
      int weapon = client.selectedWeapon() > 0 ? client.selectedWeapon() : player.getInt(144);
      var sample = input.sample(inputTime(), weapon, client.sensitivityScale(), player.getInt(56));
      client.userCommand(
          new UserCommand(
              time,
              sample.pitch(),
              sample.yaw(),
              sample.roll(),
              sample.buttons(),
              sample.weapon(),
              sample.forward(),
              sample.right(),
              sample.up()));
      var frame = client.frame(time, width, height);
      uiFrame = menuVisible() ? ui.frame(uiTime, width, height) : null;
      return frame;
    } catch (RuntimeException failure) {
      remoteFailure(failure);
      return ui.frame(uiTime, width, height);
    }
  }

  private UiHost.ClientState remoteClientState() {
    int state = 3, number = -1;
    if (remote != null) {
      state =
          switch (remote.state()) {
            case CHALLENGING -> 3;
            case CONNECTING -> 4;
            case CONNECTED -> client == null ? 5 : remoteClock.state().active() ? 8 : 7;
            default -> 1;
          };
      if (remote.session().isPresent() && remote.session().orElseThrow().gameState().isPresent())
        number = remote.session().orElseThrow().gameState().orElseThrow().clientNumber();
    }
    return new UiHost.ClientState(state, connectPackets, number, remoteAddress, "", remoteMessage);
  }

  private String remoteConfigString(int index) {
    var wire = remote.session().orElse(null);
    if (wire == null || wire.gameState().isEmpty()) return "";
    if (remoteSource != null && wire.gameStateMessageSequence() == remoteGeneration)
      return remoteSource.configStrings().getOrDefault(index, "");
    return wire.initialGameState().orElseThrow().configstrings().getOrDefault(index, "");
  }

  private void remoteFailure(Exception failure) {
    Throwable cause = failure;
    while (cause.getCause() != null) cause = cause.getCause();
    String message = cause.getMessage() == null ? "Remote connection failed" : cause.getMessage();
    errorMessage = displayMessage(message);
    print("Connection ended: " + message);
    try {
      closeGame();
    } catch (IOException cleanup) {
      print("Connection cleanup: " + cleanup.getMessage());
    }
    cvars.set("com_errorMessage", errorMessage, CvarSystem.Source.ENGINE);
    if (ui != null && ui.state() == Q3Ui.State.RUNNING) ui.setMenu(Q3Ui.Menu.MAIN);
  }

  private static String displayMessage(String text) {
    var display = new StringBuilder();
    for (int index = 0; index < text.length() && display.length() < 8191; index++) {
      char value = text.charAt(index);
      display.append(value < 32 || value == 127 ? ' ' : value > 255 ? '?' : value);
    }
    return display.toString();
  }

  private void closeGame() throws IOException {
    closeGame(false);
  }

  private void closeGame(boolean retainHost) throws IOException {
    cinematics.clear();
    cinematic = -1;
    cinematicSkip = false;
    cvars.set("cl_cinematic", "", CvarSystem.Source.ENGINE);
    finishRecording();
    IOException failure = null;
    try {
      downloads.close();
    } catch (IOException problem) {
      failure = problem;
    }
    if (!retainHost) {
      try {
        closeHosting();
      } catch (IOException problem) {
        failure = problem;
      }
    }
    if (demo != null) {
      time = inputTime();
      try {
        demo.close();
      } catch (IOException problem) {
        failure = problem;
      }
      demo = null;
      demoSource = null;
      cvars.set("cl_demoPlaying", "0", CvarSystem.Source.ENGINE);
      cvars.set("cl_demoName", "", CvarSystem.Source.ENGINE);
    }
    int inputNow = inputTime();
    input.releaseAll(inputNow);
    time = Math.max(time, inputNow);
    if (resolution != null) resolution.cancel(true);
    if (resolver != null) resolver.interrupt();
    resolution = null;
    resolver = null;
    if (remote != null) {
      try {
        remote.disconnectAndClose(remoteMilliseconds());
      } catch (IOException | RuntimeException e) {
        if (failure == null) failure = new IOException("Could not close remote connection", e);
        else failure.addSuppressed(e);
      }
    }
    remote = null;
    remoteSource = null;
    remoteClock.reset();
    remoteGeneration = 0;
    for (AutoCloseable resource : new AutoCloseable[] {client, server}) {
      if (resource == null) continue;
      try {
        resource.close();
      } catch (Exception e) {
        if (failure == null) failure = new IOException("Could not close local game", e);
        else failure.addSuppressed(e);
      }
    }
    client = null;
    server = null;
    if (remoteContentView || demoContentView) {
      try {
        closeUi();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
      audio.resetAssets();
      fs.pureServerPaks(List.of());
      fs.restartView(0);
      remoteContentView = false;
      demoContentView = false;
      remotePure = false;
      remotePakChecksums = List.of();
      if (!closed) reloadMenuAssets(presentationWidth, presentationHeight);
    }
    uiFrame = null;
    audio.stopAll();
    cvars.set("sv_running", "0", CvarSystem.Source.ENGINE);
    cvars.set("cl_paused", "0", CvarSystem.Source.ENGINE);
    world = MENU_SCENE;
    materials = menuMaterials;
    disconnectedCommands();
    generation++;
    if (failure != null) throw failure;
  }

  public RenderScene world() {
    return world;
  }

  public MaterialLibrary materials() {
    return materials;
  }

  public CgameFrame consoleFrame(String text, int cursor, int width, int height, int scroll) {
    return engineConsole.frame(consoleLines(), text, cursor, width, height, uiTime, scroll);
  }

  public List<String> complete(String prefix) {
    var matches = commands.complete(prefix);
    if (matches.size() > 1) print(String.join("  ", matches));
    return matches;
  }

  public Q3Input input() {
    return input;
  }

  public Q3Client client() {
    return client;
  }

  public boolean playing() {
    return client != null && client.state() == Q3Client.State.RUNNING;
  }

  public int generation() {
    return generation;
  }

  public CvarSystem cvars() {
    return cvars;
  }

  public CommandSystem commands() {
    return commands;
  }

  public Q3Ui ui() {
    return ui;
  }

  public boolean menuVisible() {
    if (cinematicPlaying()) return false;
    return ((client == null ? menuCatcher : client.keyCatcher()) & 2) != 0;
  }

  public CgameFrame uiFrame() {
    return uiFrame;
  }

  public boolean fullscreenMenu() {
    return menuVisible() && ui.fullscreen();
  }

  public boolean takeBridgeRequest() {
    boolean requested = bridgeRequested;
    bridgeRequested = false;
    return requested;
  }

  public void bridgeMessage(String message) {
    print(message);
  }

  /** Read-only export; the caller closes this match only after preparing its destination. */
  public dev.bluevista.craftq3.server.PlayerLoadout bridgeLoadout(byte[] destinationModule)
      throws IOException {
    if (closed || !playing() || server == null || networked() || demo != null)
      throw new IllegalStateException(
          "Enter a local Quake match before transferring to Minecraft.");
    if (host != null && host.hasClients())
      throw new IllegalStateException("Remote players are connected to this match.");
    if (playerState().getInt(4) != 0)
      throw new IllegalStateException("Respawn as an active Quake player before transferring.");
    if (!java.util.Arrays.equals(fs.read(new VirtualPath("vm/qagame.qvm")), destinationModule))
      throw new IllegalArgumentException(
          "The bridge and local match use different qagame modules.");
    return dev.bluevista.craftq3.server.PlayerLoadout.capture(server.playerState(0), server.time());
  }

  public boolean exitRequested() {
    return exitRequested;
  }

  public void openMenu() {
    input.releaseAll(inputTime());
    if (cinematicKey(27, true) || demoKey(27, true)) return;
    if (networked() && !remoteClock.state().active()) disconnectRequested = true;
    ui.setMenu(client == null ? Q3Ui.Menu.MAIN : Q3Ui.Menu.INGAME);
  }

  /** Native demo exit keys, after console/UI routing and before gameplay bindings. */
  public boolean demoKey(int key, boolean down) {
    if (demo == null || !down || menuVisible()) return false;
    boolean ordinaryExit =
        cvars.integer("com_cameraMode") == 0 && (key >= 0 && key < 128 || key == 178);
    if (key != 27 && !ordinaryExit) return false;
    if (ordinaryExit) cvars.set("nextdemo", "", CvarSystem.Source.ENGINE);
    disconnectRequested = true;
    return true;
  }

  public void menuKey(int key, boolean down) {
    if (cinematicKey(key, down)) return;
    if (ui.state() == Q3Ui.State.RUNNING) ui.key(key, down, uiTime);
  }

  public void menuMouse(double dx, double dy) {
    if (cinematicPlaying()) return;
    if (Double.isFinite(dx) && Double.isFinite(dy))
      ui.mouse((int) Math.clamp(dx, -65536, 65536), (int) Math.clamp(dy, -65536, 65536), uiTime);
  }

  public Q3Server server() {
    return server;
  }

  public int time() {
    return time;
  }

  /** Physical input uses a monotonic clock even when the remote prediction clock is corrected. */
  public int inputTime() {
    if (demo != null)
      return Math.addExact(demoInputBase, (int) ((System.nanoTime() - demoEpoch) / 1_000_000));
    return networked() ? Math.addExact(remoteInputBase, remoteMilliseconds()) : time;
  }

  public boolean networked() {
    return remote != null || resolution != null;
  }

  public AudioBackend.Diagnostics audioDiagnostics() {
    return audio.diagnostics();
  }

  /**
   * Keep prediction, reliable-command consumption and released input current without a GPU frame.
   */
  public CgameFrame backgroundFrame(int width, int height) {
    input.releaseAll(inputTime());
    return advance(50, width, height);
  }

  public CgameFrame advance(int milliseconds, int width, int height) {
    presentationWidth = width;
    presentationHeight = height;
    if (closed) throw new IllegalStateException("Quake session closed");
    applyRequests(width, height);
    commands.runFrame(1024);
    applyRequests(width, height);
    uiTime = Math.addExact(uiTime, Math.clamp(milliseconds, 0, 200));
    if (cinematicPlaying()) return cinematicFrame(width, height);
    networkTick(width, height);
    if (demo != null) return demoFrame(milliseconds, width, height);
    if (networked()) return remoteFrame(width, height);
    if (client == null) {
      if (server != null && hosting()) {
        int next = Math.addExact(time, Math.clamp(milliseconds, 0, 200));
        while (server.time() + 50 <= next) server.runFrame(server.time() + 50);
        time = next;
        hostTick();
      }
      if (!menuVisible()) ui.setMenu(Q3Ui.Menu.MAIN);
      audio.beginFrame();
      CgameFrame menu = ui.frame(uiTime, width, height);
      audio.volume(Math.clamp(cvars.number("s_volume"), 0, 1));
      audio.endFrame(
          new AudioBackend.Listener(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1)));
      uiFrame = null;
      return menu;
    }
    boolean paused = !keepsRunning() && menuVisible() && cvars.integer("cl_paused") != 0;
    int next = Math.addExact(time, paused ? 0 : Math.clamp(milliseconds, 0, 200));
    String nextInfo = client.cvars().infoString(CvarSystem.USERINFO, 1024);
    if (!nextInfo.equals(userInfo)) {
      server.updateUserInfo(0, InfoString.parse(nextInfo, 1024));
      userInfo = nextInfo;
    }
    while (server.time() + 50 <= next) {
      server.runFrame(server.time() + 50);
      if (disconnectRequested || exitRequested || (mapRequest != null && mapRequest.due() <= next))
        break;
    }
    time = next;
    if (milliseconds > 0 && !paused) {
      var player = playerState();
      int weapon = client.selectedWeapon() > 0 ? client.selectedWeapon() : player.getInt(144);
      client.userCommand(input.sample(time, weapon, client.sensitivityScale(), player.getInt(56)));
    }
    var gameFrame = client.frame(time, width, height);
    captureLocalRecording();
    hostTick();
    uiFrame = menuVisible() ? ui.frame(uiTime, width, height) : null;
    return gameFrame;
  }

  public void command(String command) {
    print("] " + command);
    commands.submit(command, dev.bluevista.craftq3.core.command.CommandSystem.Execution.APPEND);
  }

  public synchronized List<String> consoleLines() {
    return List.copyOf(console);
  }

  private synchronized void print(String text) {
    for (String line : text.split("[\r\n]+")) {
      if (line.isEmpty()) continue;
      console.addLast(line.length() > 2048 ? line.substring(0, 2048) : line);
      while (console.size() > 256) console.removeFirst();
      dev.bluevista.craftq3.fabric.CraftQ3Client.LOGGER.info("Q3: {}", line);
    }
  }

  private ByteBuffer playerState() {
    return ByteBuffer.wrap(server.playerState(0)).order(ByteOrder.LITTLE_ENDIAN);
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    try {
      closeGame();
    } catch (IOException e) {
      failure = e;
    }
    if (initialized
        && savedFiles != null
        && !GameplaySmoke.captureEnabled()
        && !Boolean.getBoolean("craftq3.lifecycleCapture")) {
      try {
        config.save("q3config.cfg");
      } catch (IOException | RuntimeException e) {
        print("Could not save q3config.cfg: " + e.getMessage());
      }
    }
    for (AutoCloseable resource :
        new AutoCloseable[] {
          cinematics, ui, browser, client, server, audio, demoFiles, savedFiles, downloadCache, fs
        }) {
      if (resource == null) continue;
      try {
        resource.close();
      } catch (Exception e) {
        if (failure == null) failure = new IOException("Could not fully close Quake session", e);
        else failure.addSuppressed(e);
      }
    }
    if (failure != null) throw failure;
  }
}
