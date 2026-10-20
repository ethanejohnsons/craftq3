package dev.bluevista.craftq3.mc.fabric.client.session;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Shows the loading frames a session draws inside a long VM call ({@code trap_UpdateScreen} while
 * the cgame registers its map, models and sounds, handed to {@code RenderSink.submitImmediate})
 * before the call returns, the way the original swapped buffers there, and measures how long the
 * screen goes without a new frame. Shared by the full-screen {@code Q3Screen} and the bridge's
 * {@code BridgeScreen}; render thread only.
 *
 * <p>A loading frame is drawn into Minecraft's main target and presented with Minecraft's own
 * sequence (blit to the window surface, submit, present); the next surface image is then acquired
 * for the rest of Minecraft's frame, which presents as usual. Nothing happens when no surface image
 * is held (minimized, invalid surface) or the previous frame reached the screen less than {@link
 * #INTERVAL_NANOS} ago, so a burst of updates costs at most one vsync interval each. If the next
 * image cannot be acquired, Minecraft's frame skips its own present (its {@code isAcquired} checks)
 * and acquires again on the next frame. Minecraft acquires the image before {@code
 * GameRenderer.update}, so this works from the bridge's frame (at {@code update}'s head) and from
 * the full-screen pass alike.
 */
public final class LoadingFrames {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Loading frames inside a VM call reach the screen at most this often. */
  public static final long INTERVAL_NANOS = 15_000_000L;

  private final Minecraft minecraft;
  private final MinecraftSessionHost host;
  private final Q3FramePresenter presenter;
  private final boolean worldViews;
  private long lastPresentNanos;
  private long longestGapNanos;
  private long presents;

  /**
   * @param worldViews whether a frame with a world view may be presented this way; the bridge says
   *     no (its world views are drawn inside Minecraft's level, never full screen), so only loading
   *     screens are
   */
  public LoadingFrames(
      Minecraft minecraft,
      MinecraftSessionHost host,
      Q3FramePresenter presenter,
      boolean worldViews) {
    this.minecraft = Objects.requireNonNull(minecraft);
    this.host = Objects.requireNonNull(host);
    this.presenter = Objects.requireNonNull(presenter);
    this.worldViews = worldViews;
  }

  /**
   * A Minecraft frame is on its way to the screen: call once per frame, before any session work.
   */
  public void noteFrame() {
    note(System.nanoTime());
  }

  /** Runs {@code sessionFrame}, presenting the loading frames it hands over into {@code target}. */
  public void run(Supplier<RenderTarget> target, Runnable sessionFrame) {
    host.immediatePresentation(() -> present(target.get()));
    try {
      sessionFrame.run();
    } finally {
      host.immediatePresentation(null);
    }
  }

  private void present(RenderTarget target) {
    long now = System.nanoTime();
    if (now - lastPresentNanos < INTERVAL_NANOS) return;
    if (!worldViews && hasWorldView(presenter.latestFrame())) return;
    GpuSurface surface = minecraft.windowSurface();
    GpuTextureView color = target.getColorTextureView();
    if (!surface.isAcquired() || color == null) return;
    presenter.renderLoading(target, host.windowWidth(), host.windowHeight());
    CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
    surface.blitFromTexture(encoder, color);
    encoder.submit();
    surface.present();
    presents++;
    note(System.nanoTime());
    try {
      surface.acquireNextTexture();
    } catch (SurfaceException e) {
      LOGGER.warn("CraftQ3: no surface image after a loading frame: {}", e.getMessage());
    }
  }

  private static boolean hasWorldView(CgameFrame frame) {
    if (frame == null) return false;
    for (CgameFrame.Command command : frame.commands())
      if (command instanceof CgameFrame.View view && view.refdef().worldModel()) return true;
    return false;
  }

  private void note(long now) {
    if (lastPresentNanos != 0) longestGapNanos = Math.max(longestGapNanos, now - lastPresentNanos);
    lastPresentNanos = now;
  }

  /**
   * The longest wall time between two frames reaching the screen (Minecraft frames and loading
   * frames presented inside a VM call) since the last {@link #resetGaps}.
   */
  public long longestGapNanos() {
    return longestGapNanos;
  }

  /** Loading frames presented inside VM calls so far. */
  public long presents() {
    return presents;
  }

  /** Starts a new {@link #longestGapNanos} measurement. */
  public void resetGaps() {
    longestGapNanos = 0;
    lastPresentNanos = 0;
  }
}
