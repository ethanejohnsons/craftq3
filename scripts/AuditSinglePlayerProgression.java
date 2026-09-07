import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.client.UiHost;
import dev.bluevista.craftq3.client.input.GameConfig;
import dev.bluevista.craftq3.client.input.Q3Input;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historical component audit; cinematic engine commands are not executed here. Use
 * AuditCampaignApplication for the production campaign/movie transition.
 * Original menu -> legitimate tutorial win -> original Next -> next-map frames -> saved campaign
 * reload. Test-only aim/movement/fire generates ordinary UserCommands; it never changes game state,
 * grants an item, sets a score or writes a progress cvar. User packs remain mounted read-only.
 */
class AuditSinglePlayerProgression {
  private static final int WIDTH = 1280, HEIGHT = 720;

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException(
          "Usage: AuditSinglePlayerProgression <games directory> [max tutorial milliseconds]");
    int budget = args.length == 2 ? Integer.parseInt(args[1]) : 300000;
    if (budget < 30000 || budget > 600000)
      throw new IllegalArgumentException("Budget 30000..600000");
    Path auditRoot = Path.of(".tools/single-player-progression");
    Files.createDirectories(auditRoot);
    Path homePath = Files.createTempDirectory(auditRoot, "home-").toAbsolutePath();
    Map<String, String> earned;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3");
        var home = new GameFileStore(homePath)) {
      try (var session = new Session(fs, home)) {
        var initial = session.openArenaMenu();
        requireShader(initial, "levelshots/q3dm0");
        session.click(initial, "menu/art/fight_0");
        var skill = session.ui.frame(session.uiTime += 50, WIDTH, HEIGHT);
        requireShader(skill, "menu/art/cut_frame");
        session.key(13);
        session.commands.runFrame(1024);
        if (!"q3dm0".equals(session.pendingMap))
          throw new AssertionError("UI did not choose tutorial");
        session.startRequested();
        if (session.cvars.integer("fraglimit") != 5 || session.cvars.integer("g_spSkill") != 2)
          throw new AssertionError("Unexpected original tutorial defaults");
        System.out.println(
            "DEFAULT_RULES skill=2 fraglimit=5 bot_enable=" + session.cvars.integer("bot_enable"));
        int started = session.uiTime, oldScore = -1;
        while (session.uiTime - started < budget && session.pendingMap == null) {
          CgameFrame menu = session.tick(true);
          int score = session.player(0).getInt(248);
          if (score != oldScore) {
            oldScore = score;
            System.out.println(
                "SCORE time="
                    + session.time
                    + " human="
                    + score
                    + " crash="
                    + session.player(1).getInt(248));
          }
          if (menu != null
              && session.postgameAt >= 0
              && session.uiTime - session.postgameAt > 15000) {
            if (score != 5 || session.player(1).getInt(248) >= score)
              throw new AssertionError("Original game did not award a tutorial win");
            requireShader(menu, "menu/art/next_0");
            session.click(menu, "menu/art/next_0");
            session.commands.runFrame(1024);
          }
        }
        if (!"q3dm1".equals(session.pendingMap) || session.postgames.size() != 1)
          throw new AssertionError(
              "No original postgame -> Next -> q3dm1 transition within budget");
        earned = progress(session.cvars);
        if (earned.getOrDefault("g_spScores2", "").isEmpty())
          throw new AssertionError("Original UI did not record campaign progress");
        System.out.println("EARNED_PROGRESS " + earned);
        if (!session.audio.localNames.contains("sound/player/announce/crash.wav"))
          throw new AssertionError("Crash entry cue was not delivered");
        session.startRequested();
        int nextStart = session.uiTime, nextBotFrames = 0;
        while (session.uiTime - nextStart < 30000) {
          session.tick(false);
          for (int slot = 1; slot < session.cvars.integer("sv_maxclients"); slot++)
            if (session.server.isBot(slot) && session.player(slot).getInt(184) > 0) nextBotFrames++;
        }
        if (nextBotFrames == 0 || !earned.equals(progress(session.cvars)))
          throw new AssertionError("Next-level original bots or campaign continuity missing");
        session.config.save("q3config.cfg");
        System.out.println(
            "NEXT_LEVEL q3dm1 liveBotFrames="
                + nextBotFrames
                + " mapCommands="
                + session.mapCommands);
      }
      // A new cvar system and fresh original UI load the actual saved config before UI
      // registration.
      try (var restored = new Session(fs, home)) {
        if (!earned.equals(progress(restored.cvars)))
          throw new AssertionError(
              "Saved original progress did not reload into a fresh UI session");
        var arena = restored.openArenaMenu();
        requireShader(arena, "levelshots/q3dm1");
        System.out.println("RESTORED_ARENA " + shaderNames(arena));
        System.out.println("RESTORED_PROGRESS " + progress(restored.cvars));
      }
    }
    System.out.println(
        "Legacy tutorial/postgame/Next component audit: PASS (cinematic execution not covered) home="
            + homePath);
  }

  private static final class Session implements UiHost, AutoCloseable {
    final VirtualFileSystem fs;
    final GameFileStore home;
    final CvarSystem cvars = new CvarSystem();
    final CommandSystem commands;
    final Q3Input input;
    final GameConfig config;
    final Audio audio = new Audio();
    final Q3Ui ui;
    final List<String> mapCommands = new ArrayList<>(), postgames = new ArrayList<>();
    Q3Server server;
    Q3Client client;
    BspTraceWorld geometry;
    String pendingMap;
    int catcher, time = 1000, uiTime = 1000, postgameAt = -1, waypoint;
    final double[][] route = {{-1000, -1230}, {-1000, -1460}, {-1152, -1640}, {-1152, -1850}};

    Session(VirtualFileSystem fs, GameFileStore home) throws Exception {
      this.fs = fs;
      this.home = home;
      cvars.register("sv_cheats", "0", CvarSystem.ROM);
      for (var entry : userInfo().entrySet())
        cvars.register(entry.getKey(), entry.getValue(), CvarSystem.USERINFO | CvarSystem.ARCHIVE);
      commands = new CommandSystem(cvars, fs, System.out::print);
      input = new Q3Input(cvars, commands, 1000);
      config = new GameConfig(commands, input.bindings(), fs, home, System.out::print);
      config.load();
      commands.runFrame(1024);
      ui =
          new Q3Ui(
              fs, cvars, commands, input.bindings(), audio, ignored -> {}, System.out::print, this);
      ui.initialize(WIDTH, HEIGHT);
      install(commands);
      routeCommands();
    }

    CgameFrame openArenaMenu() {
      ui.setMenu(Q3Ui.Menu.MAIN);
      ui.frame(uiTime, WIDTH, HEIGHT);
      key(27);
      ui.setMenu(Q3Ui.Menu.MAIN);
      ui.frame(uiTime += 100, WIDTH, HEIGHT);
      key(13);
      return ui.frame(uiTime += 100, WIDTH, HEIGHT);
    }

    void routeCommands() {
      commands.unknownHandler(this::routeCommand);
      if (server != null) server.commands().unknownHandler(this::routeCommand);
    }

    void routeCommand(CommandParser.Command command) {
      if (ui.consoleCommand(command, uiTime)) {
        System.out.println(
            "UI_COMMAND time=" + time + " realtime=" + uiTime + " " + command.arguments());
        if (command.argument(0).equalsIgnoreCase("postgame")) {
          postgames.add(command.text());
          postgameAt = uiTime;
        }
        return;
      }
      if (client != null) client.consoleCommand(command);
      else throw new UnsupportedOperationException("Unrouted command: " + command);
    }

    void install(CommandSystem buffer) {
      for (String name : List.of("map", "devmap", "spmap", "spdevmap"))
        buffer.register(
            name,
            command -> {
              if (!name.equals("spmap") || command.arguments().size() != 2)
                throw new AssertionError("Unexpected map command: " + command);
              pendingMap = command.argument(1);
              mapCommands.add(command.text());
              System.out.println("MAP_COMMAND " + command.arguments());
              buffer.frameBoundary();
            });
    }

    void startRequested() throws Exception {
      String mapName = pendingMap;
      if (mapName == null) throw new AssertionError("No original map request");
      pendingMap = null;
      closeGame();
      cvars.set("sv_cheats", "0", CvarSystem.Source.ENGINE);
      cvars.set("cl_paused", "0", CvarSystem.Source.ENGINE);
      cvars.set("g_gametype", "2", CvarSystem.Source.ENGINE);
      var map = BspReader.read(fs.read(new VirtualPath("maps/" + mapName + ".bsp")));
      geometry = new BspTraceWorld(map);
      server =
          new Q3Server(fs, mapName, map, home, System.out::print, Clock.systemUTC(), null, cvars);
      if (cvars.integer("bot_enable") != 1)
        throw new AssertionError("Normal bot default is disabled");
      install(server.commands());
      server.commands().savedFiles(home);
      client = new Q3Client(fs, server, ignored -> {}, audio, System.out::print, null, commands);
      server.initialize(time, 42);
      time = server.time();
      server.connect(0, userInfo());
      client.initialize(0, WIDTH, HEIGHT);
      routeCommands();
      ui.setMenu(Q3Ui.Menu.NONE);
      System.out.println("STARTED " + mapName + " time=" + time);
    }

    CgameFrame tick(boolean fight) {
      commands.runFrame(1024);
      uiTime += 50;
      if (pendingMap != null) return null;
      boolean paused = (keyCatcher() & 2) != 0 && cvars.integer("cl_paused") != 0;
      if (!paused) {
        server.runFrame(time += 50);
        client.userCommand(humanCommand(fight));
      }
      var frame = client.frame(time, WIDTH, HEIGHT);
      if (frame.commands().stream().noneMatch(CgameFrame.View.class::isInstance))
        throw new AssertionError("Original cgame omitted its view at " + time);
      return (keyCatcher() & 2) == 0 ? null : ui.frame(uiTime, WIDTH, HEIGHT);
    }

    UserCommand humanCommand(boolean fight) {
      var human = player(0);
      int pitch = angle(human.getFloat(152), human.getInt(56));
      int yaw = angle(human.getFloat(156), human.getInt(60));
      int roll = angle(human.getFloat(160), human.getInt(64));
      int forward = 0, buttons = 0;
      if (fight && waypoint < route.length) {
        double dx = route[waypoint][0] - human.getFloat(20),
            dy = route[waypoint][1] - human.getFloat(24);
        if (Math.hypot(dx, dy) < 20 || human.getFloat(20) > -500) waypoint++;
        else {
          yaw = angle((float) Math.toDegrees(Math.atan2(dy, dx)), human.getInt(60));
          forward = 127;
        }
      } else if (fight && server.isBot(1) && human.getInt(4) != 5) {
        var crash = player(1);
        if (human.getInt(184) <= 0) buttons = 1;
        else if (crash.getInt(184) > 0) {
          Vec3 start = position(human).add(new Vec3(0, 0, 26));
          Vec3 target = position(crash).add(new Vec3(0, 0, 12));
          double dx = target.x() - start.x(),
              dy = target.y() - start.y(),
              dz = target.z() - start.z();
          yaw = angle((float) Math.toDegrees(Math.atan2(dy, dx)), human.getInt(60));
          pitch =
              angle((float) -Math.toDegrees(Math.atan2(dz, Math.hypot(dx, dy))), human.getInt(56));
          buttons = geometry.trace(TraceRequest.ray(start, target, 1)).fraction() == 1 ? 1 : 0;
        }
      }
      int weapon =
          fight
              ? human.getInt(388) > 0 ? 3 : human.getInt(384) > 0 ? 2 : 1
              : client.selectedWeapon() > 0 ? client.selectedWeapon() : human.getInt(144);
      return new UserCommand(time, pitch, yaw, roll, buttons, weapon, forward, 0, 0);
    }

    void click(CgameFrame frame, String shader) {
      CgameFrame.Quad target = quad(frame, shader), cursor = quad(frame, "menu/art/3_cursor2");
      ui.mouse(
          (int) Math.round((centerX(target) - centerX(cursor)) / 1.5),
          (int) Math.round((centerY(target) - centerY(cursor)) / 1.5),
          uiTime += 50);
      key(178);
    }

    void key(int code) {
      uiTime += 50;
      ui.key(code, true, uiTime);
      ui.key(code, false, uiTime);
    }

    ByteBuffer player(int slot) {
      return ByteBuffer.wrap(server.playerState(slot)).order(ByteOrder.LITTLE_ENDIAN);
    }

    public ClientState clientState() {
      return new ClientState(
          client == null ? 1 : 8, 0, client == null ? -1 : 0, "localhost", "", "");
    }

    public String configString(int index) {
      return server == null ? "" : server.configstrings().get(index);
    }

    public int keyCatcher() {
      return client == null ? catcher : client.keyCatcher();
    }

    public void keyCatcher(int value) {
      catcher = value;
      if (client != null) client.keyCatcher(value);
    }

    public void clearKeys() {
      input.releaseAll(time);
    }

    void closeGame() throws Exception {
      if (client != null) {
        client.close();
        client = null;
      }
      if (server != null) {
        server.close();
        server = null;
      }
      audio.stopAll();
      cvars.set("sv_running", "0", CvarSystem.Source.ENGINE);
      cvars.set("cl_paused", "0", CvarSystem.Source.ENGINE);
    }

    public void close() throws Exception {
      ui.close();
      closeGame();
    }
  }

  private static Map<String, String> userInfo() {
    return Map.of(
        "name",
        "CampaignAudit",
        "ip",
        "localhost",
        "model",
        "sarge/default",
        "headmodel",
        "sarge/default",
        "handicap",
        "100",
        "rate",
        "25000",
        "snaps",
        "20");
  }

  private static Map<String, String> progress(CvarSystem cvars) {
    var result = new LinkedHashMap<String, String>();
    for (var cvar : cvars.all())
      if (cvar.name().startsWith("g_spScores")
          || cvar.name().equals("g_spAwards")
          || cvar.name().equals("g_spVideos")) result.put(cvar.name(), cvar.value());
    return Map.copyOf(result);
  }

  private static int angle(float degrees, int delta) {
    return ((int) (degrees * (65536.0 / 360)) - delta) & 65535;
  }

  private static Vec3 position(ByteBuffer ps) {
    return new Vec3(ps.getFloat(20), ps.getFloat(24), ps.getFloat(28));
  }

  private static CgameFrame.Quad quad(CgameFrame frame, String shader) {
    return frame.commands().stream()
        .filter(c -> c instanceof CgameFrame.Quad q && q.shader().equals(shader))
        .map(c -> (CgameFrame.Quad) c)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing original menu shader: " + shader));
  }

  private static void requireShader(CgameFrame frame, String shader) {
    quad(frame, shader);
  }

  private static double centerX(CgameFrame.Quad q) {
    return q.x() + q.width() / 2;
  }

  private static double centerY(CgameFrame.Quad q) {
    return q.y() + q.height() / 2;
  }

  private static List<String> shaderNames(CgameFrame frame) {
    return frame.commands().stream()
        .filter(CgameFrame.Quad.class::isInstance)
        .map(c -> ((CgameFrame.Quad) c).shader())
        .distinct()
        .toList();
  }

  private static final class Audio implements AudioBackend {
    final Map<Integer, String> names = new LinkedHashMap<>();
    final List<String> localNames = new ArrayList<>();
    long voices;

    public int register(String name, PcmSound sound) {
      int handle = names.size() + 1;
      names.put(handle, name);
      return handle;
    }

    public long play(Playback playback) {
      if (playback.channel() == 6) localNames.add(names.get(playback.sound()));
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
      return new Diagnostics(true, names.size(), 0, 0, 0, voices, 0, "CPU audit");
    }

    public void close() {}
  }
}
