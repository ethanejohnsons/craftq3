package dev.bluevista.craftq3.mc.fabric.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/**
 * {@link InputHost} on Minecraft's window.
 *
 * <p>The cursor is grabbed with the same GLFW cursor mode Minecraft uses for gameplay, but without
 * {@code MouseHandler.grabMouse}: that would close the screen and set Minecraft's own grab flag,
 * which is what lets {@code MouseHandler} turn the player. Raw mouse motion is a window input mode
 * that GLFW applies only while the cursor is disabled; it is re-applied from Minecraft's "Raw
 * Input" option on every grab, so the capture always honours it.
 */
public final class MinecraftInputHost implements InputHost {
  private final Minecraft minecraft;

  public MinecraftInputHost(Minecraft minecraft) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  @Override
  public boolean inputFocused() {
    Window window = minecraft.getWindow();
    return minecraft.isWindowActive()
        && minecraft.gui.overlay() == null
        && window.getWidth() > 0
        && window.getHeight() > 0;
  }

  @Override
  public void grabCursor() {
    Window window = minecraft.getWindow();
    window.updateRawMouseInput(minecraft.options.rawMouseInput().get());
    setCursor(window, InputConstants.CURSOR_DISABLED);
  }

  @Override
  public void releaseCursor() {
    setCursor(minecraft.getWindow(), InputConstants.CURSOR_NORMAL);
  }

  private void setCursor(Window window, int mode) {
    InputConstants.grabOrReleaseMouse(
        window, mode, window.getScreenWidth() / 2.0, window.getScreenHeight() / 2.0);
    // The warp to the centre is not motion.
    minecraft.mouseHandler.setIgnoreFirstMove();
  }
}
