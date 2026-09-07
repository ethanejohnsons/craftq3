package dev.bluevista.craftq3.fabric.game;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.render.Q3Screen;
import dev.bluevista.craftq3.server.Q3Server;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/** Development-only lifecycle assertions through the production session and screen. */
public final class LifecycleSmoke {
  private final SystemCinematicSmoke cinematic =
      Boolean.getBoolean("craftq3.systemCinematicSmoke") ? new SystemCinematicSmoke() : null;
  private Q3Server first, restarted;
  private int cvarHandle;

  public static boolean enabled() {
    return Boolean.getBoolean("craftq3.lifecycleCapture")
        && FabricLoader.getInstance().isDevelopmentEnvironment();
  }

  public void step(QuakeSession session, Q3Screen screen, int frame) {
    if (cinematic != null) {
      cinematic.step(session, screen, frame);
      return;
    }
    switch (frame) {
      case 20 -> {
        // Dismiss the original retail CD-key dialog using its normal Escape path.
        screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
      }
      case 35 -> {
        require(!session.playing() && session.menuVisible(), "Original main menu is missing");
        capture("main");
        session.command(
            "seta name LifecyclePlayer; bind x +gesture; map q3dm17; seta lifecycle_after_map yes");
      }
      case 60 -> {
        require(session.playing() && !session.menuVisible(), "Map command did not enter gameplay");
        require(
            session.cvars().string("lifecycle_after_map").equals("yes"),
            "Commands after map were lost");
        require(
            session.cvars().string("name").equals("LifecyclePlayer"), "Player settings were lost");
        require(session.input().bindings().binding('x').equals("+gesture"), "Bindings were lost");
        cvarHandle = session.cvars().find("name").orElseThrow().handle();
        first = session.server();
        capture("game");
        session.command("map missing_craftq3_lifecycle_map");
      }
      case 80 -> {
        require(
            session.server() == first && session.playing(),
            "Missing map destroyed the current game");
        session.command("map_restart 0");
      }
      case 100 -> {
        require(
            session.playing() && session.server() == first && first.restartCount() == 1,
            "Arena restart did not retain the server connection");
        require(first.snapshotFlags() == 4, "Restart snapshot flag did not toggle");
        require(
            session.cvars().find("name").orElseThrow().handle() == cvarHandle,
            "Cvar handles changed");
        require(session.input().bindings().binding('x').equals("+gesture"), "Reload lost bindings");
        restarted = session.server();
        session.command("disconnect");
      }
      case 120 -> {
        require(
            !session.playing() && session.menuVisible(),
            "Disconnect did not return to original UI");
        require(restarted.state() == Q3Server.State.CLOSED, "Disconnected server remained alive");
        require(
            session.cvars().integer("sv_running") == 0,
            "Disconnected server still advertises running");
        session.command("map q3dm1");
      }
      case 150 -> {
        require(
            session.playing() && session.world().mapName().equals("maps/q3dm1.bsp"),
            "Second map did not load");
        capture("second-map");
        session.command("disconnect");
      }
      default -> {}
    }
  }

  public void finish(QuakeSession session, int frame) {
    if (cinematic != null) {
      cinematic.finish(session);
      return;
    }
    if (frame != 180) return;
    require(!session.playing() && session.menuVisible(), "Final original menu missing");
    require(session.audioDiagnostics().failures() == 0, "Audio failed during map changes");
    require(session.audioDiagnostics().activeLoops() == 0, "World audio loops survived disconnect");
    CraftQ3Client.LOGGER.info(
        "CraftQ3 lifecycle smoke PASS: generations={}, persistent name={}, audio={}",
        session.generation(),
        session.cvars().string("name"),
        session.audioDiagnostics());
    var minecraft = Minecraft.getInstance();
    Screenshot.grab(
        FabricLoader.getInstance().getGameDir().toFile(),
        name("return-menu"),
        minecraft.gameRenderer.mainRenderTarget(),
        1,
        message -> {
          SmokeResult.passed("lifecycle", name("return-menu"));
          CraftQ3Client.LOGGER.info("CraftQ3 lifecycle capture: {}", message.getString());
          minecraft.execute(
              () -> {
                minecraft.gui.setScreen(null);
                minecraft.stop();
              });
        });
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static String name(String stage) {
    return "craftq3-lifecycle-"
        + stage
        + "-"
        + RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT)
        + ".png";
  }

  private static void capture(String stage) {
    var minecraft = Minecraft.getInstance();
    Screenshot.grab(
        FabricLoader.getInstance().getGameDir().toFile(),
        name(stage),
        minecraft.gameRenderer.mainRenderTarget(),
        1,
        message -> CraftQ3Client.LOGGER.info("CraftQ3 lifecycle capture: {}", message.getString()));
  }
}
