import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.client.UiHost;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opt-in original UI -> spmap -> original game/client QA. Never submits an addbot command.
 * -Daudit.enterArena=true adds ordinary human movement through the tutorial teleporter and requires
 * a Crash attack/death encounter. The default idle-human run only requires original bot movement.
 */
class AuditSinglePlayerGame {
  private static final class Host implements UiHost {
    Q3Ui ui;
    Q3Server server;
    Q3Client client;
    int catcher;
    String pendingMap;
    final List<String> mapCommands = new ArrayList<>();

    public ClientState clientState() {
      return new ClientState(client == null ? 1 : 8, 0, client == null ? -1 : 0, "", "", "");
    }

    public String configString(int index) {
      return server == null ? "" : server.configstrings().get(index);
    }

    public int keyCatcher() {
      return client == null ? catcher : client.keyCatcher();
    }

    public void keyCatcher(int value) {
      if (client == null) catcher = value;
      else client.keyCatcher(value);
    }

    public void clearKeys() {}

    void route(CommandSystem buffer) {
      buffer.unknownHandler(
          command -> {
            if (ui != null && ui.consoleCommand(command)) return;
            if (client != null) {
              System.out.println("ENGINE_COMMAND " + command.arguments());
              client.consoleCommand(command);
            } else
              throw new UnsupportedOperationException("Unrouted original UI command: " + command);
          });
    }

