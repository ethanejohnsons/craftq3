package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * A screen that owns the frame while it is open: Minecraft's level is neither extracted nor
 * rendered (which also hides the HUD), and {@link #renderFullscreen} draws into the main target
 * after the (skipped) level and before the GUI, so the screen's own widgets and text still draw on
 * top. Driven by {@code GameRendererMixin}.
 */
public interface Q3FullscreenView {
  Logger LOGGER = LogUtils.getLogger();

  /** Draws this frame's full-screen pass into {@code target}. Called on the render thread. */
  void renderFullscreen(RenderTarget target);

  /** Whether Minecraft's level extraction and rendering are skipped while this view is open. */
  default boolean replacesLevel() {
    return true;
  }

  /** The open full-screen view, if the current screen is one. */
  static Q3FullscreenView current(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof Q3FullscreenView view ? view : null;
  }

  /** True while an open view suppresses Minecraft's level. */
  static boolean replacesLevel(Minecraft minecraft) {
    Q3FullscreenView view = current(minecraft);
    return view != null && view.replacesLevel();
  }

  /**
   * Runs the open view's pass, if any. A failing pass closes the screen and returns to Minecraft
   * instead of propagating into the frame (REWRITE.md error model).
   */
  static void renderBeforeGui(Minecraft minecraft) {
    Q3FullscreenView view = current(minecraft);
    if (view == null) return;
    try {
      view.renderFullscreen(minecraft.gameRenderer.mainRenderTarget());
    } catch (RuntimeException e) {
      LOGGER.error("CraftQ3 full-screen pass failed; returning to Minecraft", e);
      minecraft.gui.setScreen(null);
    }
  }
}
