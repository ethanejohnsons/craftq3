package dev.bluevista.craftq3.fabric.game;

import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.render.Q3Screen;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/** Opt-in development proof through screen callbacks; never enabled in a distributable launch. */
public final class GameplaySmoke {
  private final byte[] initial;
  private final int weapon, ammo;
  private final QuakeSession session;
  private int pausedAt = -1;
  private int consoleBotsBefore = -1;

  public GameplaySmoke(QuakeSession session) {
    this.session = session;
    initial = session.server().playerState(0);
    var state = ByteBuffer.wrap(initial).order(ByteOrder.LITTLE_ENDIAN);
    weapon = state.getInt(144);
    ammo = state.getInt(376 + weapon * 4);
  }

  public static boolean captureEnabled() {
    return Boolean.getBoolean("craftq3.playCapture")
        && FabricLoader.getInstance().isDevelopmentEnvironment();
  }

  public static boolean inputEnabled() {
    return captureEnabled() && Boolean.getBoolean("craftq3.playInputTest");
  }

  public static boolean menuEnabled() {
    return inputEnabled() && Boolean.getBoolean("craftq3.playMenuTest");
  }

  public static boolean consoleEnabled() {
    return inputEnabled() && Boolean.getBoolean("craftq3.playConsoleTest");
  }

  public void step(Q3Screen screen, int frame) {
    if (consoleEnabled()) {
      if (frame == 120) {
        press(screen, GLFW.GLFW_KEY_GRAVE_ACCENT);
        type(screen, "sensitiv");
        press(screen, GLFW.GLFW_KEY_TAB);
        type(screen, "6");
        press(screen, GLFW.GLFW_KEY_ENTER);
        consoleBotsBefore = botCount();
        type(screen, "addbot sarge 3");
        press(screen, GLFW.GLFW_KEY_ENTER);
        type(screen, "echo ^2Original Quake console: history and completion");
        press(screen, GLFW.GLFW_KEY_ENTER);
      } else if (frame == 130) {
        press(screen, GLFW.GLFW_KEY_UP);
        screen.mouseDelta(1000, 1000);
      }
    }
    if (menuEnabled()) {
      if (frame == 120) {
        pausedAt = session.time();
        screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
      } else if (frame == 145) {
        if (!session.menuVisible() || session.uiFrame() == null || session.time() != pausedAt)
          throw new IllegalStateException("Original in-game menu did not pause the local Q3 clock");
        var client = Minecraft.getInstance();
        String backend =
            com.mojang.blaze3d.systems.RenderSystem.getDevice()
                .getDeviceInfo()
                .backendName()
                .toLowerCase(java.util.Locale.ROOT);
        net.minecraft.client.Screenshot.grab(
            FabricLoader.getInstance().getGameDir().toFile(),
            "craftq3-ingame-menu-" + backend + ".png",
            client.gameRenderer.mainRenderTarget(),
            1,
            message ->
                CraftQ3Client.LOGGER.info(
                    "Original in-game menu capture: {}", message.getString()));
      } else if (frame == 160) {
        screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
      }
    }
    switch (frame) {
      case 10 -> {
        var result =
            Minecraft.getInstance()
                .getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
        if (result != SoundEngine.PlayResult.NOT_STARTED)
          throw new IllegalStateException("Minecraft playback escaped pure-Q3 audio ownership");
      }
      case 20 -> screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_W, 0, 0));
      case 35 -> screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_W, 0, 0));
      case 40 -> screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_SPACE, 0, 0));
      case 42 -> screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_SPACE, 0, 0));
      case 60 -> screen.mouseDelta(160, 120);
      case 70 -> screen.mouseClicked(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)), false);
      case 100 -> screen.mouseReleased(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
      default -> {}
    }
  }

  private static void press(Q3Screen screen, int key) {
    screen.keyPressed(new KeyEvent(key, 0, 0));
    screen.keyReleased(new KeyEvent(key, 0, 0));
  }

  private static void type(Q3Screen screen, String text) {
    text.chars()
        .forEach(value -> screen.charTyped(new net.minecraft.client.input.CharacterEvent(value)));
  }

  public void verify(QuakeSession session) {
    if (consoleEnabled() && session.cvars().integer("sensitivity") != 6)
      throw new IllegalStateException("Console completion/dispatch did not update the cvar");
    if (consoleEnabled() && (consoleBotsBefore < 0 || botCount() != consoleBotsBefore + 1))
      throw new IllegalStateException("Console addbot did not reach the local original qagame");
    if (menuEnabled() && (pausedAt < 0 || session.menuVisible() || session.time() <= pausedAt))
      throw new IllegalStateException("Original in-game menu did not resume the local Q3 clock");
    var before = ByteBuffer.wrap(initial).order(ByteOrder.LITTLE_ENDIAN);
    var after = ByteBuffer.wrap(session.server().playerState(0)).order(ByteOrder.LITTLE_ENDIAN);
    double moved =
        Math.hypot(
            after.getFloat(20) - before.getFloat(20), after.getFloat(24) - before.getFloat(24));
    int remaining = after.getInt(376 + weapon * 4);
    if (moved < 20 || remaining >= ammo || Math.abs(after.getFloat(156) - before.getFloat(156)) < 1)
      throw new IllegalStateException(
          "Gameplay input smoke failed: moved=" + moved + ", ammo=" + ammo + " -> " + remaining);
    if (session.audioDiagnostics().failures() != 0
        || session.audioDiagnostics().startedVoices() > 100)
      throw new IllegalStateException(
          "Gameplay audio did not retain stable looping voices: " + session.audioDiagnostics());
    CraftQ3Client.LOGGER.info(
        "CraftQ3 gameplay input smoke PASS: moved={}, ammo={} -> {}, health={}, yaw={}, audio={}",
        moved,
        ammo,
        remaining,
        after.getInt(184),
        after.getFloat(156),
        session.audioDiagnostics());
  }

  private int botCount() {
    int bots = 0;
    for (int slot = 0; slot < session.cvars().integer("sv_maxclients"); slot++)
      if (session.server().isBot(slot)) bots++;
    return bots;
  }
}
