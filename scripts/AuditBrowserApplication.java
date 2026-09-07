import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.*;
import dev.bluevista.craftq3.client.net.ServerBrowser;
import dev.bluevista.craftq3.core.command.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Actual original browser to private UDP game, with no injected connect or fabricated server rows.
 */
public class AuditBrowserApplication {
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

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    if (port < 1 || port > 65535)
      throw new IllegalArgumentException("Private server port required");
    Path games = Path.of(args[1]), out = Path.of(args[2]);
    Files.createDirectories(out);
    var peer = new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port);
    var audio = new Audio();
    try (var fs = Pk3FileSystem.mount(games, "baseq3");
        var saved = new GameFileStore(out.resolve("saved"));
        var session =
            new QuakeSession(fs, saved, audio, null, null, "Browser Application QA", 640, 480)) {
      for (int i = 1; i <= 5; i++)
        session.cvars().set("sv_master" + i, "", CvarSystem.Source.ENGINE);
      var field = QuakeSession.class.getDeclaredField("browser");
      field.setAccessible(true);
      var browser = (ServerBrowser) field.get(session);
      // Only substitute the LAN target selection: the real browser sends to the private server,
      // parses its actual reply, and serves the original UI's ordinary LAN syscalls.
      int[] refreshes = {0};
      session.commands().unregister("localservers");
      session
          .commands()
          .register(
              "localservers",
              command -> {
                refreshes[0]++;
                System.out.println("UI_COMMAND " + command.text());
                browser.discoverLocal(List.of(peer));
              });
      var connectCommands = new ArrayList<String>();
      var handlers = CommandSystem.class.getDeclaredField("handlers");
      handlers.setAccessible(true);
      @SuppressWarnings("unchecked")
      var commands =
          (Map<String, Consumer<CommandParser.Command>>) handlers.get(session.commands());
      var connect = commands.get("connect");
      if (connect == null) throw new AssertionError("No actual session connect handler");
      session.commands().unregister("connect");
      session
          .commands()
          .register(
              "connect",
              command -> {
                connectCommands.add(command.text());
                System.out.println("UI_COMMAND " + command.text());
                connect.accept(command);
              });
      frame(session);
      key(session, 27);
      session.ui().setMenu(Q3Ui.Menu.MAIN);
      frame(session);
      key(session, 133);
      key(session, 13);
      int api = session.ui().apiVersion();
      System.out.println("BROWSER_API " + api);
      // API4 starts on Internet; its empty configured master list emits no network traffic.
      if (api >= 4) {
        mouse(session, 330, 88);
        key(session, 178);
        key(session, 178);
      }
      // Stop the initial refresh before changing the retail UI's default empty-server filter.
      key(session, 32);
      if (api == 3) {
        mouse(session, 330, 150);
        key(session, 178);
      }
      mouse(session, 320, 445);
      key(session, 178);
      CgameFrame last = null;
      long until = System.nanoTime() + 15_000_000_000L;
      while (System.nanoTime() < until) {
        last = frame(session);
        if (browser.count(0) > 0
            && browser.serverPing(0, 0) > 0
            && text(last).toUpperCase(Locale.ROOT).contains("CRAFTQ3")) break;
        Thread.sleep(25);
      }
      String info = browser.info(0, 0), shown = last == null ? "" : text(last);
      System.out.println(
          "BROWSER_ROW "
              + browser.address(0, 0)
              + " PING "
              + browser.serverPing(0, 0)
              + " INFO "
              + info);
      System.out.println("BROWSER_TEXT " + shown);
      if (refreshes[0] < 1
          || browser.count(0) != 1
          || !browser.address(0, 0).equals("127.0.0.1:" + port)
          || browser.serverPing(0, 0) <= 0
          || !shown.toUpperCase(Locale.ROOT).contains("CRAFTQ3"))
        throw new AssertionError(
            "Original UI did not display private server: " + session.consoleLines());
      int observedPing = browser.serverPing(0, 0);
      mouse(session, 580, 445);
      key(session, 178);
      int frames = 0, views = 0, quads = 0;
      until = System.nanoTime() + 20_000_000_000L;
      while (System.nanoTime() < until && frames < 90) {
        var current = frame(session);
        int frameViews = 0;
        for (var command : current.commands())
          if (command instanceof CgameFrame.View) {
            views++;
            frameViews++;
          } else if (command instanceof CgameFrame.Quad) quads++;
        if (session.networked() && session.playing() && frameViews > 0) frames++;
        if (!connectCommands.isEmpty() && !session.networked())
          throw new AssertionError("Join failed: " + session.consoleLines());
        Thread.sleep(16);
      }
      if (connectCommands.size() != 1
          || !connectCommands.getFirst().equals("connect 127.0.0.1:" + port)
          || frames != 90
          || session.server() != null
          || session.cvars().integer("sv_running") != 0)
        throw new AssertionError(
            "Incomplete original-menu join: "
                + connectCommands
                + " frames="
                + frames
                + " console="
                + session.consoleLines());
      session.command("disconnect");
      var main = frame(session);
      if (session.networked()
          || session.playing()
          || !session.menuVisible()
          || main.commands().isEmpty())
        throw new AssertionError("No original main menu after disconnect");
      String result =
          "{\"uiApi\":"
              + api
              + ",\"refreshes\":"
              + refreshes[0]
              + ",\"serverRows\":1,\"ping\":"
              + observedPing
              + ",\"originalConnectCommands\":1,\"remoteFrames\":"
              + frames
              + ",\"views\":"
              + views
              + ",\"quads\":"
              + quads
              + ",\"returnedToMenu\":true}";
      Files.writeString(out.resolve("browser-application-result.json"), result + "\n");
      System.out.println("PASS " + result);
    }
  }

  private static CgameFrame frame(QuakeSession s) {
    s.networkTick(640, 480);
    return s.advance(16, 640, 480);
  }

  private static void key(QuakeSession s, int k) {
    s.menuKey(k, true);
    s.menuKey(k, false);
    frame(s);
  }

  private static void mouse(QuakeSession s, int x, int y) {
    s.menuMouse(-10000, -10000);
    s.menuMouse(x, y);
    frame(s);
  }

  private static String text(CgameFrame frame) {
    var rows = new TreeMap<Float, TreeMap<Float, Character>>();
    for (var c : frame.commands())
      if (c instanceof CgameFrame.Quad q && q.shader().equals("gfx/2d/bigchars"))
        rows.computeIfAbsent(q.y(), ignored -> new TreeMap<>())
            .put(q.x(), (char) (Math.round(q.t1() * 16) * 16 + Math.round(q.s1() * 16)));
    var result = new StringBuilder();
    for (var row : rows.values()) {
      row.values().forEach(result::append);
      result.append(" | ");
    }
    return result.toString().replace('\r', ' ');
  }
}
