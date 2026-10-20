package dev.bluevista.craftq3.mc.fabric.client.debug;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3FullscreenView;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderBackend;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * {@code /q3debug map}: draws a loaded map through {@link Q3RenderBackend} from a fixed camera,
 * full screen through the render takeover, and keeps the backend's CPU time of recent frames.
 */
public final class Q3MapViewScreen extends Screen implements Q3FullscreenView {
  /** What one frame shows; the view is built per frame for the target's size. */
  public interface Scene {
    ViewDefinition view(int width, int height, int timeMillis);

    default List<CgameFrame.RefEntity> entities() {
      return List.of();
    }

    default List<CgameFrame.Poly> polygons() {
      return List.of();
    }

    default List<DynamicLight> lights() {
      return List.of();
    }

    default List<CgameFrame.Quad> overlay(int width, int height) {
      return List.of();
    }

    /** A whole cgame frame to render instead of {@link #view}; null for the single view. */
    default CgameFrame frame(int width, int height, int timeMillis) {
      return null;
    }
  }

  private static final int HISTORY = 1024;

  private final Q3RenderBackend backend;
  private final Clock clock;
  private final Screen parent;
  private final AutoCloseable owned;
  private final long startMillis;
  private volatile Scene scene;
  private final long[] cpuNanos = new long[HISTORY];
  private long frames;
  private String lastStats = "";
  private volatile boolean showStats = true;

  public Q3MapViewScreen(
      Q3RenderBackend backend, Scene scene, Clock clock, Screen parent, AutoCloseable owned) {
    super(Component.literal("CraftQ3 map view"));
    this.backend = backend;
    this.scene = scene;
    this.clock = clock;
    this.parent = parent;
    this.owned = owned;
    this.startMillis = clock.millis();
  }

  public void scene(Scene scene) {
    this.scene = scene;
  }

  /** Whether the backend's frame statistics are printed at the bottom of the screen. */
  public void showStats(boolean show) {
    showStats = show;
  }

  public long framesRendered() {
    return frames;
  }

  public Q3RenderBackend backend() {
    return backend;
  }

  /** CPU nanoseconds of the last {@code count} frames (oldest first). */
  public long[] recentCpuNanos(int count) {
    int n = (int) Math.min(Math.min(count, HISTORY), frames);
    long[] out = new long[n];
    for (int i = 0; i < n; i++) out[i] = cpuNanos[(int) ((frames - n + i) % HISTORY)];
    return out;
  }

  @Override
  public void renderFullscreen(RenderTarget target) {
    Scene current = scene;
    int time = (int) (clock.millis() - startMillis);
    // The cgame's work (building the frame) is not the renderer's: measure begin..end only.
    CgameFrame frame = current.frame(target.width, target.height, time);
    ViewDefinition view = frame == null ? current.view(target.width, target.height, time) : null;
    List<CgameFrame.Quad> overlay =
        frame == null ? current.overlay(target.width, target.height) : List.of();
    backend.beginFrame(target, target.width, target.height, time);
    if (frame != null) {
      backend.render(frame);
    } else {
      backend.render(view, current.entities(), current.polygons(), current.lights());
      if (!overlay.isEmpty()) backend.drawOverlay(overlay);
    }
    backend.endFrame();
    cpuNanos[(int) (frames % HISTORY)] = backend.stats().cpuNanos;
    frames++;
    if (frames % 60 == 0) lastStats = backend.stats().toString();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    if (showStats && !lastStats.isEmpty())
      graphics.text(font, lastStats, 4, height - 12, 0xffffffff);
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    if (owned != null) {
      try {
        owned.close();
      } catch (Exception e) {
        LOGGER.warn("CraftQ3: closing the map view failed", e);
      }
    }
    minecraft.gui.setScreen(parent);
  }

  /** Percentile {@code p} (0..100) of {@code values} in milliseconds. */
  public static double percentileMillis(long[] values, double p) {
    if (values.length == 0) return 0;
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    int index = (int) Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1);
    return sorted[Math.max(0, index)] / 1e6;
  }
}
