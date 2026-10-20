package dev.bluevista.craftq3.mc.devharness.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.engine.client.pipeline.ClientSnapshot;
import dev.bluevista.craftq3.engine.session.EngineSession;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3MapViewScreen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderBackend;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3TextureCache;
import dev.bluevista.craftq3.mc.fabric.client.screen.Q3MissingInstallationScreen;
import dev.bluevista.craftq3.mc.fabric.client.screen.Q3Screen;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3FramePresenter;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure Quake III in Minecraft with the maintainer's installation ({@code CRAFTQ3_Q3ROOT}, passed to
 * the mod as {@code -Dcraftq3.installation}); without one, the title screen button must open the
 * missing-installation screen instead.
 *
 * <ol>
 *   <li><b>Menu</b>: title screen → "CraftQ3" → the original main menu renders (not flat); Enter
 *       opens Single Player and Escape returns, Down + Enter opens Multiplayer and Escape returns
 *       (each screen differs from the main menu, and the menu comes back).
 *   <li><b>Game</b>: {@code map q3dm17} typed into the Quake console → the in-game view renders; W
 *       held for two seconds moves the player; MOUSE1 fires and the ammo count drops; {@code addbot
 *       sarge 1} puts Sarge in the player configstrings within ten seconds. The frame time of the
 *       match (session frame plus render submission) is reported.
 *   <li><b>Demo</b>: {@code record t}, three seconds, {@code stoprecord}, {@code demo t} plays.
 *   <li><b>Escape hatch</b>: Shift+Escape returns to the title screen; the session's audio device,
 *       textures and map objects are released, its server thread has ended and {@code q3config.cfg}
 *       was written.
 * </ol>
 *
 * The client game test tasks run this on OpenGL and Vulkan. Screenshots and {@code
 * craftq3-pure-report-<backend>.txt} go to the run directory.
 */
