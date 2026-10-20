package dev.bluevista.craftq3.mc.devharness.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3SpikeScene;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3SpikeScreen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Phase 0 Blaze3D spike on the real GPU: shader-side Q3 stage evaluation, the render takeover and
 * the context's ring buffers. Screenshots land in {@code build/run/<run>/screenshots/}.
 *
 * <ol>
 *   <li>From the title screen: two frames at shader times 0 s and 0.25 s (a {@link ManualClock}),
 *       which must differ in every animated cell and match the expected colour in the static and
 *       dynamic-ring cells; the static mesh is uploaded exactly once.
 *   <li>In a singleplayer world, opened through {@code /q3debug spike}: the level is neither
 *       rendered while the spike is open nor afterwards prevented from rendering.
 * </ol>
 */
public final class Q3SpikeClientTest implements FabricClientGameTest {
  private static final int TOLERANCE = 4;
  private static final double MIN_ANIMATED_DIFFERENCE = 6;

  @Override
  public void runTest(ClientGameTestContext context) {
    String backend =
        context.computeOnClient(c -> RenderSystem.getDevice().getDeviceInfo().backendName());
    String expected = System.getProperty("craftq3.devharness.expectedBackend");
    if (expected != null
        && !backend.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)))
      throw new AssertionError(
          "Expected the " + expected + " backend but Minecraft chose " + backend);
    String tag = backend.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");

    Q3RenderContext renderContext = clientMod().renderContext();
    ManualClock clock = new ManualClock(0);
    context.waitForScreen(TitleScreen.class);
    context.setScreen(() -> new Q3SpikeScreen(renderContext, clock, null));
    context.waitForScreen(Q3SpikeScreen.class);
    context.waitTicks(5);
    long frames = context.computeOnClient(c -> ((Q3SpikeScreen) c.gui.screen()).framesRendered());
    if (frames < 1)
      throw new AssertionError("The spike screen drew no frames through the takeover");

    BufferedImage t0 = read(context.takeScreenshot("craftq3-spike-" + tag + "-t0000"));
    clock.set(250);
    BufferedImage t250 = read(context.takeScreenshot("craftq3-spike-" + tag + "-t0250"));
    clock.set(1250);
    context.takeScreenshot("craftq3-spike-" + tag + "-t1250");

    for (BufferedImage image : new BufferedImage[] {t0, t250}) {
      assertCellColour(image, Q3SpikeScene.STATIC_CELL, Q3SpikeScene.STATIC_RGB, "static cell");
      assertCellColour(
          image, Q3SpikeScene.DYNAMIC_CELL, Q3SpikeScene.DYNAMIC_RGB, "dynamic-ring cell");
    }
    for (int cell : Q3SpikeScene.ANIMATED_CELLS) {
      double difference = meanDifference(t0, t250, cell);
      if (difference < MIN_ANIMATED_DIFFERENCE)
        throw new AssertionError(
            "Cell "
                + cell
                + " did not animate between t=0 and t=0.25 (mean difference "
                + difference
                + ")");
    }
    if (meanDifference(t0, t250, Q3SpikeScene.STATIC_CELL) > 1)
      throw new AssertionError("The static cell changed over time");

    // Reopening reuses the context's static mesh: still one upload.
    context.setScreen(() -> null);
    context.setScreen(() -> new Q3SpikeScreen(renderContext, clock, null));
    context.waitTicks(2);
    int uploads = context.computeOnClient(c -> renderContext.staticUploads());
    if (uploads != 1)
      throw new AssertionError("Static mesh uploaded " + uploads + " times, expected once");
    context.setScreen(() -> null);

    inWorld(context, tag);
  }

  /** Opens the spike with the real command inside a world and checks the level is suppressed. */
  private static void inWorld(ClientGameTestContext context, String tag) {
    AtomicInteger levelPasses = new AtomicInteger();
    LevelRenderEvents.END_MAIN.register(c -> levelPasses.incrementAndGet());
    try (TestSingleplayerContext world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.waitTicks(5);
      if (levelPasses.get() == 0)
        throw new AssertionError("The level never rendered before the spike opened");
      context.runOnClient(c -> c.player.connection.sendCommand("q3debug spike"));
      context.waitForScreen(Q3SpikeScreen.class);
      context.waitTicks(2);
      int before = levelPasses.get();
      context.waitTicks(10);
      BufferedImage shot = read(context.takeScreenshot("craftq3-spike-" + tag + "-world"));
      if (levelPasses.get() != before)
        throw new AssertionError(
            "The level rendered " + (levelPasses.get() - before) + " times under the spike");
      assertCellColour(
          shot, Q3SpikeScene.STATIC_CELL, Q3SpikeScene.STATIC_RGB, "static cell in world");
      context.setScreen(() -> null);
      context.waitTicks(5);
      if (levelPasses.get() == before)
        throw new AssertionError("The level did not resume after the spike closed");
      context.takeScreenshot("craftq3-spike-" + tag + "-world-closed");
    }
  }

  private static CraftQ3ClientMod clientMod() {
    // The loader's entrypoint instances are the ones that ran; CraftQ3Mod must have run too.
    var loader = FabricLoader.getInstance();
    boolean common =
        loader.getEntrypoints("main", net.fabricmc.api.ModInitializer.class).stream()
            .anyMatch(e -> e instanceof CraftQ3Mod mod && mod.initialized());
    if (!common) throw new AssertionError("CraftQ3Mod did not initialise on the client");
    return loader.getEntrypoints("client", ClientModInitializer.class).stream()
        .filter(CraftQ3ClientMod.class::isInstance)
        .map(CraftQ3ClientMod.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("CraftQ3ClientMod entrypoint not found"));
  }

  private static BufferedImage read(Path path) {
    try {
      BufferedImage image = ImageIO.read(path.toFile());
      if (image == null) throw new AssertionError("Unreadable screenshot " + path);
      return image;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void assertCellColour(BufferedImage image, int cell, int[] rgb, String what) {
    int[] r = Q3SpikeScene.cellCentre(cell, image.getWidth(), image.getHeight(), 8);
    for (int y = r[1]; y < r[3]; y += 3)
      for (int x = r[0]; x < r[2]; x += 3) {
        int p = image.getRGB(x, y);
        int[] got = {p >> 16 & 0xff, p >> 8 & 0xff, p & 0xff};
        for (int i = 0; i < 3; i++)
          if (Math.abs(got[i] - rgb[i]) > TOLERANCE)
            throw new AssertionError(
                String.format(
                    Locale.ROOT,
                    "%s pixel (%d,%d) is #%02x%02x%02x, expected #%02x%02x%02x",
                    what,
                    x,
                    y,
                    got[0],
                    got[1],
                    got[2],
                    rgb[0],
                    rgb[1],
                    rgb[2]));
      }
  }

  private static double meanDifference(BufferedImage a, BufferedImage b, int cell) {
    int[] r = Q3SpikeScene.cellCentre(cell, a.getWidth(), a.getHeight(), 4);
    long sum = 0, count = 0;
    for (int y = r[1]; y < r[3]; y++)
      for (int x = r[0]; x < r[2]; x++) {
        int p = a.getRGB(x, y), q = b.getRGB(x, y);
        for (int shift = 0; shift <= 16; shift += 8)
          sum += Math.abs((p >> shift & 0xff) - (q >> shift & 0xff));
        count += 3;
      }
    return (double) sum / count;
  }
}
