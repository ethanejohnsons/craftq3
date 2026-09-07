import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.nio.file.Files;
import java.nio.file.Path;

/** Actual CPU application recovery from a private pure server's unavailable required package. */
public class AuditPureConnectionFailure {
  private static final String EXPECTED_ERROR = "Missing server PK3s: baseq3/z_qa_cgame.pk3";

  private static final class Audio implements AudioBackend {
    private int sounds;
    private long voices;

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
      return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "CPU pure failure audit");
    }

    public void close() {}
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !Boolean.parseBoolean(args[3]))
      throw new IllegalArgumentException("Expected private port, games, output, and pure=true");
    int port = Integer.parseInt(args[0]);
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid private server port");
    Path games = Path.of(args[1]);
    Path output = Path.of(args[2]);
    Files.createDirectories(output);
    var audio = new Audio();
    try (var fs = Pk3FileSystem.mount(games, "baseq3");
        var saved = new GameFileStore(output.resolve("saved"));
        var session =
            new QuakeSession(fs, saved, audio, null, null, "Pure failure QA", 1280, 720)) {
      Q3Ui initialUi = session.ui();
      String initialUiPack = fs.which(new VirtualPath("vm/ui.qvm")).orElseThrow().container();
      String initialCgamePack = fs.which(new VirtualPath("vm/cgame.qvm")).orElseThrow().container();
      if (!initialUiPack.endsWith("zz_client_override.pk3")
          || !initialCgamePack.endsWith("zz_client_override.pk3"))
        throw new AssertionError("Failure fixture lacks its client-only fallback modules");
      if (session.advance(16, 1280, 720).commands().isEmpty())
        throw new AssertionError("Original main menu did not render before connection");

      session.command("connect 127.0.0.1:" + port);
      int connectingFrames = 0;
      long deadline = System.nanoTime() + 25_000_000_000L;
      String error = "";
      while (System.nanoTime() < deadline) {
        session.networkTick(1280, 720);
        session.advance(16, 1280, 720);
        connectingFrames++;
        if (session.playing() || session.client() != null || session.server() != null)
          throw new AssertionError(
              "Missing required package initialized a game: " + session.consoleLines());
        error = session.cvars().string("com_errorMessage");
        if (!error.isEmpty()) break;
        Thread.sleep(16);
      }
      if (!EXPECTED_ERROR.equals(error))
        throw new AssertionError(
            "Expected package preflight failure, received: "
                + error
                + "; console="
                + session.consoleLines());
      if (session.networked()
          || session.playing()
          || !session.menuVisible()
          || session.ui().state() != Q3Ui.State.RUNNING)
        throw new AssertionError("Missing package did not return to the running original menu");
      if (session.ui() != initialUi)
        throw new AssertionError(
            "Preflight closed the existing original UI before rejecting content");
      if (!fs.which(new VirtualPath("vm/ui.qvm")).orElseThrow().container().equals(initialUiPack)
          || !fs.which(new VirtualPath("vm/cgame.qvm"))
              .orElseThrow()
              .container()
              .equals(initialCgamePack))
        throw new AssertionError("Failure retained the server's filtered package view");
      int menuFrames = 0;
      for (int i = 0; i < 10; i++) {
        if (session.advance(16, 1280, 720).commands().isEmpty())
          throw new AssertionError("Original menu stopped after preflight failure");
        menuFrames++;
      }

      session.cvars().set("bot_enable", "0", CvarSystem.Source.ENGINE);
      session.command("map q3dm1");
      session.advance(16, 1280, 720);
      if (!session.playing() || session.networked() || session.server() == null)
        throw new AssertionError("Local game could not start after missing-package recovery");
      session.input().key('w', true, session.inputTime());
      int localViews = 0;
      for (int i = 0; i < 60; i++) {
        var frame = session.advance(16, 1280, 720);
        int views =
            (int) frame.commands().stream().filter(CgameFrame.View.class::isInstance).count();
        if (views == 0) throw new AssertionError("Local gameplay frame omitted its world view");
        localViews += views;
      }
      session.command("disconnect");
      var restored = session.advance(16, 1280, 720);
      if (session.playing()
          || session.networked()
          || !session.menuVisible()
          || restored.commands().isEmpty())
        throw new AssertionError("Local disconnect did not restore the original menu");
      String result =
          "{\"error\":\""
              + EXPECTED_ERROR
              + "\",\"connectingFrames\":"
              + connectingFrames
              + ",\"menuFramesAfterFailure\":"
              + menuFrames
              + ",\"initialUiPreserved\":true,\"localFramesAfterFailure\":60,\"localViews\":"
              + localViews
              + ",\"pureServer\":true}";
      Files.writeString(output.resolve("failure-result.json"), result + "\n");
      System.out.println("PASS " + result);
    }
  }
}
