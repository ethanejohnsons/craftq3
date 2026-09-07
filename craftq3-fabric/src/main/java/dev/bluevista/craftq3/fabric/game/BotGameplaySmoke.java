package dev.bluevista.craftq3.fabric.game;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.render.Q3Screen;
import dev.bluevista.craftq3.render.CgameFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Opt-in development capture of original bots through the complete client/render path. */
public final class BotGameplaySmoke {
  private final int[] moving = new int[3];
  private int observedFrames, playerModelFrames, submittedPlayerParts;
  private int respawnInputs;
  private boolean attackPressed;

  public static boolean enabled() {
    return GameplaySmoke.captureEnabled() && Boolean.getBoolean("craftq3.playBotTest");
  }

  public BotGameplaySmoke(QuakeSession session) {
    for (String name : new String[] {"sarge", "visor", "anarki"}) {
      if (!session.server().consoleCommand(CommandParser.tokenize("addbot " + name + " 3")))
        throw new IllegalStateException("Original game did not recognize addbot");
    }
  }

  public void observe(QuakeSession session, CgameFrame frame) {
    observedFrames++;
    for (int i = 0; i < moving.length; i++) {
      if (!session.server().isBot(i + 1)) continue;
      var state =
          ByteBuffer.wrap(session.server().playerState(i + 1)).order(ByteOrder.LITTLE_ENDIAN);
      if (state.getInt(4) == 0 && Math.hypot(state.getFloat(32), state.getFloat(36)) > 1)
        moving[i]++;
    }
    int parts = 0;
    for (var command : frame.commands()) {
      if (!(command instanceof CgameFrame.View view) || !view.refdef().worldModel()) continue;
      for (var entity : view.entities()) {
        if (entity.model() != null
            && entity.visible(false)
            && entity.model().tagFrames().getFirst().stream()
                .anyMatch(tag -> tag.name().equals("tag_torso"))) parts++;
      }
    }
    submittedPlayerParts += parts;
    if (parts > 0) playerModelFrames++;
  }

  public void step(QuakeSession session, Q3Screen screen, int frame) {
    var button = new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0));
    if (attackPressed) {
      screen.mouseReleased(button);
      attackPressed = false;
    }
    if (playerMode(session) == 3 && frame % 30 == 0) {
      screen.mouseClicked(button, false);
      attackPressed = true;
      respawnInputs++;
    }
  }

  public boolean ready(QuakeSession session, int renderedFrames) {
    return renderedFrames >= 900 && (playerMode(session) == 0 || renderedFrames >= 1800);
  }

  public void verify(QuakeSession session) {
    if (playerMode(session) != 0)
      throw new IllegalStateException("Original guest did not respawn the player before capture");
    for (int i = 0; i < moving.length; i++) {
      if (!session.server().isBot(i + 1) || moving[i] < 10)
        throw new IllegalStateException(
            "Original bot " + (i + 1) + " did not move in rendered play");
    }
    if (playerModelFrames < 10 || session.audioDiagnostics().failures() != 0)
      throw new IllegalStateException(
          "Bot client/render smoke failed: modelFrames="
              + playerModelFrames
              + ", audio="
              + session.audioDiagnostics());
    CraftQ3Client.LOGGER.info(
        "CraftQ3 original-bot render smoke PASS: frames={}, moving={}, modelFrames={},"
            + " submittedPlayerParts={}, respawnInputs={}, audio={}",
        observedFrames,
        java.util.Arrays.toString(moving),
        playerModelFrames,
        submittedPlayerParts,
        respawnInputs,
        session.audioDiagnostics());
  }

  private static int playerMode(QuakeSession session) {
    return ByteBuffer.wrap(session.server().playerState(0))
        .order(ByteOrder.LITTLE_ENDIAN)
        .getInt(4);
  }
}
