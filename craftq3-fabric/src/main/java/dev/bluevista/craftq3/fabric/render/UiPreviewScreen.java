package dev.bluevista.craftq3.fabric.render;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.game.UiPreviewSession;
import dev.bluevista.craftq3.render.CgameFrame;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Original UI GPU proof. Production entry remains separate until menu commands are integrated. */
public final class UiPreviewScreen extends Screen implements QuakeInputView {
  private final UiPreviewSession session;
  private final Blaze3dRenderBackend backend;
  private final Set<Integer> down = new HashSet<>();
  private CgameFrame frame;
  private long previous = System.nanoTime();
  private int frames;
  private boolean captured, closed, failed;

  public UiPreviewScreen(UiPreviewSession session) {
    super(Component.literal("CraftQ3 original UI preview"));
    this.session = session;
    backend = new Blaze3dRenderBackend(session.materials());
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    if (closed || failed) return;
    boolean captureRun = Boolean.getBoolean("craftq3.uiCapture");
    long now = System.nanoTime();
    int elapsed = captureRun ? 16 : Math.clamp((now - previous) / 1_000_000, 0, 200);
    previous = now;
    if (minecraft.isWindowActive() && minecraft.gui.overlay() == null) capture(true);
    else inputFocusLost();
    if (minecraft.getWindow().getWidth() < 1 || minecraft.getWindow().getHeight() < 1) return;
    try {
      if (captureRun && Boolean.getBoolean("craftq3.uiMainTest") && frames == 40) {
        keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        keyReleased(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        mouseDelta(320, 240);
      }
      frame =
          session.frame(
              elapsed, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
    } catch (RuntimeException e) {
      failed = true;
      CraftQ3Client.LOGGER.error("Original UI preview failed", e);
      minecraft.execute(
          () -> {
            minecraft.gui.setScreen(null);
            if (captureRun) minecraft.stop();
          });
    }
  }

  @Override
  public void drawFrame() {
    if (frame != null && !closed)
      backend.render(
          session.scene(),
          frame,
          minecraft.getWindow().getWidth(),
          minecraft.getWindow().getHeight());
  }

  @Override
  public void endFrame() {
    if (++frames != 180 || !Boolean.getBoolean("craftq3.uiCapture")) return;
    if (frame == null || frame.commands().isEmpty() || session.audioDiagnostics().failures() != 0) {
      CraftQ3Client.LOGGER.error(
          "UI capture has no scene or failed audio: {}", session.audioDiagnostics());
      minecraft.gui.setScreen(null);
      minecraft.stop();
      return;
    }
    CraftQ3Client.LOGGER.info(
        "CraftQ3 original UI capture: api={}, models={}, shaders={}, sounds={}, commands={}, audio={}",
        session.ui().apiVersion(),
        session.ui().registeredModels(),
        session.ui().registeredShaders(),
        session.ui().registeredSounds(),
        frame.commands().size(),
        session.audioDiagnostics());
    String name =
        "craftq3-ui-"
            + (Boolean.getBoolean("craftq3.uiMainTest") ? "main-" : "")
            + RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT)
            + ".png";
    Screenshot.grab(
        FabricLoader.getInstance().getGameDir().toFile(),
        name,
        minecraft.gameRenderer.mainRenderTarget(),
        1,
        message -> {
          dev.bluevista.craftq3.fabric.game.SmokeResult.passed("ui", name);
          CraftQ3Client.LOGGER.info("CraftQ3 original UI capture: {}", message.getString());
          minecraft.execute(
              () -> {
                minecraft.gui.setScreen(null);
                minecraft.stop();
              });
        });
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.key() == GLFW.GLFW_KEY_F8) {
      onClose();
      return true;
    }
    int key = Q3Screen.quakeKey(event.key());
    if (key >= 0) {
      down.add(key);
      session.ui().key(key, true, session.time());
    }
    return true;
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    int key = Q3Screen.quakeKey(event.key());
    if (key >= 0) {
      down.remove(key);
      session.ui().key(key, false, session.time());
    }
    return true;
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    if (event.codepoint() >= 32 && event.codepoint() <= 255)
      session.ui().key(1024 + event.codepoint(), true, session.time());
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    int key = Q3Screen.mouseKey(event.button());
    if (key >= 0) {
      down.add(key);
      session.ui().key(key, true, session.time());
    }
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    int key = Q3Screen.mouseKey(event.button());
    if (key >= 0) {
      down.remove(key);
      session.ui().key(key, false, session.time());
    }
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (vertical != 0) {
      int key = vertical > 0 ? 184 : 183;
      session.ui().key(key, true, session.time());
      session.ui().key(key, false, session.time());
    }
    return true;
  }

  @Override
  public void mouseDelta(double dx, double dy) {
    if (Double.isFinite(dx) && Double.isFinite(dy))
      session
          .ui()
          .mouse(
              (int) Math.clamp(dx, -65536, 65536),
              (int) Math.clamp(dy, -65536, 65536),
              session.time());
  }

  @Override
  public boolean cursorCaptured() {
    return captured;
  }

  @Override
  public void inputFocusLost() {
    if (closed) return;
    if (session.ui().state() == dev.bluevista.craftq3.client.Q3Ui.State.RUNNING)
      for (int key : Set.copyOf(down)) session.ui().key(key, false, session.time());
    down.clear();
    capture(false);
  }

  private void capture(boolean value) {
    if (closed || value == captured) return;
    captured = value;
    var window = minecraft.getWindow();
    InputConstants.grabOrReleaseMouse(
        window,
        value ? InputConstants.CURSOR_DISABLED : InputConstants.CURSOR_NORMAL,
        window.getScreenWidth() / 2.0,
        window.getScreenHeight() / 2.0);
    minecraft.mouseHandler.setIgnoreFirstMove();
  }

  @Override
  public boolean isPauseScreen() {
    return true;
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {}

  @Override
  public void removed() {
    if (closed) return;
    try {
      inputFocusLost();
    } finally {
      capture(false);
      closed = true;
      try {
        backend.close();
      } finally {
        try {
          session.close();
        } catch (java.io.IOException e) {
          CraftQ3Client.LOGGER.warn("UI preview cleanup", e);
        }
      }
    }
  }
}
