import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Original team rules and autonomous bot decisions through the production application. */
class AuditTeamApplication {
  static final class Audio implements AudioBackend {
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
      return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "CPU team match");
    }

    public void close() {}
  }

  public static void main(String[] args) throws Exception {
    Path games = Path.of(args[1]), out = Path.of(args[2]);
    Files.createDirectories(out);
    for (int type : new int[] {3, 4}) run(games, out, type, type == 3 ? "q3dm1" : "q3ctf1");
  }

  static void run(Path games, Path out, int type, String map) throws Exception {
    try (var fs = Pk3FileSystem.mount(games, "baseq3");
        var saved = new GameFileStore(Files.createTempDirectory(out, "team-" + type + "-"));
        var session =
            new QuakeSession(fs, saved, new Audio(), null, null, "TeamObserver", 640, 480)) {
      session.command(
          "set g_gametype "
              + type
              + "; set bot_enable 1; set fraglimit 5; set capturelimit 1; set timelimit 0; set"
              + " g_doWarmup 0; map "
              + map);
      System.setProperty("craftq3.lifecycleCapture", "true");
      try {
        session.advance(50, 640, 480);
      } finally {
        System.clearProperty("craftq3.lifecycleCapture");
      }
      check(
          session.playing() && session.cvars().integer("g_gametype") == type,
          "Team map startup failed");
      session.command(
          "team spectator; set nextmap \"map_restart 0\"; addbot sarge 3 red; addbot major 3 red;"
              + " addbot visor 3 blue; addbot anarki 3 blue");
      int scoreRed = 0,
          scoreBlue = 0,
          flagTransitions = 0,
          intermissionFrames = 0,
          afterRestart = 0,
          worldViews = 0;
      int initialRestart = session.server().restartCount();
      boolean ready = false, completed = false, carriedFlag = false;
      String flags = "";
      long deadline = System.nanoTime() + 180_000_000_000L;
      for (int elapsed = 0; elapsed < 600000 && System.nanoTime() < deadline; elapsed += 50) {
        var frame = session.advance(50, 640, 480);
        var server = session.server();
        int red = number(server.configstrings().get(6)),
            blue = number(server.configstrings().get(7));
        // Observe canonical player-state powerups in both profiles. Do not assume the
        // retail guest publishes the later CS_FLAGSTATUS configstring.
        String nowFlags = type == 4 ? flagCarriers(session) : "";
        if (type == 4 && !nowFlags.isEmpty()) carriedFlag = true;
        if (red != scoreRed || blue != scoreBlue || !nowFlags.equals(flags)) {
          System.out.printf(
              "TEAM type=%d time=%d red=%d blue=%d flags=%s%n",
              type, session.time(), red, blue, nowFlags);
          if (!nowFlags.equals(flags)) flagTransitions++;
          scoreRed = red;
          scoreBlue = blue;
          flags = nowFlags;
        }
        if (!completed && Math.max(red, blue) >= (type == 3 ? 5 : 1)) completed = true;
        if (player(session, 0).getInt(4) == 5) {
          intermissionFrames++;
          if (intermissionFrames > 120 && !ready) {
            session.input().key(178, true, session.inputTime());
            ready = true;
          } else session.input().key(178, false, session.inputTime());
        }
        if (server.restartCount() > initialRestart) {
          check(
              completed && intermissionFrames > 0,
              "Restart without earned team victory/intermission");
          afterRestart++;
          for (var c : frame.commands())
            if (c instanceof CgameFrame.View v
                && (v.refdef().rdflags() & CgameFrame.RDF_NOWORLDMODEL) == 0) worldViews++;
          if (afterRestart == 1) {
            check(red == 0 && blue == 0, "Team scores did not reset");
            check(nowFlags.isEmpty(), "Flag possession survived match restart");
          }
          if (afterRestart >= 300) break;
        }
        if (elapsed % 30000 == 0) {
          System.out.printf(
              "TEAM PROGRESS type=%d time=%d red=%d blue=%d bots=%s%n",
              type, session.time(), red, blue, players(session));
        }
      }
      check(
          completed && intermissionFrames > 0 && afterRestart >= 300 && worldViews > 0,
          "Incomplete team lifecycle type="
              + type
              + " red="
              + scoreRed
              + " blue="
              + scoreBlue
              + " flags="
              + flags
              + " bots="
              + players(session)
              + " console="
              + session.consoleLines());
      int redPlayers = 0, bluePlayers = 0;
      for (int i = 1; i < 5; i++) {
        check(session.server().isBot(i), "Missing retained bot " + i);
        int team = player(session, i).getInt(260);
        if (team == 1) redPlayers++;
        if (team == 2) bluePlayers++;
      }
      check(redPlayers == 2 && bluePlayers == 2, "Team assignments changed: " + players(session));
      if (type == 4) check(carriedFlag && flagTransitions >= 2, "No observed flag lifecycle");
      System.out.printf(
          "PASS team type=%d uiApi=%d earned=true intermissionFrames=%d restartFrames=%d"
              + " worldViews=%d flagTransitions=%d redBots=%d blueBots=%d%n",
          type,
          session.ui().apiVersion(),
          intermissionFrames,
          afterRestart,
          worldViews,
          flagTransitions,
          redPlayers,
          bluePlayers);
    }
  }

  static String flagCarriers(QuakeSession session) {
    var carriers = new ArrayList<String>();
    for (int slot = 1; slot < 5; slot++) {
      if (!session.server().isBot(slot)) continue;
      var state = player(session, slot);
      int team = state.getInt(260);
      if (state.getInt(312 + 7 * 4) != 0) {
        check(team == 2, "Red flag carried by a non-blue player");
        carriers.add("red:" + slot);
      }
      if (state.getInt(312 + 8 * 4) != 0) {
        check(team == 1, "Blue flag carried by a non-red player");
        carriers.add("blue:" + slot);
      }
    }
    return String.join(",", carriers);
  }

  static int number(String value) {
    return value.isEmpty() ? 0 : Integer.parseInt(value);
  }

  static ByteBuffer player(QuakeSession s, int slot) {
    return ByteBuffer.wrap(s.server().playerState(slot)).order(ByteOrder.LITTLE_ENDIAN);
  }

  static String players(QuakeSession s) {
    var result = new ArrayList<String>();
    for (int i = 0; i < 5; i++) {
      var p = player(s, i);
      result.add(
          i
              + ":team="
              + p.getInt(260)
              + ",score="
              + p.getInt(248)
              + ",hp="
              + p.getInt(184)
              + ",pos="
              + p.getFloat(20)
              + ","
              + p.getFloat(24)
              + ","
              + p.getFloat(28));
    }
    return result.toString();
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
