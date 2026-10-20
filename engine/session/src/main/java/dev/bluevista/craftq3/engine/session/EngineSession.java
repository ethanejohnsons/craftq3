package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.client.console.ClientConsole;
import dev.bluevista.craftq3.engine.client.demo.DemoPlayback;
import dev.bluevista.craftq3.engine.client.host.CgameHost;
import dev.bluevista.craftq3.engine.client.host.HostServices;
import dev.bluevista.craftq3.engine.client.host.SystemInfo;
import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.client.input.ClientInput;
import dev.bluevista.craftq3.engine.client.input.KeyTarget;
import dev.bluevista.craftq3.engine.client.net.ClientConnection;
import dev.bluevista.craftq3.engine.client.net.RemoteAddress;
import dev.bluevista.craftq3.engine.client.net.ServerBrowser;
import dev.bluevista.craftq3.engine.client.net.UiBrowser;
import dev.bluevista.craftq3.engine.client.pipeline.DemoServerClock;
import dev.bluevista.craftq3.engine.client.video.Cinematics;
import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandRegistry;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.PureManifest;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessMessage;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessPacket;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.vmabi.layout.UiClientStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import dev.bluevista.craftq3.engine.vmabi.service.TimeServices;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One Quake III engine instance, free of Minecraft: the ui, the client game (local, hosted, remote
 * or demo), the optional local server and full-screen cinematics, driven by console commands
 * exactly as the original engine is ({@code map}, {@code devmap}, {@code spmap}, {@code
 * map_restart}, {@code connect}, {@code reconnect}, {@code disconnect}, {@code demo}, {@code
 * record}, {@code stoprecord}, {@code cinematic}, {@code quit}, {@code killserver}, {@code
 * vid_restart}, {@code snd_restart}, {@code cmd}, {@code rcon}, the server's commands such as
 * {@code addbot} and {@code kick}, and the ui's server browser commands).
 *
 * <h2>States</h2>
 *
 * {@link SessionState#MENU} → ({@code map}) {@link SessionState#LOADING} → {@link
 * SessionState#LOCAL} or {@link SessionState#HOSTING}; ({@code connect}) LOADING → {@link
 * SessionState#REMOTE}; ({@code demo}) LOADING → {@link SessionState#DEMO}; ({@code cinematic})
 * {@link SessionState#CINEMATIC}. A new level (map change, {@code reconnect}) passes through
 * LOADING again. {@code disconnect}, {@code killserver}, the end of a demo or cinematic, escape out
 * of a demo, and every error return to MENU.
 *
 * <h2>Threads</h2>
 *
 * The caller's thread is the client thread (the Fabric render thread; the headless main thread): it
 * creates the session and must make every call; the client console, cvars, input, the ui and cgame
 * VMs, sound and render submission are owned by it and checked with {@link ThreadOwner}. The local
 * server runs on its own {@value ServerRunner#THREAD_NAME} thread ({@link ServerRunner}) at its
 * {@code sv_fps}, owning the server console, cvars, qagame and botlib. The two halves share only: a
 * {@link LoopbackTransport} (the same protocol-68 path a remote client uses), the server console's
 * lock-free inbox (commands such as {@code map} or {@code addbot} typed on the client go there as
 * text), the published {@link LocalServer.Status} and a queue of the server's console lines. Client
 * cvars the server needs are forwarded as {@code set} commands and the client mirror is updated
 * from the serverinfo and systeminfo configstrings ({@link CvarMirror}). With {@link
 * SessionOptions#synchronousServer()} the server is stepped inside {@link #frame} instead.
 *
 * <h2>Errors</h2>
 *
 * A ui, cgame or qagame fault, {@code trap_Error}, a lost connection or a server disconnect ends
 * the game with {@code ERR_DROP} semantics: the game and the local server are torn down, the
 * message is placed in {@code com_errorMessage} for the ui and the main menu opens. Nothing
 * propagates to the caller except {@link Error}s (resource exhaustion), including one that ended
 * the server thread.
 */
public final class EngineSession implements AutoCloseable {
  /** Longest frame step the client simulates ({@code cls.frametime} clamp). */
  public static final int MAX_FRAME_MILLIS = 200;

  /** How long a {@code map} may take to produce a level before the session gives up. */
  public static final int LOADING_TIMEOUT_MILLIS = 120_000;

  /** Ports {@code map} tries from {@code net_port} on, as the original's socket setup. */
  public static final int PORT_ATTEMPTS = 10;

  /** Fast challenge retries over loopback (the server may still be spawning). */
  static final ClientConnection.Settings LOOPBACK_SETTINGS =
      new ClientConnection.Settings(100, 30_000, 256, 64);

  /** The commands that run on the server's console (forwarded there as text). */
  static final List<String> SERVER_COMMANDS =
      List.of(
          "map_restart",
          "addbot",
          "botlist",
          "kick",
          "clientkick",
          "status",
          "serverinfo",
          "systeminfo",
          "dumpuser",
          "entitylist",
          "forceteam",
          "game_memory",
          "addip",
          "removeip",
          "listip",
          "heartbeat",
          "abort_podium",
          "say");

  private static final Log LOG = Log.get(EngineSession.class);
  private static final int UI_RESTARTS = 3;

  private final SessionHost host;
  private final SessionOptions options;
  private final ThreadOwner owner = new ThreadOwner("client");
  private final ClientConsole output = new ClientConsole();
  private final Console console;
  private final SearchPath searchPath;
  private final PureManifest manifest;
  private final SessionFiles files;
  private final HomeStore home;
  private final TimeServices time;
  private final Random random;
  private final CvarMirror mirror;
  private final ServerBrowser browser;
  private final ConcurrentLinkedQueue<String> serverLines = new ConcurrentLinkedQueue<>();
  private final ArrayDeque<Runnable> transitions = new ArrayDeque<>();
  private final long startMillis;
  private final int qport;
  private ClientContent content;
  private GameClient game;
  private ServerRunner server;
  private LoopbackTransport loopbackClient;
  private boolean hosting;
  private InetSocketAddress hostedAddress;
  private PendingLevel pendingLevel;
  private Resolving resolving;
  private Cinematics cinematics;
  private int cinematic = -1;
  private SessionState state = SessionState.MENU;
  private long lastFrame;
  private int realtime;
  private int frameMillis;
  private int syncCounter;
  private boolean contentPure;
  private List<Integer> contentPaks = List.of();
  private String lastServer = "";
  private int seenDrops;
  private int uiRestarts;
  private boolean uiBroken;
  private PacketTransport rconSocket;
  private boolean quit;
  private boolean closed;
  private long framesRun;
  private long serverDegradations;

  /** A {@code map} waiting for the server to spawn the level. */
  private record PendingLevel(int spawns, int drops, int sync, long started, String map) {}

  /** A remote address lookup in flight. */
  private record Resolving(
      String name, CompletableFuture<InetSocketAddress> address, long started) {}

  private EngineSession(SessionHost host, SessionOptions options) throws IOException {
    this.host = Objects.requireNonNull(host);
    this.options = Objects.requireNonNull(options);
    owner.claim();
    output.mirror(host::log);
    long seed = options.seed() != 0 ? options.seed() : System.nanoTime();
    random = new Random(seed);
    qport = random.nextInt(65536);
    searchPath = SearchPath.mount(host.installRoot(), host.game());
    manifest = new PureManifest(searchPath);
    home = SessionFiles.openHome(host.homeDirectory(), host.game(), output::print);
    files = new SessionFiles(home, () -> content == null ? manifest.view() : content.view);
    console = Console.client(owner, output, files.configSource(), files.configSink());
    time = new TimeServices(host.clock(), host.wallClock());
    startMillis = host.clock().millis();
    lastFrame = startMillis;
    registerCvars();
    content = newContent(manifest.view());
    browser = new ServerBrowser(console.cvars(), savedFiles(), output::print);
    browser.registerCommands(console.commands());
    registerCommands();
    mirror = new CvarMirror(console.cvars());
    mirror.captureClientOwned();
  }

  /**
   * Starts a session: mounts the game, runs the startup configs ({@code default.cfg}, {@code
   * q3config.cfg} from the home store or the install, {@code autoexec.cfg}) with the command line's
   * {@code set} lines before and after them, boots the ui to its main menu and queues the remaining
   * command-line commands. Must be called on the client thread.
   *
   * @throws IOException when the installation cannot be mounted
   */
  public static EngineSession start(SessionHost host, SessionOptions options) throws IOException {
    EngineSession session = new EngineSession(host, options);
    try {
      session.boot();
    } catch (RuntimeException failure) {
      session.close();
      throw failure;
    }
    return session;
  }

  private void registerCvars() {
    CvarTable cvars = console.cvars();
    cvars.register("com_errorMessage", "", 0);
    cvars.register("net_enabled", options.netEnabled() ? "1" : "0", CvarFlags.ARCHIVE);
    cvars.register("net_ip", "0.0.0.0", 0);
    cvars.register("net_port", Integer.toString(RemoteAddress.DEFAULT_PORT), 0);
    cvars.register("nextmap", "", 0);
    cvars.register("nextdemo", "", 0);
    cvars.register("cl_demoRecording", "0", CvarFlags.ROM);
    cvars.register("cl_demoPlaying", "0", CvarFlags.ROM);
    cvars.register("cl_demoName", "", CvarFlags.ROM);
    cvars.register("rconPassword", "", CvarFlags.TEMP);
    cvars.register("rconAddress", "", 0);
  }

  private void boot() {
    List<String> commandLine = options.commandLine();
    applySetLines(commandLine);
    console.queueStartupConfigs(options.safeMode());
    runBufferToCompletion();
    applySetLines(commandLine);
    console.cvars().applyLatched();
    ClientInput input = services().input();
    input.setConsoleTarget(output);
    input.setHooks(inputHooks());
    startUi(UiHost.Menu.MAIN);
    boolean commands = false;
    for (String line : commandLine) {
      if (CommandLine.isSet(line)) continue;
      console.buffer().add(line + "\n");
      commands = true;
    }
    if (!commands && options.attract()) {
      console.buffer().add("cinematic idlogo.RoQ\n");
      if (console.cvars().integer("com_introplayed") == 0) {
        console.cvars().set("com_introplayed", "1");
        console.cvars().set("nextmap", "cinematic intro.RoQ");
      }
    }
    LOG.info("Session started on " + host.game());
  }

  private void applySetLines(List<String> lines) {
    for (String line : lines) if (CommandLine.isSet(line)) console.commands().execute(line);
  }

  /** Runs the startup scripts to the end ({@code wait} only defers within this loop). */
  private void runBufferToCompletion() {
    for (int i = 0; i < 1024 && (!console.buffer().isEmpty() || console.inbox().pending() > 0); i++)
      console.frame();
  }

  private ServerBrowser.SavedFiles savedFiles() {
    return new ServerBrowser.SavedFiles() {
      @Override
      public Optional<byte[]> read(String path) throws IOException {
        if (home == null) return Optional.empty();
        Optional<VirtualPath> parsed = VirtualPath.parse(path);
        return parsed.isEmpty() ? Optional.empty() : home.readIfPresent(parsed.get());
      }

      @Override
      public void write(String path, byte[] data) throws IOException {
        if (home == null) throw new IOException("No writable game home");
        home.write(VirtualPath.of(path), data);
      }
    };
  }

  // ================================================================ content

  private ClientContent newContent(FileView view) {
    ClientContent next =
        new ClientContent(
            console,
            output,
            view,
            home,
            host.installRoot(),
            host,
            time,
            options.decodeImages(),
            uiLink());
    output.attach(console.buffer(), this::chatting);
    return next;
  }

  /** Rebuilds the content for another file view (a pure server's packs), restarting the ui. */
  private void rebuildContent(FileView view, UiHost.Menu menu) {
    ClientInput oldInput = services().input();
    int catchers = oldInput.catchers();
    content.close();
    content = newContent(view);
    ClientInput input = services().input();
    input.setConsoleTarget(output);
    input.setHooks(inputHooks());
    input.setCatchers(catchers & ClientInput.KEYCATCH_CONSOLE);
    startUi(menu);
  }

  private void startUi(UiHost.Menu menu) {
    ClientInput input = services().input();
    try {
      content.startUi(menu);
      input.setUiTarget(content.ui);
      uiBroken = false;
      host.requestHostUi(false);
    } catch (IOException | RuntimeException failure) {
      input.setUiTarget(KeyTarget.NONE);
      uiBroken = true;
      print("Couldn't start the ui: " + message(failure));
      input.setCatchers(input.catchers() | ClientInput.KEYCATCH_CONSOLE);
      host.requestHostUi(true);
    }
  }

  private UiHost.Link uiLink() {
    return new UiHost.Link() {
      @Override
      public UiClientStateLayout.UiClientState clientState() {
        return uiClientState();
      }

      @Override
      public String configString(int index) {
        return game == null ? "" : game.configString(index);
      }

      @Override
      public UiBrowser browser() {
        return browser;
      }
    };
  }

  private UiClientStateLayout.UiClientState uiClientState() {
    if (game != null) return game.uiState();
    if (cinematic >= 0)
      return new UiClientStateLayout.UiClientState(
          UiClientStateLayout.CA_CINEMATIC, 0, -1, "", "", "");
    if (pendingLevel != null)
      return new UiClientStateLayout.UiClientState(
          UiClientStateLayout.CA_CONNECTING, 0, -1, "localhost", "", "Starting server...");
    if (resolving != null)
      return new UiClientStateLayout.UiClientState(
          UiClientStateLayout.CA_CONNECTING,
          0,
          -1,
          resolving.name(),
          "",
          "Looking up " + resolving.name() + "...");
    return UiClientStateLayout.UiClientState.disconnected();
  }

  private boolean chatting() {
    return game != null && game.kind != GameClient.Kind.DEMO && game.active();
  }

  private ClientInput.Hooks inputHooks() {
    return new ClientInput.Hooks() {
      @Override
      public void escapeToMenu() {
        if (cinematic >= 0) {
          transitions.add(() -> stopCinematic(true));
          return;
        }
        if (game != null && game.kind != GameClient.Kind.DEMO && game.active()) {
          if (content.uiRunning()) content.ui.setActiveMenu(UiHost.Menu.INGAME);
          return;
        }
        transitions.add(
            () -> {
              disconnect();
              stopAllSounds();
            });
      }

      @Override
      public void cgameCatcherCleared() {
        if (game != null && game.cgameRunning())
          game.cgame().orElseThrow().eventHandling(CgameHost.CGAME_EVENT_NONE);
      }

      @Override
      public boolean disconnected() {
        return game == null || !game.active();
      }
    };
  }

  // ================================================================ commands

  private void registerCommands() {
    CommandRegistry commands = console.commands();
    for (String name : List.of("map", "devmap", "spmap", "spdevmap"))
      commands.register(name, this::mapCommand);
    for (String name : SERVER_COMMANDS) commands.register(name, this::serverCommand);
    commands.register("killserver", args -> later(this::killServer));
    commands.register("connect", this::connectCommand);
    commands.register("reconnect", args -> later(this::reconnect));
    commands.register("disconnect", args -> later(this::disconnect));
    commands.register("demo", this::demoCommand);
    commands.register("record", this::recordCommand);
    commands.register("stoprecord", args -> later(this::stopRecord));
    commands.register("cinematic", this::cinematicCommand);
    commands.register("quit", args -> later(this::requestQuit));
    commands.register("vid_restart", args -> later(this::videoRestart));
    commands.register("snd_restart", args -> later(this::soundRestart));
    commands.register("cmd", this::forwardCommand);
    commands.register("rcon", this::rconCommand);
    commands.register(
        "host_ui", args -> host.requestHostUi(args.argc() < 2 || !args.argv(1).equals("0")));
    commands.addFallback(this::gameCommandFallback);
  }

  /** Runs {@code action} after the current command, outside any VM call. */
  private void later(Runnable action) {
    transitions.add(action);
    console.buffer().breakFrame();
  }

  /** {@code CL_GameCommand}, {@code UI_GameCommand} and {@code CL_ForwardCommandToServer}. */
  private boolean gameCommandFallback(CommandArgs args) {
    if (game != null && game.cgameRunning() && game.cgame().orElseThrow().consoleCommand(args))
      return true;
    if (content.uiRunning() && content.ui.consoleCommand(args, realtime)) return true;
    if (game != null && game.kind != GameClient.Kind.DEMO && game.connection().isPresent()) {
      ClientConnection connection = game.connection().get();
      if (connection.state() == ClientConnection.State.ACTIVE
          || connection.state() == ClientConnection.State.PRIMED) {
        connection.command(args.text().strip());
        return true;
      }
    }
    return false;
  }

  private void mapCommand(CommandArgs args) {
    if (args.argc() < 2) {
      print("USAGE: " + args.name() + " <map name>");
      return;
    }
    String name = args.argv(1);
    String path = "maps/" + stripBsp(name) + ".bsp";
    if (!files.exists(path)) {
      print("Can't find map " + path);
      return;
    }
    if (options.servers() == null) {
      print("This build has no local server");
      return;
    }
    String text = args.text().strip();
    boolean singlePlayer = args.name().toLowerCase(Locale.ROOT).startsWith("sp");
    later(() -> startLevel(text, stripBsp(name), singlePlayer));
  }

  private static String stripBsp(String name) {
    String value = name.replace('\\', '/');
    if (value.regionMatches(true, 0, "maps/", 0, 5)) value = value.substring(5);
    if (value.toLowerCase(Locale.ROOT).endsWith(".bsp"))
      value = value.substring(0, value.length() - 4);
    return value;
  }

  private void serverCommand(CommandArgs args) {
    if (server == null) {
      if (game != null && game.kind == GameClient.Kind.REMOTE && game.connection().isPresent()) {
        game.connection().get().command(args.text().strip());
        return;
      }
      print("Server is not running.");
      return;
    }
    forwardCvars();
    server.post(args.text().strip());
  }

  private void connectCommand(CommandArgs args) {
    if (args.argc() != 2) {
      print("usage: connect [server]");
      return;
    }
    String name = args.argv(1);
    if (!isLocalName(name)) {
      try {
        RemoteAddress.parse(name);
      } catch (IllegalArgumentException invalid) {
        print("Bad server address: " + name);
        return;
      }
    }
    later(() -> connect(name));
  }

  private static boolean isLocalName(String name) {
    return name.equalsIgnoreCase("localhost") || name.equalsIgnoreCase("loopback");
  }

  private void demoCommand(CommandArgs args) {
    if (args.argc() != 2) {
      print("demo <demoname>");
      return;
    }
    String name = args.argv(1);
    later(() -> playDemo(name));
  }

  private void recordCommand(CommandArgs args) {
    if (args.argc() > 2) {
      print("record <demoname>");
      return;
    }
    String name = args.argc() == 2 ? args.argv(1) : "";
    later(() -> record(name));
  }

  private void cinematicCommand(CommandArgs args) {
    if (args.argc() < 2) {
      print("cinematic <movie> [0|1 (hold)|2 (loop)]");
      return;
    }
    String name = args.argv(1);
    String option = args.argc() > 2 ? args.argv(2) : "";
    int flags = Cinematics.SYSTEM;
    if (option.startsWith("1")
        || name.equalsIgnoreCase("demoend.roq")
        || name.equalsIgnoreCase("end.roq")) flags |= Cinematics.HOLD;
    if (option.startsWith("2")) flags |= Cinematics.LOOP;
    int chosen = flags;
    later(() -> playCinematic(name, chosen));
  }

  private void forwardCommand(CommandArgs args) {
    if (game == null || game.kind == GameClient.Kind.DEMO || game.connection().isEmpty()) {
      print("Not connected to a server.");
      return;
    }
    if (args.argc() > 1) game.connection().get().command(args.argsFrom(1));
  }

  private void rconCommand(CommandArgs args) {
    String password = console.cvars().string("rconPassword");
    if (password.isEmpty()) {
      print("You must set 'rconpassword' before issuing an rcon command.");
      return;
    }
    if (args.argc() < 2) return;
    String command = args.argsFrom(1);
    String target = console.cvars().string("rconAddress");
    if (target.isEmpty() && server != null && game != null && game.kind == GameClient.Kind.LOCAL) {
      server.post(command);
      return;
    }
    if (target.isEmpty())
      target = game != null && game.kind == GameClient.Kind.REMOTE ? lastServer : "";
    if (target.isEmpty()) {
      print("You must either be connected, or set the 'rconAddress' cvar to issue rcon commands");
      return;
    }
    try {
      InetSocketAddress address = resolveNow(target);
      if (rconSocket == null)
        rconSocket = host.transports().ephemeral(!(address.getAddress() instanceof Inet4Address));
      byte[] packet = ConnectionlessPacket.text("rcon " + password + " " + command);
      rconSocket.send(address, packet, 0, packet.length);
    } catch (IOException | RuntimeException failure) {
      print("rcon failed: " + message(failure));
    }
  }

  // ================================================================ transitions

  /** {@code map}: makes sure the local server runs, then hands it the command. */
  private void startLevel(String commandText, String map, boolean singlePlayer) {
    stopCinematicSilently();
    if (game != null && game.kind != GameClient.Kind.LOCAL) closeGame(true);
    if (resolving != null) resolving = null;
    if (server == null && !startServer(singlePlayer)) return;
    console.cvars().set("com_errorMessage", "");
    forwardCvars();
    LocalServer.Status status = server.status();
    int sync = ++syncCounter;
    server.post(commandText + "\n" + LocalServer.SYNC_COMMAND + " " + sync);
    pendingLevel = new PendingLevel(status.spawns(), status.drops(), sync, lastFrame, map);
    lastServer = "localhost";
  }

  private boolean startServer(boolean singlePlayer) {
    LocalServer created;
    boolean dedicated = console.cvars().integer("dedicated") != 0;
    try {
      created =
          options
              .servers()
              .create(
                  new LocalServer.Context(
                      searchPath,
                      home,
                      host.installRoot(),
                      host.game(),
                      new ThreadOwner("server"),
                      host.clock(),
                      host.wallClock(),
                      random.nextLong(),
                      serverLines::add,
                      dedicated));
    } catch (IOException | RuntimeException failure) {
      print("Couldn't start the server: " + message(failure));
      return false;
    }
    server = new ServerRunner(created, host.clock(), !options.synchronousServer());
    LoopbackTransport.Pair link = LoopbackTransport.pair();
    loopbackClient = link.client();
    server.submit(s -> s.addTransport(link.server(), true));
    List<String> initial = mirror.all();
    if (!initial.isEmpty()) server.post(String.join("\n", initial));
    seenDrops = 0;
    hosting = false;
    hostedAddress = null;
    if (!singlePlayer && console.cvars().integer("net_enabled") != 0) listen();
    server.start();
    console.cvars().set("sv_running", "1");
    return true;
  }

  /** Opens the hosting socket on {@code net_ip}:{@code net_port} (trying the next ports). */
  private void listen() {
    String ip = console.cvars().string("net_ip");
    int port = console.cvars().integer("net_port");
    InetAddress bind;
    try {
      bind = InetAddress.getByName(ip.isEmpty() ? "0.0.0.0" : ip);
    } catch (IOException invalid) {
      print("Bad net_ip " + ip + ": " + invalid.getMessage());
      return;
    }
    for (int attempt = 0; attempt < (port == 0 ? 1 : PORT_ATTEMPTS); attempt++) {
      try {
        PacketTransport socket =
            host.transports().listen(new InetSocketAddress(bind, port + attempt));
        SocketAddress local = socket.localAddress();
        server.submit(s -> s.addTransport(socket, false));
        hosting = true;
        hostedAddress = local instanceof InetSocketAddress inet ? inet : null;
        if (hostedAddress != null)
          console.cvars().set("net_port", Integer.toString(hostedAddress.getPort()));
        print("Hosting on " + local);
        return;
      } catch (IOException | RuntimeException busy) {
        LOG.debug("Port " + (port + attempt) + " unavailable: " + busy.getMessage());
      }
    }
    print("Couldn't open a server socket on " + ip + ":" + port + "; playing locally only");
  }

  /** {@code connect}: a remote server (resolved off-thread) or the local one. */
  private void connect(String name) {
    stopCinematicSilently();
    console.cvars().set("com_errorMessage", "");
    if (isLocalName(name)) {
      if (server == null) {
        print("Server is not running.");
        return;
      }
      if (game != null) closeGame(true);
      openLocalGame();
      return;
    }
    closeGame(true);
    stopServer();
    lastServer = name;
    RemoteAddress address = RemoteAddress.parse(name);
    CompletableFuture<InetSocketAddress> lookup;
    if (options.synchronousServer()) {
      lookup = new CompletableFuture<>();
      try {
        lookup.complete(resolve(address));
      } catch (IOException failure) {
        lookup.completeExceptionally(failure);
      }
    } else {
      lookup =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return resolve(address);
                } catch (IOException failure) {
                  throw new java.util.concurrent.CompletionException(failure);
                }
              },
              command -> Thread.ofVirtual().name("cq3-resolve").start(command));
    }
    resolving = new Resolving(name, lookup, lastFrame);
    print("Connecting to " + name + "...");
  }

  private static InetSocketAddress resolve(RemoteAddress address) throws IOException {
    InetAddress[] all = InetAddress.getAllByName(address.host());
    InetAddress chosen =
        Arrays.stream(all).filter(Inet4Address.class::isInstance).findFirst().orElse(all[0]);
    return new InetSocketAddress(chosen, address.port());
  }

  private static InetSocketAddress resolveNow(String text) throws IOException {
    return resolve(RemoteAddress.parse(text));
  }

  private void openLocalGame() {
    LoopbackTransport transport = loopbackClient;
    game =
        GameClient.network(
            GameClient.Kind.LOCAL,
            "localhost",
            gameSession(),
            (pipeline, commands, listener) ->
                new ClientConnection(
                    transport,
                    transport.remoteAddress(),
                    "localhost",
                    userInfo(),
                    random.nextInt(),
                    qport,
                    pipeline,
                    commands,
                    console.cvars(),
                    LOOPBACK_SETTINGS,
                    listener,
                    lastFrame));
  }

  private void openRemoteGame(InetSocketAddress address, String name) throws IOException {
    PacketTransport socket = host.transports().connect(address);
    SocketAddress peer = address;
    try {
      game =
          GameClient.network(
              GameClient.Kind.REMOTE,
              name,
              gameSession(),
              (pipeline, commands, listener) ->
                  new ClientConnection(
                      socket,
                      peer,
                      name,
                      userInfo(),
                      random.nextInt(),
                      qport,
                      pipeline,
                      commands,
                      console.cvars(),
                      ClientConnection.Settings.DEFAULT,
                      listener,
                      lastFrame));
    } catch (RuntimeException failure) {
      socket.close();
      throw failure;
    }
  }

  private Map<String, String> userInfo() {
    console.cvars().takeModified(CvarFlags.USERINFO);
    String info = console.cvars().info(CvarFlags.USERINFO);
    try {
      return InfoString.parse(info, Protocol68.MAX_INFO_STRING);
    } catch (IllegalArgumentException malformed) {
      return new HashMap<>(Map.of("name", "UnnamedPlayer"));
    }
  }

  private void reconnect() {
    if (lastServer.isEmpty()) {
      print("Can't reconnect to nothing.");
      return;
    }
    connect(lastServer);
  }

  /** {@code disconnect}: ends the game and the local server, back to the main menu. */
  private void disconnect() {
    boolean wasPlaying = game != null || server != null || cinematic >= 0 || resolving != null;
    stopCinematicSilently();
    closeGame(true);
    stopServer();
    resolving = null;
    if (wasPlaying || state != SessionState.MENU) openMainMenu();
  }

  private void killServer() {
    if (server == null) {
      print("Server is not running.");
      return;
    }
    if (game != null && game.kind == GameClient.Kind.LOCAL) closeGame(true);
    stopServer();
    openMainMenu();
  }

  private void playDemo(String requested) {
    String name;
    try {
      name = SessionFiles.demoFileName(requested);
    } catch (IllegalArgumentException invalid) {
      print(invalid.getMessage());
      return;
    }
    String path = findDemo(name);
    if (path == null) {
      print("couldn't open demos/" + name);
      return;
    }
    stopCinematicSilently();
    closeGame(true);
    stopServer();
    resolving = null;
    console.cvars().set("com_errorMessage", "");
    try {
      Optional<InputStream> stream = files.open(path);
      if (stream.isEmpty()) throw new IOException("couldn't open " + path);
      InputStream input = stream.get();
      String fileName = path.substring(path.lastIndexOf('/') + 1);
      game =
          GameClient.demo(
              fileName,
              gameSession(),
              pipeline -> {
                try {
                  return DemoPlayback.open(fileName, input, pipeline);
                } catch (IOException failure) {
                  try {
                    input.close();
                  } catch (IOException ignored) {
                    LOG.debug("Demo stream close failed");
                  }
                  throw new java.io.UncheckedIOException(failure);
                }
              });
    } catch (java.io.UncheckedIOException failure) {
      drop(message(failure.getCause()));
      return;
    } catch (IOException | RuntimeException failure) {
      drop(message(failure));
      return;
    }
    console.cvars().set("cl_demoPlaying", "1");
    console.cvars().set("cl_demoName", name);
    if (content.uiRunning()) content.ui.setActiveMenu(UiHost.Menu.NONE);
    print("Playing demo " + path);
  }

  /** {@code demos/<name>}, adding the protocol extensions the original tries. */
  private String findDemo(String name) {
    List<String> candidates = new ArrayList<>();
    String lower = name.toLowerCase(Locale.ROOT);
    if (SessionFiles.hasDemoExtension(name)) {
      candidates.add(name);
      if (lower.endsWith(".dm3")) name = name.substring(0, name.length() - 4);
      else return files.exists("demos/" + name) ? "demos/" + name : null;
    }
    for (int protocol : new int[] {68, 67, 66}) candidates.add(name + ".dm_" + protocol);
    for (String candidate : candidates)
      if (files.exists("demos/" + candidate)) return "demos/" + candidate;
    return null;
  }

  private void record(String requested) {
    if (game == null || game.kind == GameClient.Kind.DEMO) {
      print(
          game == null
              ? "You must be in a level to record."
              : "Can't record during demo playback.");
      return;
    }
    if (game.recording()) {
      print("Already recording.");
      return;
    }
    String name;
    if (requested.isEmpty()) {
      List<String> existing = files.list("demos", ".dm_68");
      name = null;
      for (int number = 0; number < 10000; number++) {
        String candidate = String.format(Locale.ROOT, "demo%04d.dm_68", number);
        if (existing.stream().noneMatch(candidate::equalsIgnoreCase)) {
          name = candidate;
          break;
        }
      }
      if (name == null) {
        print("Couldn't find a free demo name");
        return;
      }
    } else {
      try {
        name = SessionFiles.demoFileName(requested);
      } catch (IllegalArgumentException invalid) {
        print(invalid.getMessage());
        return;
      }
      if (!name.toLowerCase(Locale.ROOT).endsWith(".dm_68")) name += ".dm_68";
    }
    try {
      SessionFiles.Recording file = files.create("demos/" + name);
      try {
        game.startRecording(name, file);
      } catch (IOException | RuntimeException failure) {
        file.close();
        throw failure;
      }
    } catch (IOException | RuntimeException failure) {
      print("Couldn't record " + name + ": " + message(failure));
      return;
    }
    console.cvars().set("cl_demoRecording", "1");
    console.cvars().set("cl_demoName", name);
    print("recording to demos/" + name + ".");
  }

  private void stopRecord() {
    if (game == null || !game.recording()) {
      print("Not recording a demo.");
      return;
    }
    game.stopRecording().ifPresent(this::print);
    console.cvars().set("cl_demoRecording", "0");
    console.cvars().set("cl_demoName", "");
  }

  private void playCinematic(String name, int flags) {
    closeGame(true);
    stopServer();
    resolving = null;
    stopCinematicSilently();
    stopAllSounds();
    if (cinematics == null)
      cinematics = new Cinematics(this::openGameFile, services().sound(), output::print);
    cinematics.setVolume(console.cvars().value("s_volume"));
    cinematic = cinematics.play(name, 0, 0, 640, 480, flags, realtime);
    if (cinematic < 0) {
      openMainMenu();
      return;
    }
    ClientInput input = services().input();
    input.setCatchers(input.catchers() & ClientInput.KEYCATCH_CONSOLE);
    if (content.uiRunning()) content.ui.setActiveMenu(UiHost.Menu.NONE);
  }

  private InputStream openGameFile(String path) throws IOException {
    Optional<InputStream> stream = files.open(path);
    if (stream.isEmpty()) throw new java.nio.file.NoSuchFileException(path);
    return stream.get();
  }

  /** Ends the cinematic; {@code runNext} executes {@code nextmap} as the original does. */
  private void stopCinematic(boolean runNext) {
    if (cinematic < 0) return;
    cinematics.stop(cinematic);
    cinematic = -1;
    stopAllSounds();
    if (runNext) {
      String next = console.cvars().string("nextmap");
      if (!next.isBlank()) {
        console.cvars().set("nextmap", "");
        console.buffer().add(next + "\n");
      }
    }
    if (game == null && pendingLevel == null) openMainMenu();
  }

  private void stopCinematicSilently() {
    if (cinematic < 0) return;
    cinematics.stop(cinematic);
    cinematic = -1;
  }

  private void requestQuit() {
    quit = true;
    disconnect();
    host.requestQuit();
  }

  private void videoRestart() {
    services().renderer().setVideoSize(host.windowWidth(), host.windowHeight());
    console.cvars().applyLatched();
    boolean inGame = game != null && game.active();
    try {
      content.ui.shutdown();
      startUi(inGame ? UiHost.Menu.NONE : UiHost.Menu.MAIN);
      if (game != null) game.restartCgame();
    } catch (IOException | RuntimeException failure) {
      drop(message(failure));
    }
  }

  private void soundRestart() {
    stopAllSounds();
    videoRestart();
  }

  private void stopAllSounds() {
    services().sound().stopAllSounds();
  }

  /** Ends the current client game (and its recording). */
  private void closeGame(boolean sendDisconnect) {
    if (game == null) return;
    GameClient ending = game;
    game = null;
    try {
      if (sendDisconnect) ending.disconnect(lastFrame);
      else ending.close();
    } catch (RuntimeException failure) {
      LOG.warn("Game cleanup failed: " + failure);
    }
    if (server != null) server.wake();
    console.cvars().set("cl_demoRecording", "0");
    console.cvars().set("cl_demoPlaying", "0");
    console.cvars().set("cl_demoName", "");
    services().unloadWorld();
    services().input().setCgameTarget(KeyTarget.NONE);
    mirror.resetServerInfo();
    stopAllSounds();
    if (contentPure) {
      manifest.setServerPaks(List.of());
      manifest.restart(0);
      contentPure = false;
      contentPaks = List.of();
      rebuildContent(manifest.view(), UiHost.Menu.MAIN);
    }
  }

  private void stopServer() {
    pendingLevel = null;
    if (server == null) return;
    ServerRunner stopping = server;
    server = null;
    serverDegradations += stopping.status().degradations();
    stopping.close();
    loopbackClient = null;
    hosting = false;
    hostedAddress = null;
    console.cvars().set("sv_running", "0");
    drainServerOutput();
  }

  private void openMainMenu() {
    ClientInput input = services().input();
    input.setCatchers(input.catchers() & ClientInput.KEYCATCH_CONSOLE);
    if (content.uiRunning()) content.ui.setActiveMenu(UiHost.Menu.MAIN);
  }

  /**
   * {@code ERR_DROP}: ends the game and the local server, shows {@code message} in the ui's error
   * screen ({@code com_errorMessage}) and returns to the main menu, restarting a failed ui.
   */
  private void drop(String message) {
    print("********************");
    print("ERROR: " + message);
    print("********************");
    transitions.clear();
    try {
      stopCinematicSilently();
      closeGame(false);
      stopServer();
      resolving = null;
    } catch (RuntimeException cleanup) {
      LOG.warn("Cleanup after an error failed: " + cleanup);
    }
    console.cvars().set("com_errorMessage", message);
    console.buffer().clear();
    if (!content.uiRunning() && uiRestarts < UI_RESTARTS) {
      uiRestarts++;
      content.ui.shutdown();
      startUi(UiHost.Menu.MAIN);
    } else openMainMenu();
  }

  // ================================================================ game callbacks

  private GameClient.Session gameSession() {
    return new GameClient.Session() {
      @Override
      public HostServices services() {
        return EngineSession.this.services();
      }

      @Override
      public void print(String line) {
        EngineSession.this.print(line);
      }

      @Override
      public void levelStarting(GameClient starting, ServerMessageCodec.GameState gameState) {
        prepareLevel(starting, gameState);
      }

      @Override
      public void systemInfo(GameClient source, String info) {
        SystemInfo.apply(console.cvars(), info, EngineSession.this::print);
        mirror.baseline();
      }

      @Override
      public String pureChecksums() {
        try {
          return manifest.referencedPureChecksums();
        } catch (IllegalStateException tooMany) {
          return null;
        }
      }
    };
  }

  /** {@code CL_ParseGamestate} and the pure pack setup before a cgame loads. */
  private void prepareLevel(GameClient starting, ServerMessageCodec.GameState gameState) {
    ClientInput input = services().input();
    input.setViewAngles(0, 0, 0);
    input.setCatchers(input.catchers() & ClientInput.KEYCATCH_CONSOLE);
    if (content.uiRunning()) content.ui.setActiveMenu(UiHost.Menu.NONE);
    if (starting.kind == GameClient.Kind.DEMO) return;
    String systemInfo = gameState.configstrings().getOrDefault(Protocol68.CS_SYSTEMINFO, "");
    Map<String, String> values;
    try {
      values = InfoString.parse(systemInfo, Protocol68.BIG_INFO_STRING);
    } catch (IllegalArgumentException malformed) {
      values = Map.of();
    }
    List<Integer> paks = new ArrayList<>();
    if (starting.kind == GameClient.Kind.REMOTE && parseInt(values.get("sv_pure")) != 0)
      for (String word : values.getOrDefault("sv_paks", "").trim().split("\\s+"))
        if (!word.isEmpty()) paks.add(parseInt(word));
    manifest.setServerPaks(paks);
    manifest.restart(gameState.checksumFeed());
    boolean pure = !paks.isEmpty();
    if (pure != contentPure || (pure && !paks.equals(contentPaks))) {
      contentPure = pure;
      contentPaks = List.copyOf(paks);
      rebuildContent(manifest.view(), UiHost.Menu.NONE);
    }
    // The restart cleared references; the running ui module counts as referenced again.
    try {
      content.view.read(VirtualPath.of("vm/ui.qvm"));
    } catch (IOException | RuntimeException missing) {
      LOG.debug("No ui module to reference");
    }
  }

  private static int parseInt(String text) {
    if (text == null) return 0;
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException invalid) {
      return 0;
    }
  }

  // ================================================================ frame

  /** {@link #frame(long)} at the host clock's time. */
  public void frame() {
    frame(host.clock().millis());
  }

  /**
   * Runs one client frame at {@code nowMillis} (the host's monotonic clock): server output, console
   * commands and transitions, cvar forwarding, the server step (synchronous mode), the network or
   * demo, user commands, one rendered frame for the {@link
   * dev.bluevista.craftq3.engine.client.RenderSink} and the sound mix.
   */
  public void frame(long nowMillis) {
    owner.check();
    if (closed) return;
    rethrowServerFatal();
    frameMillis = Math.clamp(nowMillis - lastFrame, 0, MAX_FRAME_MILLIS);
    lastFrame = Math.max(lastFrame, nowMillis);
    realtime = (int) (lastFrame - startMillis);
    framesRun++;
    try {
      drainServerOutput();
      checkServer();
      runCommands();
      forwardCvars();
      if (server != null && !server.threaded()) {
        server.step(nowMillis);
        drainServerOutput();
        checkServer();
      }
      advanceConnection();
      OptionalInt serverTime = gameFrame(nowMillis);
      render(serverTime);
      browser.pump();
      pollRcon();
    } catch (GameClient.Drop failure) {
      drop(failure.getMessage());
    } catch (RuntimeException failure) {
      LOG.warn("Frame failed", failure);
      drop(message(failure));
    } catch (IOException failure) {
      drop(message(failure));
    }
    services().updateSound(frameMillis);
    if (server != null && server.threaded()) server.wake();
    updateState();
    rethrowServerFatal();
  }

  private void rethrowServerFatal() {
    if (server == null) return;
    Optional<Throwable> fatal = server.fatal();
    if (fatal.isPresent() && fatal.get() instanceof Error error) throw error;
  }

  private void runCommands() {
    for (int pass = 0; pass < 16; pass++) {
      console.frame();
      if (transitions.isEmpty()) return;
      while (!transitions.isEmpty()) transitions.poll().run();
    }
  }

  private void drainServerOutput() {
    for (String line; (line = serverLines.poll()) != null; ) output.print(line);
  }

  /** Reacts to the local server's published status: a dropped level ends the game. */
  private void checkServer() {
    if (server == null) return;
    LocalServer.Status status = server.status();
    if (status.drops() > seenDrops) {
      seenDrops = status.drops();
      if (game != null || pendingLevel != null) {
        throw new GameClient.Drop(
            status.dropReason().isEmpty() ? "Server dropped the level" : status.dropReason());
      }
    }
    if (status.phase() == LocalServer.Phase.CLOSED && game == null && pendingLevel == null) {
      stopServer();
      return;
    }
    if (pendingLevel != null) {
      PendingLevel waiting = pendingLevel;
      boolean spawned =
          status.spawns() > waiting.spawns() && status.phase() == LocalServer.Phase.RUNNING;
      if (spawned) {
        pendingLevel = null;
        if (game == null && console.cvars().integer("dedicated") == 0) openLocalGame();
      } else if (status.sync() >= waiting.sync()) {
        pendingLevel = null;
        print("Couldn't start map " + waiting.map());
        if (game == null) {
          stopServer();
          openMainMenu();
        }
      } else if (lastFrame - waiting.started() > LOADING_TIMEOUT_MILLIS) {
        pendingLevel = null;
        throw new GameClient.Drop("The server did not start " + waiting.map());
      }
    }
  }

  /** Forwards changed client cvars the server needs ({@link CvarMirror}). */
  private void forwardCvars() {
    if (server == null) return;
    List<String> changed = new ArrayList<>();
    mirror.changes(changed::add);
    if (!changed.isEmpty()) server.post(String.join("\n", changed));
  }

  /** Remote address lookup and the connection it starts. */
  private void advanceConnection() throws IOException {
    if (resolving == null) return;
    Resolving lookup = resolving;
    if (!lookup.address().isDone()) {
      if (lastFrame - lookup.started() > LOADING_TIMEOUT_MILLIS) {
        resolving = null;
        throw new GameClient.Drop("Couldn't resolve " + lookup.name());
      }
      return;
    }
    resolving = null;
    InetSocketAddress address;
    try {
      address = lookup.address().join();
    } catch (RuntimeException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      throw new GameClient.Drop("Bad server address " + lookup.name() + ": " + message(cause));
    }
    openRemoteGame(address, lookup.name());
  }

  private OptionalInt gameFrame(long nowMillis) throws IOException {
    if (cinematic >= 0) {
      int status = cinematics.run(cinematic, realtime);
      if (status == Cinematics.FMV_EOF) stopCinematic(true);
      return OptionalInt.empty();
    }
    if (game == null) return OptionalInt.empty();
    GameClient current = game;
    if (current.kind != GameClient.Kind.DEMO) {
      if (console.cvars().takeModified(CvarFlags.USERINFO))
        current
            .connection()
            .ifPresent(
                c -> c.command("userinfo \"" + console.cvars().info(CvarFlags.USERINFO) + "\""));
      current.pump(nowMillis);
      if (current.kind == GameClient.Kind.LOCAL)
        mirror.mirrorServerInfo(current.configString(Protocol68.CS_SERVERINFO), this::print);
    }
    if (game != current) return OptionalInt.empty();
    CvarTable cvars = console.cvars();
    OptionalInt serverTime =
        current.serverTime(
            realtime,
            cvars.value("timescale"),
            frameMillis,
            (int) host.clock().millis(),
            new GameClient.Demo(
                cvars.integer("cl_timeNudge"),
                cvars.integer("cl_freezeDemo") != 0,
                cvars.integer("timedemo") != 0));
    if (current.kind == GameClient.Kind.DEMO) {
      if (current.demoEnded()) {
        finishDemo();
        return OptionalInt.empty();
      }
      if (serverTime.isPresent()) current.addCommand(UserCmd.idle(serverTime.getAsInt()));
      return serverTime;
    }
    if (current.cgameRunning()) {
      CgameHost cgame = current.cgame().orElseThrow();
      int commandTime = serverTime.orElse(0);
      UserCmd command =
          services()
              .input()
              .createCommand(
                  commandTime,
                  realtime,
                  cgame.selectedWeapon(),
                  cgame.sensitivityScale(),
                  current.deltaPitch());
      current.addCommand(command);
    }
    current
        .recordingFailure()
        .ifPresent(
            failure -> {
              print("Demo recording failed: " + failure.getMessage());
              stopRecord();
            });
    return serverTime;
  }

  private void finishDemo() {
    GameClient ending = game;
    ending
        .demo()
        .ifPresent(
            playback -> {
              if (console.cvars().integer("timedemo") != 0) {
                DemoServerClock.Timedemo stats = playback.clock().timedemo();
                int elapsed = (int) host.clock().millis() - stats.start();
                if (stats.frames() > 0 && elapsed > 0)
                  print(
                      String.format(
                          Locale.ROOT,
                          "%d frames, %.1f seconds: %.1f fps",
                          stats.frames(),
                          elapsed / 1000.0,
                          stats.frames() * 1000.0 / elapsed));
              }
            });
    demosFinished++;
    closeGame(false);
    String next = console.cvars().string("nextdemo");
    if (!next.isEmpty()) {
      console.cvars().set("nextdemo", "");
      console.buffer().add(next + "\n");
    } else openMainMenu();
  }

  private long demosFinished;

  private void render(OptionalInt serverTime) {
    HostServices services = services();
    var renderer = services.renderer();
    if (renderer.width() != host.windowWidth() || renderer.height() != host.windowHeight())
      renderer.setVideoSize(host.windowWidth(), host.windowHeight());
    renderer.beginFrame(realtime);
    UiHost ui = content.ui;
    boolean uiRunning = content.uiRunning();
    int catchers = services.input().catchers();
    if (cinematic >= 0) {
      cinematics.draw(cinematic, renderer.width(), renderer.height()).ifPresent(renderer::image);
    } else {
      boolean fullscreen = uiRunning && ui.isFullscreen();
      if (!fullscreen) {
        if (game == null) {
          if (pendingLevel != null || resolving != null) {
            if (uiRunning) {
              ui.refresh(realtime);
              ui.drawConnectScreen(false);
            }
          } else if (uiRunning && (catchers & ClientInput.KEYCATCH_UI) == 0) {
            ui.setActiveMenu(UiHost.Menu.MAIN);
          }
        } else if (!game.cgameRunning()) {
          if (uiRunning) {
            ui.refresh(realtime);
            ui.drawConnectScreen(false);
          }
        } else {
          game.draw(serverTime);
          if (!game.active() && uiRunning && game.kind != GameClient.Kind.DEMO) {
            ui.refresh(realtime);
            ui.drawConnectScreen(true);
          }
        }
      }
      if (uiRunning && (services.input().catchers() & ClientInput.KEYCATCH_UI) != 0)
        ui.refresh(realtime);
    }
    if ((services.input().catchers() & ClientInput.KEYCATCH_CONSOLE) != 0)
      content.overlay.draw(0.5f, realtime);
    else if (uiBroken) content.overlay.draw(1, realtime);
    renderer.endFrame();
  }

  private void pollRcon() {
    if (rconSocket == null) return;
    byte[] buffer = new byte[Protocol68.MAX_MSGLEN + 1];
    try {
      for (int i = 0; i < 16; i++) {
        int length = rconSocket.poll(buffer);
        if (length == PacketTransport.NONE) return;
        if (length < 0) continue;
        byte[] packet = Arrays.copyOf(buffer, length);
        if (!ConnectionlessPacket.isConnectionless(packet)) continue;
        ConnectionlessMessage message = ConnectionlessMessage.parse(packet);
        if (!message.line().startsWith("print")) continue;
        String text = new String(message.body(), StandardCharsets.ISO_8859_1);
        for (String line : text.split("\n")) if (!line.isEmpty()) print(line);
      }
    } catch (IOException | RuntimeException failure) {
      LOG.debug("rcon reply ignored: " + failure.getMessage());
    }
  }

  private void updateState() {
    SessionState next;
    if (cinematic >= 0) next = SessionState.CINEMATIC;
    else if (game == null)
      next =
          pendingLevel != null || resolving != null
              ? SessionState.LOADING
              : server != null && console.cvars().integer("dedicated") != 0
                  ? (hosting ? SessionState.HOSTING : SessionState.LOCAL)
                  : SessionState.MENU;
    else if (!game.active()) next = SessionState.LOADING;
    else
      next =
          switch (game.kind) {
            case DEMO -> SessionState.DEMO;
            case REMOTE -> SessionState.REMOTE;
            case LOCAL -> hosting ? SessionState.HOSTING : SessionState.LOCAL;
          };
    if (next == state) return;
    SessionState previous = state;
    state = next;
    if (next == SessionState.MENU) uiRestarts = 0;
    print("Session: " + previous + " -> " + next);
    host.onStateChanged(previous, next);
  }

  // ================================================================ input

  /** A key went down or up (a Quake key number from {@link KeyNames}). */
  public void keyEvent(int key, boolean down) {
    owner.check();
    if (closed) return;
    ClientInput input = services().input();
    if (down
        && (key < 128 || key == KeyNames.MOUSE1)
        && key != ClientInput.KEY_CONSOLE
        && key != ClientInput.KEY_CONSOLE_ALT
        && input.catchers() == 0
        && (cinematic >= 0 || (game != null && game.kind == GameClient.Kind.DEMO))) {
      console.cvars().set("nextdemo", "");
      key = KeyNames.ESCAPE;
    }
    int k = key;
    guard(() -> input.keyEvent(k, down, inputTime()));
  }

  /** A typed character (for text fields). */
  public void charEvent(int character) {
    owner.check();
    if (closed) return;
    guard(() -> services().input().charEvent(character, inputTime()));
  }

  /** Relative mouse motion. */
  public void mouseEvent(int dx, int dy) {
    owner.check();
    if (closed) return;
    guard(() -> services().input().mouseEvent(dx, dy, inputTime()));
  }

  private int inputTime() {
    return (int) (Math.max(lastFrame, host.clock().millis()) - startMillis);
  }

  private void guard(Runnable action) {
    try {
      action.run();
    } catch (GameClient.Drop failure) {
      drop(failure.getMessage());
    } catch (RuntimeException failure) {
      drop(message(failure));
    }
    if (!transitions.isEmpty()) {
      while (!transitions.isEmpty()) {
        try {
          transitions.poll().run();
        } catch (RuntimeException failure) {
          drop(message(failure));
        }
      }
      updateState();
    }
  }

  // ================================================================ public API

  /** Appends console text, as typed into the console ({@code Cbuf_AddText}). */
  public void command(String text) {
    owner.check();
    console.buffer().add(text.endsWith("\n") ? text : text + "\n");
  }

  public SessionState state() {
    return state;
  }

  public boolean quitRequested() {
    return quit;
  }

  public Console console() {
    return console;
  }

  public CvarTable cvars() {
    return console.cvars();
  }

  /** The drop-down console's scroll-back (also every printed line). */
  public ClientConsole output() {
    return output;
  }

  /** The client's VM host services (renderer, sound, input) for the current content. */
  public HostServices services() {
    return content.services;
  }

  public UiHost ui() {
    return content.ui;
  }

  /** The running cgame, if any. */
  public Optional<CgameHost> cgame() {
    return game == null ? Optional.empty() : game.cgame();
  }

  /** The local server's newest published status, while one runs. */
  public Optional<LocalServer.Status> serverStatus() {
    return server == null ? Optional.empty() : Optional.of(server.status());
  }

  /** The local server's thread, while one runs threaded. */
  public Optional<Thread> serverThread() {
    return server == null ? Optional.empty() : server.thread();
  }

  /** The UDP address the hosted server listens on, while hosting. */
  public Optional<InetSocketAddress> hostedAddress() {
    return Optional.ofNullable(hostedAddress);
  }

  /** The client connection's state, while connected or connecting. */
  public Optional<ClientConnection.State> connectionState() {
    return game == null ? Optional.empty() : game.connection().map(ClientConnection::state);
  }

  /** The current client game's pipeline (snapshots as the cgame reads them). */
  public Optional<dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline> pipeline() {
    return game == null ? Optional.empty() : Optional.of(game.pipeline);
  }

  /** Levels the current client game loaded. */
  public int levelsLoaded() {
    return game == null ? 0 : game.levels();
  }

  /** Demos played to their end. */
  public long demosFinished() {
    return demosFinished;
  }

  /**
   * Syscall degradations of the ui, the current cgame and every local server run so far (the {@code
   * vm_strictSyscalls} counters).
   */
  public long degradations() {
    long total = content.ui.degradationTotal() + serverDegradations;
    if (game != null) total += game.degradations();
    if (server != null) total += server.status().degradations();
    return total;
  }

  /** The search path the session mounted (shared with the local server). */
  public SearchPath searchPath() {
    return searchPath;
  }

  /** Milliseconds since the session started, as the VMs see them ({@code cls.realtime}). */
  public int realtime() {
    return realtime;
  }

  public long frames() {
    return framesRun;
  }

  /** The ui could not be started; only the console is drawn. */
  public boolean uiBroken() {
    return uiBroken;
  }

  private void print(String line) {
    output.print(line);
  }

  private static String message(Throwable failure) {
    String text = failure.getMessage();
    return text == null || text.isBlank() ? failure.toString() : text;
  }

  /**
   * Ends the session: the game, the local server and its thread, the ui; writes {@code
   * q3config.cfg} when configured; closes the files. Idempotent.
   */
  @Override
  public void close() {
    owner.check();
    if (closed) return;
    closed = true;
    try {
      stopCinematicSilently();
      closeGame(true);
      stopServer();
    } catch (RuntimeException failure) {
      LOG.warn("Session teardown failed: " + failure);
    }
    if (options.writeConfig() && home != null) console.writeConfig(Console.CONFIG_FILE);
    if (cinematics != null) cinematics.close();
    try {
      browser.close();
    } catch (IOException failure) {
      LOG.debug("Browser close failed");
    }
    if (rconSocket != null) {
      try {
        rconSocket.close();
      } catch (IOException failure) {
        LOG.debug("rcon socket close failed");
      }
    }
    content.close();
    try {
      files.close();
    } catch (IOException failure) {
      LOG.debug("Home store close failed");
    }
    try {
      searchPath.close();
    } catch (IOException failure) {
      LOG.debug("Search path close failed");
    }
    owner.release();
  }
}
