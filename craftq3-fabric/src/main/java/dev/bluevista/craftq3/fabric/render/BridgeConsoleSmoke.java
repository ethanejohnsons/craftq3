package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.fabric.bridge.BridgeGame;
import dev.bluevista.craftq3.render.CgameFrame;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

/** Exercises actual bridge screen callbacks and original console/weapon QVM commands. */
public final class BridgeConsoleSmoke {
  private static boolean weapon, rebound, projectile, restored, character;
  private static final java.util.Set<String> observedModels = new java.util.TreeSet<>();

  private BridgeConsoleSmoke() {}

  public static void launchFromChat(net.minecraft.client.Minecraft client) {
    var chat =
        new net.minecraft.client.gui.screens.ChatScreen(
            Boolean.getBoolean("craftq3.bridgeConsoleFresh") ? "/q3 bridge fresh" : "/q3 bridge",
            false);
    client.gui.setScreen(chat);
    chat.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
    client.schedule(
        () -> {
          if (!(client.gui.screen() instanceof BridgeScreen)) {
            dev.bluevista.craftq3.fabric.CraftQ3Client.LOGGER.error(
                "Bridge console chat-entry FAILED: chat submission removed the bridge screen");
            client.stop();
          } else System.out.println("CraftQ3 bridge chat-entry PASS");
        });
  }

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && (Boolean.getBoolean("craftq3.bridgeConsoleSmoke")
            || !System.getProperty("craftq3.bridgeSettingsSmoke", "").isEmpty());
  }

  public static void step(BridgeScreen screen, BridgeGame game, int frame) {
    if (frame == 0 && System.getProperty("craftq3.bridgeSettingsSmoke", "").equals("verify")) {
      restored =
          game.cvars().number("sensitivity") == 6
              && game.input().bindings().binding('f').equals("weapon 5");
      if (!restored)
        throw new IllegalStateException(
            "Bridge settings did not survive process restart: sensitivity="
                + game.cvars().number("sensitivity")
                + " bind="
                + game.input().bindings().binding('f'));
      System.out.println("CraftQ3 bridge settings restored sensitivity=6 bind-f=weapon-5");
    }
    switch (frame) {
      case 20 -> {
        screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_GRAVE_ACCENT, 0, 0));
        screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_GRAVE_ACCENT, 0, 0));
        screen.keyReleased(new KeyEvent(GLFW.GLFW_KEY_GRAVE_ACCENT, 0, 0));
        if (screen.cursorCaptured())
          throw new IllegalStateException("Console toggle repeated or mouse captured");
        type(screen, "give all");
        press(screen, GLFW.GLFW_KEY_ENTER);
      }
      case 40 -> {
        type(screen, "weapon 7");
        press(screen, GLFW.GLFW_KEY_ENTER);
        type(screen, "bind f \"weapon 5\"");
        press(screen, GLFW.GLFW_KEY_ENTER);
        type(screen, "cg_deferPlayers 0; model visor; headmodel visor; cg_thirdPerson 1");
        press(screen, GLFW.GLFW_KEY_ENTER);
      }
      case 60 -> {
        if (!character)
          throw new IllegalStateException(
              "Console model commands did not render the Visor body: "
                  + observedModels
                  + " userinfo="
                  + game.cvars()
                      .infoString(dev.bluevista.craftq3.core.cvar.CvarSystem.USERINFO, 1024)
                  + " third="
                  + game.cvars().string("cg_thirdPerson")
                  + " defer="
                  + game.cvars().string("cg_deferPlayers"));
        type(screen, "cg_thirdPerson 0");
        press(screen, GLFW.GLFW_KEY_ENTER);
        weapon = game.selectedWeapon() == 7;
        if (!weapon)
          throw new IllegalStateException(
              "Original QVM did not grant/select railgun: " + game.selectedWeapon());
        press(screen, GLFW.GLFW_KEY_ESCAPE);
        if (!screen.cursorCaptured())
          throw new IllegalStateException("Console Escape did not resume mouse input");
        press(screen, GLFW.GLFW_KEY_F);
      }
      case 80 -> {
        rebound = game.selectedWeapon() == 5;
        if (!rebound)
          throw new IllegalStateException(
              "Console weapon binding failed: " + game.selectedWeapon());
        screen.mouseClicked(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)), false);
      }
      case 110 -> screen.mouseReleased(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
      case 150 -> {
        press(screen, GLFW.GLFW_KEY_GRAVE_ACCENT);
        type(screen, "sensitiv");
        press(screen, GLFW.GLFW_KEY_TAB);
        type(screen, "6");
        press(screen, GLFW.GLFW_KEY_ENTER);
        type(screen, "echo ^2Bridge console: original weapons, history and completion");
        press(screen, GLFW.GLFW_KEY_ENTER);
        press(screen, GLFW.GLFW_KEY_UP);
        screen.mouseDelta(1000, 1000);
      }
      default -> {}
    }
  }

  static void observe(CgameFrame frame) {
    for (var command : frame.commands())
      if (command instanceof CgameFrame.View view)
        for (var entity : view.entities()) {
          if (entity.model() != null && entity.visible(false))
            observedModels.add(entity.model().name());
          if (entity.model() != null
              && entity.visible(false)
              && entity.model().name().startsWith("models/players/visor/")) character = true;
          if (!entity.depthHack()
              && entity.model() != null
              && entity.model().name().toLowerCase(java.util.Locale.ROOT).contains("rocket"))
            projectile = true;
        }
  }

  private static void press(BridgeScreen screen, int key) {
    screen.keyPressed(new KeyEvent(key, 0, 0));
    screen.keyReleased(new KeyEvent(key, 0, 0));
  }

  private static void type(BridgeScreen screen, String text) {
    text.chars().forEach(value -> screen.charTyped(new CharacterEvent(value)));
  }

  public static String result() {
    if (!weapon || !rebound || !projectile || !character)
      throw new IllegalStateException(
          "Bridge console incomplete: weapon="
              + weapon
              + " rebound="
              + rebound
              + " projectile="
              + projectile);
    return "chat-entry=true console=true character=visor original-loadout=true weapon-switch=true"
        + " binding=true rocket=true focus=true settings-restored="
        + restored;
  }
}