public final class Q3PureModeClientTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3PureModeClientTest.class);
  private static final int WIDTH = 1920, HEIGHT = 1080;
  private static final double MIN_DEVIATION = 12;
  private static final int SECOND_TICKS = 20;

  /** {@code CS_PLAYERS} in baseq3's {@code bg_public.h}. */
  private static final int CS_PLAYERS = 544;

  private static final int MAX_CLIENTS = 64;

  private final List<String> failures = new ArrayList<>();
  private final StringBuilder report = new StringBuilder();

  @Override
  public void runTest(ClientGameTestContext context) {
    context.waitForScreen(TitleScreen.class);
    context.getInput().resizeWindow(WIDTH, HEIGHT);
    context.waitTicks(3);
    String backendName =
        context.computeOnClient(c -> RenderSystem.getDevice().getDeviceInfo().backendName());
    String tag = backendName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    report.append("CraftQ3 pure Quake III on ").append(backendName).append('\n');

    String root = System.getenv("CRAFTQ3_Q3ROOT");
    if (root == null || root.isBlank() || !Files.isDirectory(Path.of(root))) {
      LOGGER.warn("CRAFTQ3_Q3ROOT is not set; checking only the missing-installation screen");
      missingInstallation(context);
      finish(context, tag);
      return;
    }
    Baseline baseline = context.computeOnClient(Q3PureModeClientTest::baseline);
    try {
      menu(context, tag);
      game(context, tag);
      demo(context, tag);
      escapeHatch(context, tag, baseline);
    } catch (RuntimeException | AssertionError e) {
      context.takeScreenshot("craftq3-pure-" + tag + "-failure");
      failures.add("aborted: " + e);
      LOGGER.error("Pure-mode test aborted", e);
      context.runOnClient(c -> c.gui.setScreen(new TitleScreen()));
    }
    finish(context, tag);
  }

  // ------------------------------------------------------------------ steps

  private void missingInstallation(ClientGameTestContext context) {
    CraftQ3Config config =
        context
            .computeOnClient(c -> dev.bluevista.craftq3.mc.fabric.CraftQ3Mod.instance())
            .installation()
            .config();
    if (config.installationPath().isPresent()) return;
    context.clickScreenButton(CraftQ3ClientMod.TITLE_BUTTON);
    context.waitForScreen(Q3MissingInstallationScreen.class);
    context.takeScreenshot("craftq3-pure-missing-installation");
    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
    context.waitForScreen(TitleScreen.class);
  }

  private void menu(ClientGameTestContext context, String tag) {
    context.clickScreenButton(CraftQ3ClientMod.TITLE_BUTTON);
    context.waitForScreen(Q3Screen.class);
    waitFor(
        context,
        "the main menu draws",
        c -> {
          Q3Screen screen = Q3Screen.current(c);
          return screen != null
              && screen.session() != null
              && screen.session().state() == SessionState.MENU
              && screen.presenter().framesDrawn() > 30;
        },
        60 * SECOND_TICKS);
    long startMillis = context.computeOnClient(c -> Q3Screen.current(c).startNanos() / 1_000_000);
    report.append("session start (mount, configs, ui boot): ").append(startMillis).append(" ms\n");
    check(
        !context.computeOnClient(c -> Q3Screen.current(c).session().uiBroken()), "the ui VM runs");
    BufferedImage main = screenshot(context, "craftq3-pure-" + tag + "-menu");
    double deviation = deviation(main);
    report.append(String.format(Locale.ROOT, "main menu deviation %.1f%n", deviation));
    check(deviation >= MIN_DEVIATION, "main menu is flat (deviation " + deviation + ")");

    TestInput input = context.getInput();
    // q3_ui opens with Single Player under the cursor.
    input.pressKey(GLFW.GLFW_KEY_ENTER);
    context.waitTicks(SECOND_TICKS);
    BufferedImage single = screenshot(context, "craftq3-pure-" + tag + "-singleplayer");
    double singleDiff = difference(main, single);
    check(singleDiff > 8, "Single Player did not open (difference " + singleDiff + ")");
    input.pressKey(GLFW.GLFW_KEY_ESCAPE);
    context.waitTicks(SECOND_TICKS);
    BufferedImage back = screenshot(context, "craftq3-pure-" + tag + "-menu-back1");
    check(difference(main, back) < singleDiff / 2, "Escape did not return from Single Player");

    input.pressKey(GLFW.GLFW_KEY_DOWN);
    context.waitTicks(2);
    input.pressKey(GLFW.GLFW_KEY_ENTER);
    context.waitTicks(SECOND_TICKS);
    BufferedImage multi = screenshot(context, "craftq3-pure-" + tag + "-multiplayer");
    double multiDiff = difference(main, multi);
    check(multiDiff > 8, "Multiplayer did not open (difference " + multiDiff + ")");
    check(difference(single, multi) > 4, "Multiplayer looks like Single Player");
    input.pressKey(GLFW.GLFW_KEY_ESCAPE);
    context.waitTicks(SECOND_TICKS);
    screenshot(context, "craftq3-pure-" + tag + "-menu-back2");
    report.append(
        String.format(
            Locale.ROOT,
            "menu navigation: single player diff %.1f, multiplayer diff %.1f%n",
            singleDiff,
            multiDiff));
  }

  private void game(ClientGameTestContext context, String tag) {
    TestInput input = context.getInput();
    // The original console: ~ opens it, the line runs on Enter, ~ closes it.
    input.pressKey(GLFW.GLFW_KEY_GRAVE_ACCENT);
    context.waitTicks(2);
    input.typeChars("map q3dm17");
    input.pressKey(GLFW.GLFW_KEY_ENTER);
    context.waitTicks(2);
    long loadStart = System.nanoTime();
    waitFor(
        context,
        "q3dm17 is in game",
        c -> {
          Q3Screen screen = Q3Screen.current(c);
          if (screen == null || screen.session() == null) return false;
          Q3FramePresenter presenter = screen.presenter();
          return screen.session().state().inGame()
              && screen.session().state() != SessionState.DEMO
              && !presenter.loading()
              && presenter.mapName().equals("maps/q3dm17.bsp")
              && snapshot(screen.session()).isPresent();
        },
        120 * SECOND_TICKS);
    report.append(
        String.format(
            Locale.ROOT,
            "map q3dm17 to first snapshot: %d ms%n",
            (System.nanoTime() - loadStart) / 1_000_000));
    // Close the console if the map change left it open.
    if (context.computeOnClient(c -> (catchers(c) & 1) != 0))
      input.pressKey(GLFW.GLFW_KEY_GRAVE_ACCENT);
    context.waitTicks(SECOND_TICKS);
    BufferedImage view = screenshot(context, "craftq3-pure-" + tag + "-q3dm17");
    double deviation = deviation(view);
    report.append(String.format(Locale.ROOT, "in-game deviation %.1f%n", deviation));
    check(deviation >= MIN_DEVIATION, "in-game view is flat (deviation " + deviation + ")");

    // Move forward for two seconds.
    float[] before = origin(context);
    input.holdKey(GLFW.GLFW_KEY_W);
    waitMillis(context, 2000);
    input.releaseKey(GLFW.GLFW_KEY_W);
    context.waitTicks(5);
    float[] after = origin(context);
    double moved = Math.hypot(after[0] - before[0], after[1] - before[1]);
    report.append(String.format(Locale.ROOT, "W for 2 s moved %.1f units%n", moved));
    check(moved > 64, "W did not move the player (" + moved + " units)");

    // Fire.
    int weapon = playerInt(context, PlayerStateLayout.WEAPON);
    int ammoBefore = playerInt(context, PlayerStateLayout.AMMO + 4 * weapon);
    input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    waitMillis(context, 600);
    input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    context.waitTicks(5);
    int ammoAfter = playerInt(context, PlayerStateLayout.AMMO + 4 * weapon);
    report.append(
        String.format(
            Locale.ROOT, "fire: weapon %d ammo %d -> %d%n", weapon, ammoBefore, ammoAfter));
    check(
        ammoAfter < ammoBefore,
        "firing did not use ammo (" + ammoBefore + " -> " + ammoAfter + ")");
    screenshot(context, "craftq3-pure-" + tag + "-fire");

    // A bot.
    command(context, "addbot sarge 1");
    long botStart = System.nanoTime();
    boolean bot = false;
    while (!bot && System.nanoTime() - botStart < 10_000_000_000L) {
      context.waitTicks(5);
      bot = context.computeOnClient(c -> botPresent(Q3Screen.current(c).session(), "sarge"));
    }
    check(bot, "addbot sarge 1: no Sarge in the player configstrings within 10 s");
    report.append(
        String.format(
            Locale.ROOT, "bot joined after %d ms%n", (System.nanoTime() - botStart) / 1_000_000));

    // Frame time with the bot in play.
    waitMillis(context, 1500);
    long framesBefore = context.computeOnClient(c -> Q3Screen.current(c).sessionFrames());
    context.waitFor(c -> Q3Screen.current(c).sessionFrames() >= framesBefore + 600, 60 * 60);
    long[] work = context.computeOnClient(c -> Q3Screen.current(c).recentWorkNanos(600));
    String stats =
        context.computeOnClient(
            c -> {
              Q3RenderBackend backend = Q3Screen.current(c).presenter().backendOrNull();
              return backend == null ? "" : backend.stats().toString();
            });
    report.append(
        String.format(
            Locale.ROOT,
            "match frame (session + render submission) p50 %.2f ms p99 %.2f ms max %.2f ms over"
                + " %d frames; %s%n",
            Q3MapViewScreen.percentileMillis(work, 50),
            Q3MapViewScreen.percentileMillis(work, 99),
            Q3MapViewScreen.percentileMillis(work, 100),
            work.length,
            stats));
    screenshot(context, "craftq3-pure-" + tag + "-bot");
    // The scoreboard while held.
    input.holdKey(GLFW.GLFW_KEY_TAB);
    context.waitTicks(10);
    screenshot(context, "craftq3-pure-" + tag + "-scores");
    input.releaseKey(GLFW.GLFW_KEY_TAB);
  }

  private void demo(ClientGameTestContext context, String tag) {
    command(context, "record t");
    waitMillis(context, 3000);
    command(context, "stoprecord");
    context.waitTicks(5);
    Path demo =
        context.computeOnClient(
            c -> Q3Screen.current(c).host().gameHome().resolve("demos").resolve("t.dm_68"));
    check(Files.isRegularFile(demo), "record t wrote no " + demo);
    command(context, "demo t");
    boolean playing;
    try {
      waitFor(
          context,
          "demo t plays",
          c -> {
            Q3Screen screen = Q3Screen.current(c);
            return screen != null
                && screen.session() != null
                && screen.session().state() == SessionState.DEMO
                && !screen.presenter().loading()
                && snapshot(screen.session()).isPresent();
          },
          60 * SECOND_TICKS);
      playing = true;
    } catch (AssertionError e) {
      playing = false;
      failures.add(e.getMessage());
    }
    if (playing) {
      waitMillis(context, 1000);
      BufferedImage frame = screenshot(context, "craftq3-pure-" + tag + "-demo");
      check(deviation(frame) >= MIN_DEVIATION, "demo playback is flat");
      report
          .append("demo t: plays (")
          .append(Files.exists(demo) ? size(demo) : 0)
          .append(" bytes)\n");
    }
  }

  private void escapeHatch(ClientGameTestContext context, String tag, Baseline baseline) {
    Q3Screen screen = context.computeOnClient(Q3Screen::current);
    EngineSession session = context.computeOnClient(c -> screen.session());
    Path config = context.computeOnClient(c -> screen.host().gameHome().resolve("q3config.cfg"));
    long configBefore = Files.exists(config) ? modified(config) : -1;
    Thread server = context.computeOnClient(c -> session.serverThread().orElse(null));
    context.getInput().holdShift();
    context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
    context.getInput().releaseShift();
    context.waitForScreen(TitleScreen.class);
    context.waitTicks(5);
    screenshot(context, "craftq3-pure-" + tag + "-title-after");
    Baseline now = context.computeOnClient(Q3PureModeClientTest::baseline);
    report.append("resources before ").append(baseline).append(", after ").append(now).append('\n');
    check(
        now.textures() <= baseline.textures(), "textures not released: " + baseline + " -> " + now);
    check(now.audioDevices() == baseline.audioDevices(), "audio device still open: " + now);
    check(!now.vanillaMuted(), "Minecraft audio still muted");
    check(context.computeOnClient(c -> screen.presenter().backendOrNull() == null), "backend open");
    if (server != null) {
      try {
        server.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      check(!server.isAlive(), "the server thread is still running");
    }
    check(
        Files.exists(config) && modified(config) >= configBefore,
        "q3config.cfg was not written to " + config);
  }

  // ------------------------------------------------------------------ helpers

  private record Baseline(int textures, int audioDevices, boolean vanillaMuted) {}

  /**
   * Textures the session owns: the shared cache minus its pinned engine images ({@code
   * $whiteimage}, {@code $missing}, {@code $black}), which live as long as the client.
   */
  private static Baseline baseline(Minecraft client) {
    CraftQ3ClientMod mod = clientMod();
    var textures = mod.renderContext().textures();
    int pinned = 0;
    for (String name : List.of(Q3TextureCache.WHITE, Q3TextureCache.MISSING, Q3TextureCache.BLACK))
      if (textures.find(name) != null) pinned++;
    return new Baseline(
        textures.size() - pinned, mod.audio().openDevices(), mod.audio().vanillaMuted());
  }

  private static void command(ClientGameTestContext context, String line) {
    context.runOnClient(c -> Q3Screen.current(c).session().command(line + "\n"));
    context.waitTicks(2);
  }

  private static int catchers(Minecraft client) {
    return Q3Screen.current(client).session().services().input().catchers();
  }

  private static Optional<ClientSnapshot> snapshot(EngineSession session) {
    return session.pipeline().flatMap(p -> p.latestSnapshot());
  }

  private static byte[] player(ClientGameTestContext context) {
    return context.computeOnClient(
        c ->
            snapshot(Q3Screen.current(c).session())
                .orElseThrow(() -> new AssertionError("no snapshot"))
                .snapshot()
                .player());
  }

  private static int word(byte[] bytes, int offset) {
    return (bytes[offset] & 255)
        | (bytes[offset + 1] & 255) << 8
        | (bytes[offset + 2] & 255) << 16
        | bytes[offset + 3] << 24;
  }

  private static int playerInt(ClientGameTestContext context, int offset) {
    return word(player(context), offset);
  }

  private static float[] origin(ClientGameTestContext context) {
    byte[] ps = player(context);
    return new float[] {
      Float.intBitsToFloat(word(ps, PlayerStateLayout.ORIGIN)),
      Float.intBitsToFloat(word(ps, PlayerStateLayout.ORIGIN + 4)),
      Float.intBitsToFloat(word(ps, PlayerStateLayout.ORIGIN + 8))
    };
  }

  private static boolean botPresent(EngineSession session, String name) {
    if (session == null || session.pipeline().isEmpty()) return false;
    Map<Integer, String> strings = session.pipeline().get().configStrings();
    for (int i = CS_PLAYERS; i < CS_PLAYERS + MAX_CLIENTS; i++) {
      String value = strings.getOrDefault(i, "").toLowerCase(Locale.ROOT);
      if (value.contains("n\\" + name) && value.contains("skill")) return true;
    }
    return false;
  }

  private static void waitMillis(ClientGameTestContext context, long millis) {
    long end = System.nanoTime() + millis * 1_000_000;
    context.waitFor(c -> System.nanoTime() >= end, ClientGameTestContext.NO_TIMEOUT);
  }

  private void waitFor(
      ClientGameTestContext context, String what, Predicate<Minecraft> condition, int ticks) {
    for (int waited = 0; waited < ticks; waited++) {
      if (context.computeOnClient(c -> condition.test(c))) return;
      if (context.computeOnClient(c -> !(c.gui.screen() instanceof Q3Screen)))
        throw new AssertionError("the Quake screen closed while waiting until " + what);
      context.waitTick();
    }
    throw new AssertionError("timed out waiting until " + what);
  }

  private void check(boolean condition, String failure) {
    if (!condition) {
      failures.add(failure);
      LOGGER.error("Pure-mode check failed: {}", failure);
    }
  }

  private void finish(ClientGameTestContext context, String tag) {
    Path file =
        FabricLoader.getInstance().getGameDir().resolve("craftq3-pure-report-" + tag + ".txt");
    if (!failures.isEmpty()) report.append("FAILURES:\n");
    for (String failure : failures) report.append("  ").append(failure).append('\n');
    try {
      Files.writeString(file, report);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    LOGGER.info("Pure-mode report ({}):\n{}", file, report);
    if (!failures.isEmpty()) throw new AssertionError("Pure-mode failures: " + failures);
  }

  private static BufferedImage screenshot(ClientGameTestContext context, String name) {
    return read(context.takeScreenshot(name));
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

  private static double luminance(int p) {
    return 0.299 * (p >> 16 & 255) + 0.587 * (p >> 8 & 255) + 0.114 * (p & 255);
  }

  private static double deviation(BufferedImage image) {
    double sum = 0, squares = 0;
    long n = 0;
    for (int y = 0; y < image.getHeight(); y += 2)
      for (int x = 0; x < image.getWidth(); x += 2) {
        double l = luminance(image.getRGB(x, y));
        sum += l;
        squares += l * l;
        n++;
      }
    double mean = sum / n;
    return Math.sqrt(Math.max(0, squares / n - mean * mean));
  }

  /** Mean absolute luminance difference of two same-sized screenshots. */
  private static double difference(BufferedImage a, BufferedImage b) {
    int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
    double sum = 0;
    long n = 0;
    for (int y = 0; y < h; y += 2)
      for (int x = 0; x < w; x += 2) {
        sum += Math.abs(luminance(a.getRGB(x, y)) - luminance(b.getRGB(x, y)));
        n++;
      }
    return sum / n;
  }

  private static long modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return -1;
    }
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return -1;
    }
  }

  private static CraftQ3ClientMod clientMod() {
    return FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream()
        .filter(CraftQ3ClientMod.class::isInstance)
        .map(CraftQ3ClientMod.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("CraftQ3ClientMod entrypoint not found"));
  }
}
