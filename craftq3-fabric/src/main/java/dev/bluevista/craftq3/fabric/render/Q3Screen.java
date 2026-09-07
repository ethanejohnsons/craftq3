package dev.bluevista.craftq3.fabric.render;

import com.mojang.blaze3d.platform.InputConstants;
import dev.bluevista.craftq3.client.input.Q3Input;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.render.FrameTimings;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Pure-Q3 host screen: all world, camera, weapon and HUD submissions come from original cgame. */
public final class Q3Screen extends Screen implements QuakeInputView {
  private final QuakeSession session;
  private final dev.bluevista.craftq3.fabric.game.GameplaySmoke inputSmoke;
  private final dev.bluevista.craftq3.fabric.game.BotGameplaySmoke botSmoke;
  private boolean captureQueued;
  private Blaze3dRenderBackend backend;
  private int backendGeneration, frameGeneration;
  private final dev.bluevista.craftq3.fabric.game.LifecycleSmoke lifecycleSmoke =
      dev.bluevista.craftq3.fabric.game.LifecycleSmoke.enabled()
          ? new dev.bluevista.craftq3.fabric.game.LifecycleSmoke()
          : null;
  private final FrameTimings timings = new FrameTimings();
  private final Set<Integer> pressedKeys = new HashSet<>();
  private final Set<Integer> menuKeys = new HashSet<>();
  private final dev.bluevista.craftq3.client.input.ConsoleEditor console =
      new dev.bluevista.craftq3.client.input.ConsoleEditor();
  private int consoleScroll;
  private CgameFrame frame;
  private long previousTime = System.nanoTime(), remainingNanos;
  private boolean captured, consoleOpen, diagnostics, closed, failed;
  private int renderedFrames;
  private int lastFramebufferWidth = 1280, lastFramebufferHeight = 720;

  private final java.util.function.Consumer<QuakeSession> bridgeAction;
  private final java.util.concurrent.CompletableFuture<Void> ready;

  public Q3Screen(QuakeSession session) {
    this(session, game -> game.bridgeMessage("Minecraft bridge is unavailable in this host."));
  }

  public Q3Screen(QuakeSession session, java.util.function.Consumer<QuakeSession> bridgeAction) {
    this(session, bridgeAction, java.util.concurrent.CompletableFuture.completedFuture(null));
  }

