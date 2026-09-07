import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.*;
import dev.bluevista.craftq3.core.command.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CPU-only original multiplayer UI command capture; no network, GUI or guest-memory mutation. */
class AuditCreateServerMenu {
  public static void main(String[] args) throws Exception {
    if (args.length < 1)
      throw new IllegalArgumentException(
          "Usage: AuditMultiplayerMenu games [ui.qvm|-] [keys comma-separated]");
    try (var disk = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      var fs =
          new VirtualFileSystem() {
            public Optional<Origin> which(VirtualPath p) {
              return disk.which(p);
            }

            public List<VirtualPath> list(String p) {
              return disk.list(p);
            }

            public List<Origin> searchOrder() {
              return disk.searchOrder();
            }

            public byte[] read(VirtualPath p) throws IOException {
              return args.length > 1 && !args[1].equals("-") && p.value().equals("vm/ui.qvm")
                  ? Files.readAllBytes(Path.of(args[1]))
                  : disk.read(p);
            }

            public void close() {}
          };
      var cvars = new CvarSystem();
      cvars.register("sv_cheats", "0", CvarSystem.ROM);
      cvars.register("protocol", System.getProperty("audit.protocol", "71"), CvarSystem.ROM);
      cvars.register("debug_protocol", System.getProperty("audit.debugProtocol", ""), 0);
      cvars.register("name", "BrowserAudit", CvarSystem.USERINFO);
      cvars.register("model", "sarge/default", CvarSystem.USERINFO);
      cvars.register("headmodel", "sarge/default", CvarSystem.USERINFO);
      var commands = new CommandSystem(cvars, fs, System.out::print);
      commands.unknownHandler(
          c -> System.out.println("COMMAND " + c.text() + " ARGV " + c.arguments()));
      var browser =
          new UiBrowser() {
            public int count(int source) {
              System.out.println("COUNT " + source);
              return 0;
            }

            public void markVisible(int source, int index, int value) {
              System.out.println("VISIBLE " + source + " " + index + " " + value);
            }

            public void resetPings(int source) {
              System.out.println("RESET " + source);
            }

            public void clearPing(int index) {}

            public boolean updatePings(int source) {
              System.out.println("UPDATE " + source);
              return false;
            }

            public void loadCache() {
              System.out.println("LOADCACHE");
            }

            public void saveCache() {
              System.out.println("SAVECACHE");
            }
          };
      var disconnected = UiHost.disconnected();
      var host =
          new UiHost() {
            public ClientState clientState() {
              return disconnected.clientState();
            }

            public String configString(int index) {
              return "";
            }

            public int keyCatcher() {
              return disconnected.keyCatcher();
            }

            public void keyCatcher(int value) {
              disconnected.keyCatcher(value);
            }

            public void clearKeys() {}

            public UiBrowser browser() {
              return browser;
            }
          };
      try (var ui =
          new Q3Ui(
              fs,
              cvars,
              commands,
              new KeyBindings(),
              new Audio(),
              f -> {},
              System.out::print,
              host)) {
        ui.initialize(640, 480);
        ui.setMenu(Q3Ui.Menu.MAIN);
        ui.frame(1000, 640, 480);
        key(ui, 27, 1001);
        ui.setMenu(Q3Ui.Menu.MAIN);
        ui.frame(1100, 640, 480);
        key(ui, 133, 1200);
        key(ui, 13, 1201);
        dump(ui.frame(1300, 640, 480));
        commands.runFrame(1024);
        int now = 1400;
        if (args.length > 2)
          for (String part : args[2].split(",")) {
            if (part.startsWith("t")) {
              now = Integer.parseInt(part.substring(1));
              System.out.println("TIME " + now);
            } else if (part.startsWith("m")) {
              String[] xy = part.substring(1).split(":");
              ui.mouse(-10000, -10000, now++);
              ui.mouse(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]), now++);
              System.out.println("MOUSE " + part);
            } else {
              int k = Integer.parseInt(part);
              System.out.println("KEY " + k);
              key(ui, k, now++);
            }
            dump(ui.frame(now++, 640, 480));
            commands.runFrame(1024);
            System.out.println(
                "CVARS "
                    + cvars.string("ui_browserMaster")
                    + "/"
                    + cvars.string("ui_netSource")
                    + "/"
                    + cvars.string("ui_browserGameType"));
          }
        System.out.println("HOST CVARS dedicated="+cvars.string("dedicated")+" maxclients="+cvars.string("sv_maxclients")+" gametype="+cvars.string("g_gametype")+" hostname="+cvars.string("sv_hostname"));
        System.out.println("API " + ui.apiVersion() + " SYSCALLS " + ui.syscallCounts());
      }
    }
  }

  private static void key(Q3Ui ui, int k, int t) {
    ui.key(k, true, t);
    ui.key(k, false, t);
  }

  private static void dump(CgameFrame frame) {
    for (var cmd : frame.commands())
      if (cmd instanceof CgameFrame.Quad q
          && q.shader().startsWith("menu/art/"))
        System.out.println("CONTROL " + q);
    var rows = new java.util.TreeMap<Float, java.util.TreeMap<Float, Character>>();
    for (var q : frame.commands())
      if (q instanceof CgameFrame.Quad quad && quad.shader().equals("gfx/2d/bigchars")) {
        char value = (char) (Math.round(quad.t1() * 16) * 16 + Math.round(quad.s1() * 16));
        rows.computeIfAbsent(quad.y(), ignored -> new java.util.TreeMap<>()).put(quad.x(), value);
      }
    for (var entry : rows.entrySet()) {
      var line = new StringBuilder();
      entry.getValue().values().forEach(line::append);
      System.out.println("TEXT " + entry.getKey() + " " + line);
    }
  }

  private static final class Audio implements AudioBackend {
    int sounds;
    long voices;

    public int register(String name, PcmSound sound) {
      return ++sounds;
    }

    public long play(Playback playback) {
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
