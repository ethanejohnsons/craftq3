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
import org.lwjgl.glfw.GLFW;
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
 * for the rest of Minecraft's frame, which presents as usual. Minecraft acquires the image before
 * {@code GameRenderer.update}, so this works from the bridge's frame (at {@code update}'s head) and
 * from the full-screen pass alike. Nothing happens while the window is minimized or the previous
 * frame reached the screen less than {@link #INTERVAL_NANOS} ago, so a burst of updates costs at
 * most one vsync interval each (FIFO pacing happens in the acquire).
 *
 * <p>On Vulkan the surface can be broken: a swapchain out of date (the window changed size), or one
 * Minecraft could not create because GLFW reported a size the surface does not allow at that
 * moment. Minecraft then holds no image for its frame. Its own configure failure marks the surface
 * invalid until the next framebuffer resize callback, which never comes if the window settled at
 * another size meanwhile (Fabric's client game tests cancel the callback altogether), so nothing
 * reaches the screen again. A broken surface is brought back here the way {@code renderFrame} does
 * it: configured to the window's framebuffer size, then acquired; Minecraft is asked to reconfigure
 * on its next frame too. Blaze3D reports an out-of-date present only through the next acquire (its
 * kept exception, wrapped as the cause), so that acquire is retried once after reconfiguring, and
 * the frame is not counted as shown. The surface is always left as Minecraft had it (holding an
 * image or not): {@code renderFrame} does nothing at all while an image is held, so one left behind
 * outside its frame would freeze the game. A failed recovery is retried after {@link #RETRY_NANOS}.
 */
public final class LoadingFrames {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Loading frames inside a VM call reach the screen at most this often. */
  public static final long INTERVAL_NANOS = 15_000_000L;

  /** A surface that could not be brought back is tried again after this long. */
  public static final long RETRY_NANOS = 100_000_000L;

  private final Minecraft minecraft;
  private final MinecraftSessionHost host;
  private final Q3FramePresenter presenter;
  private final boolean worldViews;
  private long lastPresentNanos;
  private long lastFailureNanos;
  private long lastReconfigureNanos;
  private long longestGapNanos;
  private long presents;
  private long recoveries;

  /** This VM call presents without an image of Minecraft's (its surface was broken). */
  private boolean unheldPresents;

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
   * A Minecraft frame is under way: call once per frame, before any session work. It counts as
   * reaching the screen only when it holds a surface image (Minecraft presents only then); a frame
   * without one on a broken surface (out of date, or a configure that failed) while the window is
   * not minimized asks Minecraft to reconfigure on its next frame (at most every {@link
   * #RETRY_NANOS}), so a configure that failed once does not leave the screen frozen.
   */
  public void noteFrame() {
    long now = System.nanoTime();
    GpuSurface surface = minecraft.windowSurface();
    if (surface.isAcquired()) note(now);
    else if (surface.isSuboptimal()
        && !minecraft.getWindow().isMinimized()
        && now - lastReconfigureNanos >= RETRY_NANOS) {
      if (lastReconfigureNanos == 0)
        LOGGER.info("CraftQ3: Minecraft's frame holds no surface image; asking it to reconfigure");
      lastReconfigureNanos = now;
      minecraft.invalidateSurfaceConfiguration();
    }
  }

  /** Runs {@code sessionFrame}, presenting the loading frames it hands over into {@code target}. */
  public void run(Supplier<RenderTarget> target, Runnable sessionFrame) {
    unheldPresents = false;
    host.immediatePresentation(() -> present(target.get()));
    try {
      sessionFrame.run();
    } finally {
      host.immediatePresentation(null);
    }
  }

  private void present(RenderTarget target) {
    long now = System.nanoTime();
    if (now - lastPresentNanos < INTERVAL_NANOS || now - lastFailureNanos < RETRY_NANOS) return;
    if (!worldViews && hasWorldView(presenter.latestFrame())) return;
    GpuTextureView color = target.getColorTextureView();
    if (color == null) return;
    GpuSurface surface = minecraft.windowSurface();
    boolean held = surface.isAcquired();
    if (!held) {
      // Only a broken surface is brought back (and then for the rest of the VM call): a frame
      // rendered outside Minecraft's renderFrame (a game test screenshot) holds no image either.
      if (!unheldPresents && !surface.isSuboptimal()) return;
      if (!recover(surface)) return;
      if (!unheldPresents) {
        unheldPresents = true;
        // Minecraft could not acquire for this frame and may hold its surface invalid until it
        // is reconfigured: let its next frame do so.
        minecraft.invalidateSurfaceConfiguration();
      }
    }
    presenter.renderLoading(target, host.windowWidth(), host.windowHeight());
    CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
    surface.blitFromTexture(encoder, color);
    encoder.submit();
    surface.present();
    // Leave the surface as Minecraft had it: acquired for the rest of its frame, or not at all
    // (renderFrame does nothing while an image is held, so one left behind would freeze it).
    boolean shown;
    if (!held) shown = !surface.isSuboptimal();
    else {
      shown = true;
      try {
        surface.acquireNextTexture();
      } catch (SurfaceException e) {
        // A wrapped cause is the present's own failure (out of date), reported by this acquire.
        shown = !(e.getCause() instanceof SurfaceException);
        recover(surface);
      }
    }
    if (shown) {
      presents++;
      note(System.nanoTime());
    }
  }

  /**
   * Configures the surface to the window's framebuffer size when it is out of date (or was never
   * configured) and acquires the next image; true when an image is held afterwards.
   */
  private boolean recover(GpuSurface surface) {
    if (minecraft.getWindow().isMinimized()) return failed(null);
    try {
      if (surface.isSuboptimal() || surface.currentConfiguration().isEmpty()) configure(surface);
      try {
        surface.acquireNextTexture();
      } catch (SurfaceException e) {
        // An out-of-date present is reported once, by the first acquire after it.
        if (!(e.getCause() instanceof SurfaceException)) throw e;
        if (surface.isSuboptimal()) configure(surface);
        surface.acquireNextTexture();
      }
    } catch (SurfaceException e) {
      return failed(e);
    }
    if (recoveries++ == 0)
      LOGGER.info(
          "CraftQ3: window surface brought back for loading frames ({})",
          surface.currentConfiguration().orElse(null));
    return true;
  }

  private void configure(GpuSurface surface) throws SurfaceException {
    int[] width = new int[1];
    int[] height = new int[1];
    GLFW.glfwGetFramebufferSize(minecraft.getWindow().handle(), width, height);
    if (width[0] == 0 || height[0] == 0) throw new SurfaceException("the window has no area");
    GpuSurface.PresentMode mode =
        surface
            .currentConfiguration()
            .map(GpuSurface.Configuration::presentMode)
            .orElseGet(
                () ->
                    GpuSurface.PresentMode.getSupportedVsyncMode(
                        surface.supportedPresentModes(), minecraft.options.enableVsync().get()));
    surface.configure(new GpuSurface.Configuration(width[0], height[0], mode));
  }

  private boolean failed(SurfaceException e) {
    lastFailureNanos = System.nanoTime();
    if (e != null)
      LOGGER.debug("CraftQ3: no surface image for a loading frame: {}", e.getMessage());
    return false;
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
   * The longest wall time between two frames reaching the screen (Minecraft frames holding a
   * surface image and loading frames presented inside a VM call) since the last {@link #resetGaps}.
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
