package dev.bluevista.craftq3.mc.fabric.client.screen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.client.host.HostServices;
import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.session.EngineSession;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputScreen;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputSink;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3FullscreenView;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.client.session.MinecraftSessionHost;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3FramePresenter;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3SessionFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Quake III, full screen, with no Minecraft world: one {@link EngineSession} and its {@link
 * MinecraftSessionHost}.
 *
 * <ul>
 *   <li><b>Frames</b>: each Minecraft frame runs one session frame, then draws the latest {@code
 *       CgameFrame} through the {@link Q3FramePresenter} in the render takeover pass. Minecraft's
 *       level is neither extracted nor rendered ({@link Q3FullscreenView}). When the session loads
 *       a map ({@code trap_R_LoadWorldMap}), its scene and textures are prepared off the render
 *       thread and the ui's or cgame's loading screen stays up meanwhile.
 *   <li><b>Input</b>: every key, character, button and the raw mouse motion go to the session
 *       ({@link Q3InputScreen}); Escape is Quake's own (its in-game menu), Shift+Escape always
 *       returns to Minecraft.
 *   <li><b>Audio</b>: Minecraft's own sounds are stopped and held off while the screen is open
 *       ({@link MinecraftAudio#claimHostAudio}).
 *   <li><b>Pause</b>: a pause screen, so an integrated world opened before stays paused.
 *   <li><b>Lifecycle</b>: the session starts on the screen's second frame (the first shows a
 *       "Starting" line). Closing the screen (Shift+Escape, {@code quit}, an unexpected failure, or
 *       the client stopping) closes the session (which writes {@code q3config.cfg} and joins the
 *       server thread), the audio device and claim, and the renderer's GPU objects. A window resize
 *       becomes a {@code vid_restart} once the size has settled; until then frames are scaled.
 *       Focus loss releases every key.
 *   <li><b>Failures</b>: game-level errors ({@code ERR_DROP}) stay inside the session and show in
 *       its menu. Anything that escapes it is logged, shown as a toast, and returns to Minecraft.
 * </ul>
 */
public final class Q3Screen extends Q3InputScreen implements Q3FullscreenView {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final SystemToast.SystemToastId FAILURE_TOAST = new SystemToast.SystemToastId();

  /** How long the framebuffer size must hold still before {@code vid_restart}. */
  static final long RESIZE_SETTLE_MILLIS = 400;

  private static final int HISTORY = 4096;

  private final Screen parent;
  private final Q3Launch launch;
  private final Q3SessionFactory factory;
  private final Q3RenderContext renderContext;
  private final MinecraftAudio audio;
  private final Path installRoot;
  private final String game;
  private final Path gameDirectory;
  private final Clock clock;
  private final InputForwarder input;

  private Q3FramePresenter presenter;
  private MinecraftSessionHost host;
  private EngineSession session;
  private HostServices attachedServices;
  private MinecraftAudio.HostAudioClaim audioClaim;
  private boolean opened;
  private boolean started;
  private boolean failed;
  private boolean closing;
  private int passes;
  private long resizeSeenAt = -1;
  private int pendingWidth, pendingHeight;
  private long sessionFrames;
  private long lastFrameNanos;
  private long startNanos;
  // Per frame: the session frame plus render submission on the render thread, and the wall time
  // since the previous frame.
  private final long[] workNanos = new long[HISTORY];
  private final long[] intervalNanos = new long[HISTORY];

  private Q3Screen(
      Screen parent,
      Q3Launch launch,
      Q3SessionFactory factory,
      Q3RenderContext renderContext,
      MinecraftAudio audio,
      Path installRoot,
      String game,
      Path gameDirectory,
      Clock clock,
      InputForwarder input) {
    super(Component.literal("Quake III Arena"), input, clock);
    this.parent = parent;
    this.launch = Objects.requireNonNull(launch, "launch");
    this.factory = Objects.requireNonNull(factory, "factory");
    this.renderContext = Objects.requireNonNull(renderContext, "renderContext");
    this.audio = Objects.requireNonNull(audio, "audio");
    this.installRoot = Objects.requireNonNull(installRoot, "installRoot");
    this.game = Objects.requireNonNull(game, "game");
    this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.input = input;
    input.screen = this;
  }

  /**
   * A screen that starts a session on {@code installRoot}/{@code game} when it opens and returns to
   * {@code parent} (null: back to the game) when it closes.
   */
  public static Q3Screen create(
      Screen parent,
      Q3Launch launch,
      Q3SessionFactory factory,
      Q3RenderContext renderContext,
      MinecraftAudio audio,
      Path installRoot,
      String game,
      Path gameDirectory) {
    return new Q3Screen(
        parent,
        launch,
        factory,
        renderContext,
        audio,
        installRoot,
        game,
        gameDirectory,
        Clock.system(),
        new InputForwarder());
  }

  // ------------------------------------------------------------------ lifecycle

  @Override
  public void added() {
    super.added();
    if (opened) return;
    opened = true;
    try {
      audioClaim = audio.claimHostAudio();
      presenter = new Q3FramePresenter(renderContext);
      host =
          new MinecraftSessionHost(
              minecraft, audio, presenter, installRoot, game, gameDirectory, clock);
    } catch (RuntimeException | LinkageError e) {
      fail("Quake III could not start", e);
    }
  }

  /** Starts the session (render thread; mounts the installation and boots the ui). */
  private void startSession() {
    started = true;
    long start = System.nanoTime();
    try {
      List<String> commandLine = launch.command().map(List::of).orElse(List.of());
      session = factory.open(host, commandLine);
      startNanos = System.nanoTime() - start;
      LOGGER.info(
          "CraftQ3: session started on {} ({}) in {} ms, home {}",
          installRoot,
          game,
          startNanos / 1_000_000,
          host.gameHome());
    } catch (Exception | LinkageError e) {
      fail("Quake III could not start", e);
    }
  }

  @Override
  public void removed() {
    super.removed();
    release();
  }

  /** Shift+Escape, {@code quit} or a failure: back to where the screen was opened from. */
  @Override
  public void onClose() {
    if (closing) return;
    closing = true;
    release();
    // A queued close may run after something else replaced this screen; leave that one alone.
    if (minecraft.gui.screen() == this) minecraft.gui.setScreen(parent);
  }

  /** Releases everything in reverse order of creation. Idempotent; never throws. */
  private void release() {
    EngineSession s = session;
    session = null;
    attachedServices = null;
    if (s != null) {
      try {
        s.close();
      } catch (RuntimeException | Error e) {
        LOGGER.error("CraftQ3: the session did not close cleanly", e);
      }
    }
    if (host != null) {
      try {
        host.close();
      } catch (RuntimeException e) {
        LOGGER.error("CraftQ3: the audio device did not close cleanly", e);
      }
    }
    if (presenter != null) {
      try {
        presenter.close();
      } catch (RuntimeException e) {
        LOGGER.error("CraftQ3: renderer resources were not released cleanly", e);
      }
    }
    if (audioClaim != null) {
      audioClaim.close();
      audioClaim = null;
    }
  }

  /** An unexpected failure: log, toast, return to Minecraft. Never throws. */
  void fail(String title, Throwable error) {
    if (failed) return;
    failed = true;
    LOGGER.error("CraftQ3: {}", title, error);
    String message =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    if (message.length() > 120) message = message.substring(0, 117) + "...";
    try {
      SystemToast.add(
          minecraft.gui.toastManager(),
          FAILURE_TOAST,
          Component.literal("CraftQ3: " + title),
          Component.literal(message));
    } catch (RuntimeException toastFailure) {
      error.addSuppressed(toastFailure);
    }
    // Leave the screen once the current input or render callback has returned.
    minecraft.schedule(this::onClose);
  }

  // ------------------------------------------------------------------ frame

  @Override
  public void renderFullscreen(RenderTarget target) {
    if (failed || closing || host == null) return;
    // The first pass only lets the "Starting" line reach the screen.
    if (passes++ == 0) return;
    if (!started) startSession();
    EngineSession s = session;
    if (s == null) return;
    try {
      long start = System.nanoTime();
      trackResize();
      inputCapture().poll();
      s.frame();
      if (s.quitRequested() || host.quitRequested()) {
        host.clearQuit();
        minecraft.schedule(this::onClose);
        return;
      }
      syncWorld(s);
      presenter.render(target, host.windowWidth(), host.windowHeight());
      long now = System.nanoTime();
      int slot = (int) (sessionFrames % HISTORY);
      workNanos[slot] = now - start;
      intervalNanos[slot] = lastFrameNanos == 0 ? 0 : now - lastFrameNanos;
      lastFrameNanos = now;
      sessionFrames++;
    } catch (RuntimeException | Error e) {
      fail("Quake III stopped unexpectedly", e);
    }
  }

  /**
   * Hands the renderer the session's current assets and map: a content rebuild (pure server packs)
   * brings new assets, and {@code trap_R_LoadWorldMap} a new world.
   */
  private void syncWorld(EngineSession s) {
    HostServices services = s.services();
    ClientAssets assets = services.assets();
    if (services != attachedServices) {
      attachedServices = services;
      presenter.attachAssets(assets.lookup(), assets.library());
    }
    String world = assets.worldName();
    if (world.equals(presenter.requestedMap())) return;
    if (world.isEmpty()) presenter.mapUnloaded();
    else assets.world().ifPresent(map -> presenter.mapLoaded(world, map));
  }

  /**
   * {@code vid_restart} semantics: once the framebuffer has held a new size for {@link
   * #RESIZE_SETTLE_MILLIS}, the VMs get it as their {@code glconfig}.
   */
  private void trackResize() {
    int width = host.framebufferWidth(), height = host.framebufferHeight();
    if (width == host.windowWidth() && height == host.windowHeight()) {
      resizeSeenAt = -1;
      return;
    }
    long now = clock.millis();
    if (resizeSeenAt < 0 || width != pendingWidth || height != pendingHeight) {
      resizeSeenAt = now;
      pendingWidth = width;
      pendingHeight = height;
      return;
    }
    if (now - resizeSeenAt < RESIZE_SETTLE_MILLIS) return;
    resizeSeenAt = -1;
    if (host.applyVideoSize()) {
      LOGGER.info("CraftQ3: window is now {}x{}; vid_restart", width, height);
      session.command("vid_restart\n");
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    if (failed) return;
    if (session == null || presenter == null || presenter.framesDrawn() == 0) {
      graphics.centeredText(
          font, Component.literal("Starting Quake III..."), width / 2, height / 2, 0xffffffff);
    } else if (host != null && host.hostUiRequested()) {
      graphics.centeredText(
          font,
          Component.literal("The Quake III menu could not start: press ~ for the console,"),
          width / 2,
          height - 34,
          0xffffff55);
      graphics.centeredText(
          font,
          Component.literal("Shift+Escape returns to Minecraft"),
          width / 2,
          height - 22,
          0xffffff55);
    }
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}

  @Override
  public boolean isPauseScreen() {
    return true;
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return false;
  }

  // ------------------------------------------------------------------ inspection (tests, stats)

  /** The running session, or null before it starts and after it closed. */
  public EngineSession session() {
    return session;
  }

  /** The session's host, or null before the screen opened. */
  public MinecraftSessionHost host() {
    return host;
  }

  /** The frame presenter, or null before the screen opened. */
  public Q3FramePresenter presenter() {
    return presenter;
  }

  /** Session frames run so far. */
  public long sessionFrames() {
    return sessionFrames;
  }

  /** How long {@code EngineSession.start} took (mount, configs, ui boot). */
  public long startNanos() {
    return startNanos;
  }

  /**
   * Render-thread time of the last {@code count} frames (session frame plus render submission),
   * oldest first.
   */
  public long[] recentWorkNanos(int count) {
    return recent(workNanos, count);
  }

  /** Wall time between the last {@code count} frames, oldest first. */
  public long[] recentIntervalNanos(int count) {
    return recent(intervalNanos, count);
  }

  private long[] recent(long[] ring, int count) {
    int n = (int) Math.min(Math.min(count, HISTORY), Math.max(0, sessionFrames - 1));
    long[] out = new long[n];
    for (int i = 0; i < n; i++) out[i] = ring[(int) ((sessionFrames - n + i) % HISTORY)];
    return out;
  }

  /** Whether the screen failed and is closing. */
  public boolean failed() {
    return failed;
  }

  /** The current screen if it is a {@code Q3Screen}. */
  public static Q3Screen current(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof Q3Screen screen ? screen : null;
  }

  // ------------------------------------------------------------------ input

  /**
   * Forwards captured input to the session; it exists before the screen (the capture needs it), so
   * the screen links itself in. An exception out of the session closes the screen with a toast.
   */
  private static final class InputForwarder implements Q3InputSink {
    private Q3Screen screen;
    private double mouseX, mouseY;

    @Override
    public void key(int q3Key, boolean down, long time) {
      EngineSession s = live();
      if (s == null) return;
      try {
        s.keyEvent(q3Key, down);
      } catch (RuntimeException | Error e) {
        screen.fail("Quake III stopped unexpectedly", e);
      }
    }

    @Override
    public void character(int codepoint) {
      EngineSession s = live();
      if (s == null) return;
      try {
        s.charEvent(codepoint);
      } catch (RuntimeException | Error e) {
        screen.fail("Quake III stopped unexpectedly", e);
      }
    }

    @Override
    public void mouseMove(double dx, double dy, long time) {
      EngineSession s = live();
      if (s == null) return;
      // Whole counts only; fractions (scaled or high-DPI motion) carry over.
      mouseX += dx;
      mouseY += dy;
      int x = (int) mouseX, y = (int) mouseY;
      mouseX -= x;
      mouseY -= y;
      if (x == 0 && y == 0) return;
      try {
        s.mouseEvent(x, y);
      } catch (RuntimeException | Error e) {
        screen.fail("Quake III stopped unexpectedly", e);
      }
    }

    private EngineSession live() {
      return screen == null || screen.failed || screen.closing ? null : screen.session;
    }
  }
}