    void install(CommandSystem buffer) {
      for (String name : List.of("map", "devmap", "spmap", "spdevmap"))
        buffer.register(
            name,
            command -> {
              if (command.arguments().size() != 2) throw new AssertionError(command);
              mapCommands.add(command.arguments().toString());
              if (!name.equals("spmap"))
                throw new AssertionError("Expected original spmap: " + command);
              pendingMap = command.argument(1);
              buffer.frameBoundary();
            });
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException(
          "Usage: AuditSinglePlayerGame <games directory> [milliseconds]");
    int duration = args.length == 2 ? Integer.parseInt(args[1]) : 60000;
    if (duration < 1000 || duration > 300000)
      throw new IllegalArgumentException("duration 1000..300000");
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      var cvars = new CvarSystem();
      cvars.register("sv_cheats", "0", CvarSystem.ROM);
      Map<String, String> userInfo =
          Map.of(
              "name",
              "MenuGameAudit",
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
      userInfo.forEach((name, value) -> cvars.register(name, value, CvarSystem.USERINFO));
      var commands = new CommandSystem(cvars, fs, System.out::print);
      var host = new Host();
      host.install(commands);
      host.route(commands);
      var bindings = new KeyBindings();
      bindings.bind('w', "+forward");
      bindings.bind(178, "+attack");
      var audio = new Audio();
      try (var ui =
          new Q3Ui(fs, cvars, commands, bindings, audio, frame -> {}, System.out::print, host)) {
        host.ui = ui;
        ui.initialize(1280, 720);
        ui.setMenu(Q3Ui.Menu.MAIN);
        ui.frame(1000, 1280, 720);
        key(ui, 27, 1001);
        ui.setMenu(Q3Ui.Menu.MAIN);
        ui.frame(1100, 1280, 720);
        key(ui, 13, 1900);
        var arena = ui.frame(1950, 1280, 720);
        if (arena.commands().stream()
            .noneMatch(
                c -> c instanceof CgameFrame.Quad q && q.shader().equals("levelshots/q3dm0")))
          throw new AssertionError("Original arena selection did not show q3dm0");
        // Original 640x480 virtual menu coordinates, verified from its submitted Fight/cursor
        // quads.
        ui.mouse(576, 448, 2100);
        ui.frame(2150, 1280, 720);
        key(ui, 178, 2200);
        var skill = ui.frame(2250, 1280, 720);
        if (skill.commands().stream()
            .noneMatch(
                c -> c instanceof CgameFrame.Quad q && q.shader().equals("menu/art/cut_frame")))
          throw new AssertionError("Original difficulty screen missing");
        key(ui, 13, 3000);
        ui.frame(3050, 1280, 720);
        commands.runFrame(1024);
        System.out.println("MENU_MAP_COMMANDS " + host.mapCommands);
        if (!"q3dm0".equals(host.pendingMap) || host.mapCommands.size() != 1)
          throw new AssertionError("Original menu did not request exactly one q3dm0 game");
        cvars.set("sv_cheats", "0", CvarSystem.Source.ENGINE);
        cvars.set("cl_paused", "0", CvarSystem.Source.ENGINE);
        cvars.set(
            "g_gametype",
            "2",
            CvarSystem.Source.ENGINE); // Host semantics of the observed spmap command.
        String mapName = host.pendingMap;
        host.pendingMap = null;
        var map = BspReader.read(fs.read(new VirtualPath("maps/" + mapName + ".bsp")));
        try (var server =
            new Q3Server(
                fs, mapName, map, null, System.out::print, Clock.systemUTC(), null, cvars)) {
          if (server.cvars().integer("bot_enable") != 1)
            throw new AssertionError("Normal local-game default did not enable original bots");
          host.server = server;
          host.install(server.commands());
          try (var client =
              new Q3Client(fs, server, frame -> {}, audio, System.out::print, null, commands)) {
            host.client = client;
            server.initialize(1000, 42);
            int start = server.time();
            server.connect(
                0, userInfo); // Preserve the human slot before queued original game commands drain.
            client.initialize(0, 1280, 720);
            host.route(commands);
            ui.setMenu(Q3Ui.Menu.NONE);
            var userCommandsField = Q3Server.class.getDeclaredField("userCommands");
            userCommandsField.setAccessible(
                true); // Read-only observation of original qagame-generated bot inputs.
            int views = 0, quads = 0, entities = 0, moving = 0, attacks = 0, alive = 0, bot = -1;
            double distance = 0;
            Vec3 previous = null;
            int previousFlags = 0,
                previousHealth = 125,
                deaths = 0,
                firstAlive = -1,
                firstAttack = -1;
            ByteBuffer human = state(server, 0);
            int pitch = angle(human.getFloat(152), human.getInt(56));
            int yaw = angle(human.getFloat(156), human.getInt(60));
            int roll = angle(human.getFloat(160), human.getInt(64));
            System.out.println(
                "HUMAN_SPAWN origin="
                    + origin(human)
                    + " viewAngles="
                    + human.getFloat(152)
                    + ","
                    + human.getFloat(156)
                    + ","
                    + human.getFloat(160));
            int waypoint = 0;
            double[][] route = {{-1000, -1230}, {-1000, -1460}, {-1152, -1640}, {-1152, -1850}};
            for (int step = 1; step <= duration / 50; step++) {
              commands.runFrame(1024);
              if (host.pendingMap != null)
                throw new AssertionError("Unexpected subsequent map: " + host.pendingMap);
              int time = start + step * 50;
              server.runFrame(time);
              int forward = Integer.getInteger("audit.forward", 0);
              int buttons = Integer.getInteger("audit.buttons", 0);
              if (Boolean.getBoolean("audit.enterArena") && waypoint < route.length) {
                var hp = state(server, 0);
                double dx = route[waypoint][0] - hp.getFloat(20),
                    dy = route[waypoint][1] - hp.getFloat(24);
                if (Math.hypot(dx, dy) < 20 || hp.getFloat(20) > -500) waypoint++;
                else {
                  yaw = angle((float) Math.toDegrees(Math.atan2(dy, dx)), hp.getInt(60));
                  forward = 127;
                }
              }
              client.userCommand(
                  new UserCommand(
                      time, pitch, yaw, roll, buttons, client.selectedWeapon(), forward, 0, 0));
              var frame = client.frame(time, 1280, 720);
              for (var command : frame.commands()) {
                if (command instanceof CgameFrame.View view) {
                  views++;
                  entities += view.entities().size();
                }
                if (command instanceof CgameFrame.Quad) quads++;
              }
              for (int slot = 1; slot < cvars.integer("sv_maxclients"); slot++)
                if (server.isBot(slot)) {
                  if (bot != -1 && bot != slot)
                    throw new AssertionError("Unexpected additional bot slot " + slot);
                  bot = slot;
                }
              if (bot != -1) {
                var ps = state(server, bot);
                var position = origin(ps);
                if (ps.getInt(184) > 0) {
                  alive++;
                  if (firstAlive < 0) firstAlive = time;
                }
                var input = ((UserCommand[]) userCommandsField.get(server))[bot];
                if ((input.buttons() & 1) != 0) {
                  attacks++;
                  if (firstAttack < 0) firstAttack = time;
                }
                int humanHealth = state(server, 0).getInt(184);
                if (humanHealth <= 0 && previousHealth > 0) deaths++;
                previousHealth = humanHealth;
                if (previous != null && ((previousFlags ^ ps.getInt(104)) & 4) == 0) {
                  double moved =
                      Math.sqrt(
                          Math.pow(position.x() - previous.x(), 2)
                              + Math.pow(position.y() - previous.y(), 2)
                              + Math.pow(position.z() - previous.z(), 2));
                  if (moved > 0.01 && moved < 128) {
                    distance += moved;
                    moving++;
                  }
                }
                previous = position;
                previousFlags = ps.getInt(104);
                if (step % 100 == 0 || step == 1)
                  System.out.println(
                      "SAMPLE time="
                          + time
                          + " bot="
                          + bot
                          + " health="
                          + ps.getInt(184)
                          + " pm="
                          + ps.getInt(4)
                          + " origin="
                          + position
                          + " input="
                          + input
                          + " human="
                          + origin(state(server, 0))
                          + " humanHealth="
                          + state(server, 0).getInt(184));
              }
            }
            System.out.println(
                "RESULT map="
                    + mapName
                    + " uiApi="
                    + ui.apiVersion()
                    + " gameAbi="
                    + server.abi()
                    + " bot="
                    + bot
                    + " aliveFrames="
                    + alive
                    + " movingFrames="
                    + moving
                    + " distance="
                    + distance
                    + " attackFrames="
                    + attacks
                    + " firstAlive="
                    + firstAlive
                    + " firstAttack="
                    + firstAttack
                    + " humanDeaths="
                    + deaths
                    + " localSounds="
                    + audio.locals
                    + " views="
                    + views
                    + " quads="
                    + quads
                    + " entities="
                    + entities
                    + " sounds="
                    + audio.voices
                    + " configPlayer="
                    + (bot < 0 ? "" : server.configstrings().get(544 + bot)));
            System.out.println("BOTLIB " + server.botlibStatus());
            if (bot < 0 || views == 0 || quads == 0 || moving == 0 || distance < 64)
              throw new AssertionError("Missing original game/client/bot output");
            if (duration >= 15000 && !audio.locals.contains("sound/player/announce/crash.wav"))
              throw new AssertionError("Original Crash entry cue did not reach the audio backend");
            if (Boolean.getBoolean("audit.enterArena") && (attacks == 0 || deaths == 0))
              throw new AssertionError(
                  "Crash did not attack and kill the human in the bounded encounter");
          }
        }
      }
    }
  }

  private static int angle(float degrees, int delta) {
    return ((int) (degrees * (65536.0 / 360)) - delta) & 65535;
  }

  private static ByteBuffer state(Q3Server server, int client) {
    return ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static Vec3 origin(ByteBuffer ps) {
    return new Vec3(ps.getFloat(20), ps.getFloat(24), ps.getFloat(28));
  }

  private static void key(Q3Ui ui, int key, int time) {
    ui.key(key, true, time);
    ui.key(key, false, time);
  }

  private static final class Audio implements AudioBackend {
    int sounds;
    long voices;
    final Map<Integer, String> names = new java.util.HashMap<>();
    final List<String> locals = new ArrayList<>();

    public int register(String name, PcmSound sound) {
      names.put(++sounds, name);
      return sounds;
    }

    public long play(Playback playback) {
      if (playback.channel() == 6) {
        locals.add(names.get(playback.sound()));
        System.out.println("LOCAL_SOUND " + names.get(playback.sound()) + " " + playback);
      }
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
      return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "CPU audit");
    }

    public void close() {}
  }
}
