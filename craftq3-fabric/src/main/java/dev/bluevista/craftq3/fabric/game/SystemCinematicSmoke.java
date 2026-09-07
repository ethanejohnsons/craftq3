package dev.bluevista.craftq3.fabric.game;

import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.render.Q3Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Original cinematic command/menu transitions through the real Minecraft screen. */
final class SystemCinematicSmoke {
  private final long start = System.nanoTime();
  private int stage;
  private boolean captured, ready, finished;
  private long samples;

  void step(QuakeSession session, Q3Screen screen, int frame) {
    if (finished || frame < 20) return;
    if (System.nanoTime() - start > 45_000_000_000L)
      throw new IllegalStateException("System cinematic QA timeout at stage " + stage);
    if (stage == 0) {
      key(screen, GLFW.GLFW_KEY_ESCAPE);
      session.ui().setMenu(Q3Ui.Menu.MAIN);
      key(screen, GLFW.GLFW_KEY_GRAVE_ACCENT);
      session.command("set nextmap \"map q3dm17\"; cinematic idlogo");
      stage = 1;
    } else if (stage == 1) {
      if (session.cinematicPlaying()) {
        var info = session.cinematicInfo().orElseThrow();
        samples = Math.max(samples, info.consumedSamples());
        if (info.frame() >= 90 && !captured) {
          captured = true;
          capture("movie");
        }
      } else if (session.playing()) {
        require(captured && samples == 129150, "Movie did not play its complete soundtrack");
        require(session.world().mapName().equals("maps/q3dm17.bsp"), "nextmap lost destination");
        session.command("give all");
        stage = 2;
      }
    } else if (stage == 2) {
      require(session.playing(), "Original gameplay disappeared after movie");
      session.command("disconnect");
      stage = 3;
    } else if (stage == 3 && !session.playing()) {
      session.ui().setMenu(Q3Ui.Menu.MAIN);
      for (int i = 0; i < 4; i++) menuKey(session, 133);
      menuKey(session, 13);
      menuKey(session, 13);
      stage = 4;
    } else if (stage == 4 && session.cinematicPlaying()) {
      require(
          session.cvars().string("cl_cinematic").equals("video/idlogo.roq"),
          "Original menu selected an unexpected movie");
      if (session.cinematicInfo().orElseThrow().milliseconds() >= 500) {
        key(screen, GLFW.GLFW_KEY_SPACE);
        stage = 5;
      }
    } else if (stage == 5 && !session.cinematicPlaying()) {
      require(session.menuVisible() && !session.playing(), "Skip failed to restore main menu");
      require(session.cvars().string("cl_cinematic").isEmpty(), "Movie state survived skip");
      ready = true;
    }
  }

  void finish(QuakeSession session) {
    if (!ready || finished) return;
    require(session.audioDiagnostics().failures() == 0, "Native audio failed");
    require(session.audioDiagnostics().activeLoops() == 0, "Movie retained world loops");
    finished = true;
    CraftQ3Client.LOGGER.info(
        "CraftQ3 system cinematic PASS: samples={} console=true nextmap=true original-menu=true"
            + " screen-skip=true returned-menu=true {}",
        samples,
        session.audioDiagnostics());
    var minecraft = Minecraft.getInstance();
    String filename = "craftq3-system-cinematic-return-menu.png";
    Screenshot.grab(
        net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(),
        filename,
        minecraft.gameRenderer.mainRenderTarget(),
        1,
        message -> {
          SmokeResult.passed("lifecycle", filename);
          minecraft.execute(
              () -> {
                minecraft.gui.setScreen(null);
                minecraft.stop();
              });
        });
  }

  private static void key(Q3Screen screen, int key) {
    screen.keyPressed(new KeyEvent(key, 0, 0));
    screen.keyReleased(new KeyEvent(key, 0, 0));
  }

  private static void menuKey(QuakeSession session, int key) {
    session.menuKey(key, true);
    session.menuKey(key, false);
  }

  private static void capture(String stage) {
    var minecraft = Minecraft.getInstance();
    Screenshot.takeScreenshot(
        minecraft.gameRenderer.mainRenderTarget(),
        image -> {
          try (image) {
            image.writeToFile(
                java.nio.file.Path.of("/tmp/craftq3-system-cinematic-" + stage + ".png"));
          } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
          }
        });
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