  public Q3Screen(
      QuakeSession session,
      java.util.function.Consumer<QuakeSession> bridgeAction,
      java.util.concurrent.CompletableFuture<Void> ready) {
    super(Component.literal("CraftQ3: " + session.world().mapName()));
    this.session = session;
    this.ready = java.util.Objects.requireNonNull(ready);
    this.bridgeAction = java.util.Objects.requireNonNull(bridgeAction);
    inputSmoke =
        dev.bluevista.craftq3.fabric.game.GameplaySmoke.inputEnabled()
            ? new dev.bluevista.craftq3.fabric.game.GameplaySmoke(session)
            : null;
    botSmoke =
        dev.bluevista.craftq3.fabric.game.BotGameplaySmoke.enabled()
            ? new dev.bluevista.craftq3.fabric.game.BotGameplaySmoke(session)
            : null;
    backend = new Blaze3dRenderBackend(session.materials());
    backendGeneration = session.generation();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    if (closed || failed || !ready.isDone()) return;
    if (ready.isCompletedExceptionally()) {
      CraftQ3Client.LOGGER.error("Could not finish Quake map admission");
      minecraft.gui.setScreen(null);
      return;
    }
    boolean active =
        dev.bluevista.craftq3.fabric.game.GameplaySmoke.captureEnabled()
            || dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.enabled()
            || lifecycleSmoke != null
            || (minecraft.isWindowActive() && minecraft.gui.overlay() == null);
    if (minecraft.getWindow().getWidth() < 1 || minecraft.getWindow().getHeight() < 1) {
      inputFocusLost();
      previousTime = System.nanoTime();
      remainingNanos = 0;
      return;
    }
    lastFramebufferWidth = minecraft.getWindow().getWidth();
    lastFramebufferHeight = minecraft.getWindow().getHeight();
    if (!active) inputFocusLost();
    else if (!captured && !consoleOpen && minecraft.isWindowActive()) capture(true);
    long now = System.nanoTime();
    remainingNanos += Math.clamp(now - previousTime, 0, 200_000_000);
    previousTime = now;
    int elapsed = (int) (remainingNanos / 1_000_000);
    remainingNanos %= 1_000_000;
    if (dev.bluevista.craftq3.fabric.game.GameplaySmoke.captureEnabled()
        || dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.enabled()
        || lifecycleSmoke != null) elapsed = 16;
    // Local pure-Q3 play pauses on lost focus. The Minecraft tick never determines Q3 time.
    try {
      dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.sourceStep(session, renderedFrames);
      if (inputSmoke != null) inputSmoke.step(this, renderedFrames);
      if (botSmoke != null) botSmoke.step(session, this, renderedFrames);
      if (lifecycleSmoke != null) lifecycleSmoke.step(session, this, renderedFrames);
      boolean wasCinematic = session.cinematicPlaying();
      frame =
          session.advance(
              active || session.keepsRunning() ? elapsed : 0,
              minecraft.getWindow().getWidth(),
              minecraft.getWindow().getHeight());
      if (!wasCinematic && session.cinematicPlaying()) {
        consoleOpen = false;
        session.input().releaseAll(session.inputTime());
        capture(true);
      }
      frameGeneration = session.generation();
      if (botSmoke != null) botSmoke.observe(session, frame);
      if (session.exitRequested()) minecraft.execute(() -> minecraft.gui.setScreen(null));
      else if (session.takeBridgeRequest()) {
        inputFocusLost();
        minecraft.execute(
            () -> {
              if (!closed && minecraft.gui.screen() == this) bridgeAction.accept(session);
            });
      }
    } catch (RuntimeException e) {
      failed = true;
      CraftQ3Client.LOGGER.error("Original Quake game/client VM failed", e);
      minecraft.execute(
          () -> {
            minecraft.gui.setScreen(null);
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
                && (Boolean.getBoolean("craftq3.playCapture")
                    || dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.enabled()
                    || lifecycleSmoke != null)) minecraft.stop();
          });
      return;
    }
    if (diagnostics) {
      var timing = timings.snapshot();
      graphics.fill(5, 5, 300, 45, 0xc0101820);
      graphics.text(
          font,
          String.format(Locale.ROOT, "CraftQ3 %.0f FPS | QVM game + cgame", timing.fps()),
          10,
          10,
          0xff82e5bd,
          false);
      graphics.text(
          font,
          "F8 diagnostics | ` console | Esc menu | Shift+Esc Minecraft",
          10,
          23,
          0xffeeeeee,
          false);
    }
  }

  @Override
  public void tick() {
    if (closed || failed || !ready.isDone() || ready.isCompletedExceptionally()) return;
    int width = minecraft.getWindow().getWidth(), height = minecraft.getWindow().getHeight();
    if (session.keepsRunning() && (width < 1 || height < 1)) {
      inputFocusLost();
      try {
        frame = session.backgroundFrame(lastFramebufferWidth, lastFramebufferHeight);
        frameGeneration = session.generation();
      } catch (RuntimeException failure) {
        failed = true;
        CraftQ3Client.LOGGER.error("Background Quake client failed", failure);
        minecraft.execute(() -> minecraft.gui.setScreen(null));
      }
    } else session.networkTick(width, height);
  }

  @Override
  public void drawFrame() {
    if (closed || frame == null || frameGeneration != session.generation()) return;
    if (backendGeneration != session.generation()) {
      backend.close();
      backend = new Blaze3dRenderBackend(session.materials());
      backendGeneration = session.generation();
    }
    timings.frame(System.nanoTime());
    int width = minecraft.getWindow().getWidth(), height = minecraft.getWindow().getHeight();
    if (session.uiFrame() != null && session.fullscreenMenu()) {
      backend.render(session.world(), session.uiFrame(), width, height);
    } else {
      backend.render(session.world(), frame, width, height);
      if (session.uiFrame() != null)
        backend.render(session.world(), session.uiFrame(), width, height, false);
    }
    if (consoleOpen)
      backend.render(
          session.world(),
          session.consoleFrame(console.text(), console.cursor(), width, height, consoleScroll),
          width,
          height,
          false);
  }

  @Override
  public void endFrame() {
    if (frame == null || closed || !ready.isDone()) return;
    renderedFrames++;
    dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.finish(
        minecraft, session, renderedFrames);
    if (lifecycleSmoke != null) {
      try {
        lifecycleSmoke.finish(session, renderedFrames);
      } catch (RuntimeException e) {
        CraftQ3Client.LOGGER.error("CraftQ3 lifecycle smoke FAILED", e);
        minecraft.gui.setScreen(null);
        minecraft.stop();
      }
    }
    if (!captureQueued
        && (botSmoke == null ? renderedFrames == 180 : botSmoke.ready(session, renderedFrames))
        && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.playCapture")) {
      captureQueued = true;
      if (botSmoke != null) {
        try {
          botSmoke.verify(session);
        } catch (RuntimeException e) {
          CraftQ3Client.LOGGER.error("CraftQ3 original-bot render smoke FAILED", e);
          minecraft.gui.setScreen(null);
          minecraft.stop();
          return;
        }
      }
      if (inputSmoke != null) {
        try {
          inputSmoke.verify(session);
        } catch (RuntimeException e) {
          CraftQ3Client.LOGGER.error("CraftQ3 gameplay input smoke FAILED", e);
          minecraft.gui.setScreen(null);
          minecraft.stop();
          return;
        }
      }
      String name =
          "craftq3-play-"
              + session.world().mapName().replace("maps/", "").replace(".bsp", "")
              + (inputSmoke == null ? "" : "-input")
              + (botSmoke == null ? "" : "-bots")
              + (dev.bluevista.craftq3.fabric.game.GameplaySmoke.consoleEnabled() ? "-console" : "")
              + (dev.bluevista.craftq3.fabric.game.GameplaySmoke.menuEnabled() ? "-menu" : "")
              + "-"
              + com.mojang.blaze3d.systems.RenderSystem.getDevice()
                  .getDeviceInfo()
                  .backendName()
                  .toLowerCase(Locale.ROOT)
              + ".png";
      CraftQ3Client.LOGGER.info(
          "CraftQ3 gameplay capture: serverFrames={}, entities={}, audio={}",
          session.server().frameNumber(),
          session.server().entityCount(),
          session.audioDiagnostics());
      net.minecraft.client.Screenshot.grab(
          net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(),
          name,
          minecraft.gameRenderer.mainRenderTarget(),
          1,
          message -> {
            dev.bluevista.craftq3.fabric.game.SmokeResult.passed("play", name);
            CraftQ3Client.LOGGER.info("CraftQ3 gameplay capture: {}", message.getString());
            minecraft.execute(
                () -> {
                  minecraft.gui.setScreen(null);
                  minecraft.stop();
                });
          });
    }
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    boolean first = pressedKeys.add(event.key());
    if (event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT) {
      if (first) {
        consoleOpen = !consoleOpen;
        session.input().releaseAll(session.inputTime());
        capture(!consoleOpen);
      }
      return true;
    }
    if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
      if (session.cinematicKey(27, true) || session.demoKey(27, true)) {
        consoleOpen = false;
        capture(true);
      } else if (consoleOpen) {
        consoleOpen = false;
        capture(true);
      } else if ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) onClose();
      else if (session.menuVisible()) {
        menuKeys.add(27);
        session.menuKey(27, true);
      } else session.openMenu();
      return true;
    }
    if (consoleOpen) {
      switch (event.key()) {
        case GLFW.GLFW_KEY_ENTER -> {
          console.accept().ifPresent(session::command);
          consoleScroll = 0;
        }
        case GLFW.GLFW_KEY_BACKSPACE -> console.backspace();
        case GLFW.GLFW_KEY_DELETE -> console.delete();
        case GLFW.GLFW_KEY_LEFT -> console.left();
        case GLFW.GLFW_KEY_RIGHT -> console.right();
        case GLFW.GLFW_KEY_HOME -> console.home();
        case GLFW.GLFW_KEY_END -> console.end();
        case GLFW.GLFW_KEY_UP -> console.previous();
        case GLFW.GLFW_KEY_DOWN -> console.next();
        case GLFW.GLFW_KEY_PAGE_UP -> consoleScroll = Math.min(65536, consoleScroll + 8);
        case GLFW.GLFW_KEY_PAGE_DOWN -> consoleScroll = Math.max(0, consoleScroll - 8);
        case GLFW.GLFW_KEY_TAB ->
            console
                .completionPrefix()
                .ifPresent(prefix -> console.complete(session.complete(prefix)));
        default -> {}
      }
      return true;
    }
    if (event.key() == GLFW.GLFW_KEY_F8) {
      if (first) diagnostics = !diagnostics;
      return true;
    }
    int key = quakeKey(event.key());
    if (key >= 0) pressQuakeKey(key);
    return true;
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    pressedKeys.remove(event.key());
    int key = quakeKey(event.key());
    if (key >= 0 && pressedKeys.stream().noneMatch(other -> quakeKey(other) == key))
      releaseQuakeKey(key);
    return true;
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    if (!consoleOpen && session.menuVisible()) {
      if (event.codepoint() >= 32 && event.codepoint() <= 255)
        session.menuKey(1024 + event.codepoint(), true);
      return true;
    }
    if (consoleOpen
        && event.codepoint() >= 32
        && event.codepoint() <= 255
        && event.codepoint() != '`'
        && event.codepoint() != '~') console.insert(event.codepoint());
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (!consoleOpen) {
      if (!captured) capture(true);
      int key = mouseKey(event.button());
      if (key >= 0) pressQuakeKey(key);
    }
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    int key = mouseKey(event.button());
    if (key >= 0) releaseQuakeKey(key);
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (consoleOpen && vertical != 0) {
      consoleScroll = (int) Math.clamp(consoleScroll + Math.signum(vertical) * 3, 0, 65536);
    } else if (vertical != 0) {
      int key = vertical > 0 ? Q3Input.WHEEL_UP : Q3Input.WHEEL_DOWN;
      for (int i = 0; i < Math.min(10, Math.max(1, Math.abs(vertical))); i++) {
        pressQuakeKey(key);
        releaseQuakeKey(key);
      }
    }
    return true;
  }

  private void pressQuakeKey(int key) {
    if (session.cinematicKey(key, true)) return;
    if (session.menuVisible()) {
      menuKeys.add(key);
      session.menuKey(key, true);
    } else if (!session.demoKey(key, true)) session.input().key(key, true, session.inputTime());
  }

  private void releaseQuakeKey(int key) {
    if (menuKeys.remove(key)) session.menuKey(key, false);
    session.input().key(key, false, session.inputTime());
  }

  private void capture(boolean capture) {
    if (closed || capture == captured) return;
    captured = capture;
    var window = minecraft.getWindow();
    InputConstants.grabOrReleaseMouse(
        window,
        capture ? InputConstants.CURSOR_DISABLED : InputConstants.CURSOR_NORMAL,
        window.getScreenWidth() / 2.0,
        window.getScreenHeight() / 2.0);
    minecraft.mouseHandler.setIgnoreFirstMove();
  }

  @Override
  public boolean cursorCaptured() {
    return captured;
  }

  @Override
  public void mouseDelta(double dx, double dy) {
    if (consoleOpen) return;
    if (session.cinematicPlaying()) return;
    if (session.menuVisible()) session.menuMouse(dx, dy);
    else session.input().mouse(dx, dy);
  }

  @Override
  public void inputFocusLost() {
    if (closed) return;
    if (dev.bluevista.craftq3.fabric.game.GameplaySmoke.captureEnabled()) {
      capture(false);
      return;
    }
    session.input().releaseAll(session.inputTime());
    for (int key : Set.copyOf(menuKeys)) session.menuKey(key, false);
    menuKeys.clear();
    pressedKeys.clear();
    capture(false);
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
    inputFocusLost();
    closed = true;
    try {
      backend.close();
    } finally {
      try {
        session.close();
      } catch (java.io.IOException e) {
        CraftQ3Client.LOGGER.warn("Quake session shutdown", e);
      }
    }
  }

  static int mouseKey(int button) {
    return switch (button) {
      case 0 -> Q3Input.MOUSE1;
      case 1 -> Q3Input.MOUSE2;
      case 2 -> Q3Input.MOUSE3;
      case 3, 4 -> Q3Input.MOUSE1 + button;
      default -> -1;
    };
  }

  static int quakeKey(int key) {
    if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return key + ('a' - 'A');
    if (key >= 32 && key <= 126) return key;
    if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F15) return 145 + key - GLFW.GLFW_KEY_F1;
    return switch (key) {
      case GLFW.GLFW_KEY_TAB -> 9;
      case GLFW.GLFW_KEY_ENTER -> 13;
      case GLFW.GLFW_KEY_ESCAPE -> 27;
      case GLFW.GLFW_KEY_BACKSPACE -> 127;
      case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> 128;
      case GLFW.GLFW_KEY_CAPS_LOCK -> 129;
      case GLFW.GLFW_KEY_PAUSE -> 131;
      case GLFW.GLFW_KEY_UP -> 132;
      case GLFW.GLFW_KEY_DOWN -> 133;
      case GLFW.GLFW_KEY_LEFT -> 134;
      case GLFW.GLFW_KEY_RIGHT -> 135;
      case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> 136;
      case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> 137;
      case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> 138;
      case GLFW.GLFW_KEY_INSERT -> 139;
      case GLFW.GLFW_KEY_DELETE -> 140;
      case GLFW.GLFW_KEY_PAGE_DOWN -> 141;
      case GLFW.GLFW_KEY_PAGE_UP -> 142;
      case GLFW.GLFW_KEY_HOME -> 143;
      case GLFW.GLFW_KEY_END -> 144;
      default -> -1;
    };
  }
}
