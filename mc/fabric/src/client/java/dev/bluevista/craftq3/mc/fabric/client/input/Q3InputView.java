package dev.bluevista.craftq3.mc.fabric.client.input;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * A screen whose input belongs to Quake III. While one is open, {@code MouseHandlerMixin} hands the
 * frame's relative mouse motion to its {@link Q3InputCapture} and Minecraft never sees it, so the
 * camera cannot turn. {@link Q3InputScreen} is the ready-made implementation.
 */
public interface Q3InputView {
  Logger LOGGER = LogUtils.getLogger();

  /** The capture receiving this screen's input. */
  Q3InputCapture inputCapture();

  /** The open view's capture, if the current screen is one. */
  static Q3InputCapture current(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof Q3InputView view ? view.inputCapture() : null;
  }

  /**
   * Runs an input delivery, containing failures: a throwing sink closes the capture and returns to
   * Minecraft instead of propagating into Minecraft's input handling (REWRITE.md error model).
   */
  static void deliver(Minecraft minecraft, Q3InputCapture capture, Runnable delivery) {
    try {
      delivery.run();
    } catch (RuntimeException e) {
      LOGGER.error("CraftQ3 input delivery failed; returning to Minecraft", e);
      try {
        capture.close();
      } catch (RuntimeException suppressed) {
        e.addSuppressed(suppressed);
        LOGGER.error("CraftQ3 input capture did not close cleanly", suppressed);
      }
      minecraft.gui.setScreen(null);
    }
  }
}
