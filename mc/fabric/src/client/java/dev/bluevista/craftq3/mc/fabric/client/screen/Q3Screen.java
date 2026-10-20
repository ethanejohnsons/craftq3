package dev.bluevista.craftq3.mc.fabric.client.screen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputScreen;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputSink;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3FullscreenView;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.client.session.MinecraftSessionHost;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3FramePresenter;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3Session;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3SessionFactory;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Quake III, full screen, with no Minecraft world: one {@link Q3Session} and its {@link
 * MinecraftSessionHost}.
 *
 * <ul>
 *   <li><b>Frames</b>: each Minecraft frame runs one session frame, then draws the latest {@code
 *       CgameFrame} through the session's {@link Q3FramePresenter} in the render takeover pass.
 *       Minecraft's level is neither extracted nor rendered ({@link Q3FullscreenView}).
 *   <li><b>Input</b>: every key, character, button and the raw mouse motion go to the session
 *       ({@link Q3InputScreen}); Escape is Quake's own (its in-game menu), Shift+Escape always
 *       returns to Minecraft.
 *   <li><b>Audio</b>: Minecraft's own sounds are stopped and held off while the screen is open
 *       ({@link MinecraftAudio#claimHostAudio}).
 *   <li><b>Pause</b>: a pause screen, so an integrated world opened before stays paused.
 *   <li><b>Lifecycle</b>: closing the screen (Shift+Escape, {@code quit}, an unexpected failure, or
 *       the client stopping) closes the session (which writes {@code q3config.cfg}), the audio
 *       device and claim, and the backend's GPU objects. A window resize reaches the session as
 *       {@code vid_restart}; focus loss releases every key.
 *   <li><b>Failures</b>: game-level errors ({@code ERR_DROP}) stay inside the session and its menu.
 *       Anything that escapes it is logged, shown as a toast, and returns to Minecraft.
 * </ul>
 */
public final class Q3Screen extends Q3InputScreen implements Q3FullscreenView {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final SystemToast.SystemToastId FAILURE_TOAST = new SystemToast.SystemToastId();

  private final Screen parent;
  private final Q3Launch launch;
  private final Q3SessionFactory factory;
  private final Q3RenderContext renderContext;
  private final MinecraftAudio audio;
  private final Path installRoot;
  private final String game;
  private final Path gameDirectory;
  private final Clock clock;
  private final long startMillis;
  private final InputForwarder input;

  private Q3FramePresenter presenter;
  private MinecraftSessionHost host;
  private Q3Session session;
  private MinecraftAudio.HostAudioClaim audioClaim;
  private boolean launched;
  private boolean failed;
  private boolean closing;
  private int videoWidth, videoHeight;
  private boolean focused = true;
  private long sessionFrames;
  private long lastFrameNanos;
  private static final int HISTORY = 4096;
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
    this.clock = clock;
    this.startMillis = clock.millis();
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
    if (launched) return;
    launched = true;
    try {
      audioClaim = audio.claimHostAudio();
      presenter = new Q3FramePresenter(renderContext);
      host =
          new MinecraftSessionHost(minecraft, audio, presenter, installRoot, game, gameDirectory);
      videoWidth = host.windowWidth();
      videoHeight = host.windowHeight();
      session = factory.open(host);
      LOGGER.info(
          "CraftQ3: session started on {} ({}), home {}", installRoot, game, host.homeDirectory());
      launch.command().ifPresent(command -> session.command(command + "\n"));
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
    minecraft.gui.setScreen(parent);
  }

  /** Releases everything in reverse order of creation. Idempotent; never throws. */
  private void release() {
    Q3Session s = session;
    session = null;
    if (s != null) {
      try {
        s.close();
      } catch (RuntimeException e) {
        LOGGER.error("CraftQ3: the session did not close cleanly", e);
      }
    }
    if (host != null) {
      try {
        host.close();
      } catch (RuntimeException e) {
        LOGGER.error("CraftQ3: the audio device did not close cleanly", e);
      }
      host = null;
    }
    if (presenter != null) {
      try {
        presenter.close();
      } catch (RuntimeException e) {
        LOGGER.error("CraftQ3: renderer resources were not released cleanly", e);
      }
      presenter = null;
    }
    if (audioClaim != null) {
      audioClaim.close();
      audioClaim = null;
    }
  }

  /** An unexpected failure: log, toast, return to Minecraft. Never throws. */
  private void fail(String title, Throwable error) {
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
    minecraft.execute(this::onClose);
  }

  // ------------------------------------------------------------------ frame

  @Override
  public void renderFullscreen(RenderTarget target) {
    if (session == null || failed) return;
    try {
      int width = host.windowWidth(), height = host.windowHeight();
      if (width != videoWidth || height != videoHeight) {
        videoWidth = width;
        videoHeight = height;
        session.videoResized(width, height);
      }
      boolean nowFocused = inputCapture().poll();
      if (nowFocused != focused) {
        focused = nowFocused;
        session.focusChanged(nowFocused);
      }
      long start = System.nanoTime();
      session.frame(realMillis());
      if (host.quitRequested()) {
        host.clearQuit();
        minecraft.execute(this::onClose);
        return;
      }
      presenter.render(target, target.width, target.height);
      long now = System.nanoTime();
      int slot = (int) (sessionFrames % HISTORY);
      workNanos[slot] = now - start;
      intervalNanos[slot] = lastFrameNanos == 0 ? 0 : now - lastFrameNanos;
      lastFrameNanos = now;
      sessionFrames++;
    } catch (RuntimeException | LinkageError e) {
      fail("Quake III stopped unexpectedly", e);
    }
  }

  private int realMillis() {
    return (int) (clock.millis() - startMillis);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    if (session != null && presenter != null && presenter.framesDrawn() == 0) {
      graphics.centeredText(
          font, Component.literal("Starting Quake III..."), width / 2, height / 2, 0xffffffff);
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
  public Q3Session session() {
    return session;
  }

  /** The session's host, or null. */
  public MinecraftSessionHost host() {
    return host;
  }

  /** The frame presenter, or null. */
  public Q3FramePresenter presenter() {
    return presenter;
  }

  /** Session frames run so far. */
  public long sessionFrames() {
    return sessionFrames;
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

  // ------------------------------------------------------------------ input

  /** Forwards captured input to the session; the sink must exist before the screen. */
  private static final class InputForwarder implements Q3InputSink {
    private Q3Screen screen;
    private double mouseX, mouseY;

    @Override
    public void key(int q3Key, boolean down, long time) {
      Q3Session s = live();
      if (s == null) return;
      try {
        s.keyEvent(q3Key, down, screen.relative(time));
      } catch (RuntimeException e) {
        screen.fail("Quake III stopped unexpectedly", e);
      }
    }

    @Override
    public void character(int codepoint) {
      Q3Session s = live();
      if (s == null) return;
      try {
        s.charEvent(codepoint, screen.realMillis());
      } catch (RuntimeException e) {
        screen.fail("Quake III stopped unexpectedly", e);
      }
    }

    @Override
    public void mouseMove(double dx, double dy, long time) {
      Q3Session s = live();
      if (s == null) return;
      // Whole counts only; fractions (scaled or high-DPI motion) carry over.
      mouseX += dx;
      mouseY += dy;
      int x = (int) mouseX, y = (int) mouseY;
      mouseX -= x;
      mouseY -= y;
      if (x == 0 && y == 0) return;
      try {
        s.mouseEvent(x, y, screen.relative(time));
      } catch (RuntimeException e) {
        screen.fail("Quake III stopped unexpectedly", e);
      }
    }

    private Q3Session live() {
      return screen == null || screen.failed ? null : screen.session;
    }
  }

  private int relative(long captureMillis) {
    return (int) (captureMillis - startMillis);
  }

  /** The current screen if it is a {@code Q3Screen}. */
  public static Q3Screen current(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof Q3Screen screen ? screen : null;
  }
}
