package dev.bluevista.craftq3.mc.fabric.client.bridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.client.pipeline.ClientSnapshot;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.session.EngineSession;
import dev.bluevista.craftq3.engine.session.GameLocalServer;
import dev.bluevista.craftq3.engine.session.SessionOptions;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.session.TransportFactory;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.mc.fabric.bridge.AvatarShape;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeAvatar;
import dev.bluevista.craftq3.mc.fabric.bridge.LevelTerrainCells;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputScreen;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputSink;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3LevelOverlay;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.client.session.MinecraftSessionHost;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3FramePresenter;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.bridge.SyntheticMapSource;
import dev.bluevista.craftq3.mc.hybrid.terrain.GridCollisionSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

/**
 * The client side of the bridge: Quake III's cgame and HUD over Minecraft's own view of the world.
 *
 * <ul>
 *   <li><b>Session</b>: one {@link EngineSession} that connects to the server's bridge game ({@code
 *       connect 127.77.255.255}) through a {@link PayloadClientTransport}, so the integrated and a
 *       dedicated server are reached the same way. The bridge level the server synthesizes comes
 *       from {@code mapOverride} (built locally like the server's), and the Minecraft blocks of the
 *       client's level join the cgame's prediction traces ({@code worldSources}).
 *   <li><b>View</b>: Minecraft renders its level as usual; {@code BridgeCameraMixin} puts its
 *       camera at the cgame's view ({@link BridgeCameraPose}, roll and field of view included), and
 *       the cgame frame (entities, effects, the weapon, the HUD) is drawn inside the level pass
 *       with Minecraft's projection and depth ({@link Q3LevelOverlay}), so blocks hide Quake things
 *       behind them. Minecraft's hand, view bobbing and HUD are hidden.
 *   <li><b>Body</b>: the local player is held where the Quake player is ({@code
 *       BridgeLocalPlayerMixin} stops its own movement) with the Quake hull ({@link BridgeAvatar});
 *       the server places the authoritative body the same way.
 *   <li><b>Input</b>: all of it goes to Quake ({@link Q3InputScreen}); Shift+Escape, {@code quit}
 *       or leaving the game in Quake's menu ends the bridge.
 *   <li><b>Lifecycle</b>: the screen does not pause the game; closing it closes the session and
 *       asks the server to end the bridge (which saves the loadout).
 * </ul>
 */
public final class BridgeScreen extends Q3InputScreen implements Q3LevelOverlay {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final SystemToast.SystemToastId FAILURE_TOAST = new SystemToast.SystemToastId();
  private static final int HISTORY = 4096;

  /** The bridge level's path as the cgame loads it. */
  public static final String MAP_PATH = "maps/" + SyntheticMapSource.MAP_NAME + ".bsp";

  private final Q3RenderContext renderContext;
  private final MinecraftAudio audio;
  private final Path installRoot;
  private final String game;
  private final Path gameDirectory;
  private final CoordinateTransform transform;
  private final Runnable leave;
  private final InputForwarder input;
  private final PayloadClientTransport.Factory transports = new PayloadClientTransport.Factory();

  private Q3FramePresenter presenter;
  private MinecraftSessionHost host;
  private EngineSession session;
  private GridCollisionSource terrain;
  private BspMap bridgeMap;
  private BridgeCameraPose pose;
  private boolean opened, started, connected, failed, closing;
  private long sessionFrames;
  private final long[] workNanos = new long[HISTORY];

  private BridgeScreen(
      Q3RenderContext renderContext,
      MinecraftAudio audio,
      Path installRoot,
      String game,
      Path gameDirectory,
      CoordinateTransform transform,
      Runnable leave,
      InputForwarder input) {
    super(Component.literal("Quake III in Minecraft"), input, Clock.system());
    this.renderContext = Objects.requireNonNull(renderContext);
    this.audio = Objects.requireNonNull(audio);
    this.installRoot = Objects.requireNonNull(installRoot);
    this.game = Objects.requireNonNull(game);
    this.gameDirectory = Objects.requireNonNull(gameDirectory);
    this.transform = Objects.requireNonNull(transform);
    this.leave = Objects.requireNonNull(leave);
    this.input = input;
    input.screen = this;
  }

  /**
   * A bridge screen for the server's bridge anchored by {@code transform}; {@code leave} asks the
   * server to end the bridge (it runs when the screen closes).
   */
  public static BridgeScreen create(
      Q3RenderContext renderContext,
      MinecraftAudio audio,
      Path installRoot,
      String game,
      Path gameDirectory,
      CoordinateTransform transform,
      Runnable leave) {
    return new BridgeScreen(
        renderContext,
        audio,
        installRoot,
        game,
        gameDirectory,
        transform,
        leave,
        new InputForwarder());
  }

  // ------------------------------------------------------------------ lifecycle

  @Override
  public void added() {
    super.added();
    if (opened) return;
    opened = true;
    try {
      presenter = new Q3FramePresenter(renderContext);
      terrain = new GridCollisionSource(new LevelTerrainCells(minecraft.level), transform, true);
      bridgeMap =
          SyntheticMapSource.build(
                  new SyntheticMapSource.World(
                      terrain, Vec3f.ZERO, 0, List.of(), "Minecraft bridge"))
              .build(MAP_PATH);
      host =
          new MinecraftSessionHost(
              minecraft,
              audio,
              presenter,
              installRoot,
              game,
              gameDirectory,
              Clock.system(),
              new MinecraftSessionHost.Hooks() {
                @Override
                public TransportFactory transports() {
                  return transports;
                }

                @Override
                public Optional<BspMap> mapOverride(String normalizedPath) {
                  return MAP_PATH.equalsIgnoreCase(normalizedPath)
                      ? Optional.of(bridgeMap)
                      : Optional.empty();
                }

                @Override
                public List<CollisionSource> worldSources(String mapName) {
                  return SyntheticMapSource.MAP_NAME.equalsIgnoreCase(mapName)
                      ? List.of(terrain)
                      : List.of();
                }
              });
    } catch (RuntimeException | LinkageError e) {
      fail("the bridge could not start", e);
    }
  }

  private void startSession() {
    started = true;
    try {
      List<String> commands = new ArrayList<>();
      String name = minecraft.getUser().getName().replaceAll("[\\\\\";]", "");
      if (!name.isEmpty()) commands.add("set name \"" + name + "\"");
      commands.add("connect " + PayloadClientTransport.SERVER_TEXT);
      session =
          EngineSession.start(
              host,
              SessionOptions.defaults(GameLocalServer.FACTORY)
                  .withNetEnabled(false)
                  .withCommandLine(commands));
      LOGGER.info("CraftQ3: bridge session started, home {}", host.gameHome());
    } catch (Exception | LinkageError e) {
      fail("the bridge could not start", e);
    }
  }

  /** The datagram the server sent (payload receiver, render thread). */
  public void receive(byte[] data) {
    PayloadClientTransport transport = transports.current();
    if (transport != null) transport.receive(data);
  }

  @Override
  public void removed() {
    super.removed();
    release();
  }

  /** Shift+Escape, {@code quit}, leaving the game, a failure or the server ending the bridge. */
  @Override
  public void onClose() {
    if (closing) return;
    closing = true;
    release();
    if (minecraft.gui.screen() == this) minecraft.gui.setScreen(null);
  }

  /** The server ended the bridge: close without asking it again. */
  public void ended() {
    closing = true;
    opened = false; // the server already knows
    release();
    if (minecraft.gui.screen() == this) minecraft.gui.setScreen(null);
  }

  private void release() {
    EngineSession s = session;
    session = null;
    pose = null;
    if (minecraft != null && minecraft.player != null)
      ((BridgeAvatar) minecraft.player).craftq3$setAvatar(null);
    if (s != null) {
      try {
        s.close();
      } catch (RuntimeException | Error e) {
        LOGGER.error("CraftQ3: the bridge session did not close cleanly", e);
      }
    }
    if (host != null) {
      try {
        host.close();
      } catch (RuntimeException e) {
        LOGGER.error("CraftQ3: the bridge audio did not close cleanly", e);
      }
      host = null;
    }
    if (presenter != null) {
      try {
        presenter.close();
      } catch (RuntimeException e) {
        LOGGER.error("CraftQ3: bridge renderer resources were not released cleanly", e);
      }
      presenter = null;
    }
    if (opened) {
      opened = false;
      try {
        leave.run();
      } catch (RuntimeException e) {
        LOGGER.warn("CraftQ3: could not tell the server the bridge ended", e);
      }
    }
  }

  void fail(String what, Throwable error) {
    if (failed) return;
    failed = true;
    LOGGER.error("CraftQ3: {}", what, error);
    String message =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    if (message.length() > 120) message = message.substring(0, 117) + "...";
    try {
      SystemToast.add(
          minecraft.gui.toastManager(),
          FAILURE_TOAST,
          Component.literal("CraftQ3: " + what),
          Component.literal(message));
    } catch (RuntimeException toast) {
      error.addSuppressed(toast);
    }
    minecraft.schedule(this::onClose);
  }

  // ------------------------------------------------------------------ frame

  @Override
  public void beforeCamera(Minecraft mc) {
    if (failed || closing || host == null) return;
    if (!started) startSession();
    EngineSession s = session;
    if (s == null) return;
    try {
      long start = System.nanoTime();
      terrain.invalidate();
      inputCapture().poll();
      s.frame();
      if (s.quitRequested() || host.quitRequested()) {
        host.clearQuit();
        mc.schedule(this::onClose);
        return;
      }
      SessionState state = s.state();
      if (state == SessionState.REMOTE) connected = true;
      else if (connected && state == SessionState.MENU) {
        // The game ended (disconnect from Quake's menu, the server dropped us).
        mc.schedule(this::onClose);
        return;
      }
      updatePose(mc);
      int slot = (int) (sessionFrames++ % HISTORY);
      workNanos[slot] = System.nanoTime() - start;
    } catch (RuntimeException | Error e) {
      fail("the bridge stopped unexpectedly", e);
    }
  }

  private void updatePose(Minecraft mc) {
    CgameFrame frame = presenter.latestFrame();
    CgameFrame.Refdef world = null;
    if (frame != null)
      for (CgameFrame.Command command : frame.commands())
        if (command instanceof CgameFrame.View view && view.refdef().worldModel()) {
          world = view.refdef();
          break;
        }
    pose = world == null ? null : BridgeCameraPose.of(world, transform);
    LocalPlayer player = mc.player;
    if (player == null || session == null) return;
    Optional<ClientSnapshot> snapshot = session.pipeline().flatMap(p -> p.latestSnapshot());
    if (snapshot.isEmpty()) return;
    byte[] ps = snapshot.get().snapshot().player();
    float ox = Float.intBitsToFloat(word(ps, PlayerStateLayout.ORIGIN));
    float oy = Float.intBitsToFloat(word(ps, PlayerStateLayout.ORIGIN + 4));
    float oz = Float.intBitsToFloat(word(ps, PlayerStateLayout.ORIGIN + 8));
    int viewHeight = word(ps, PlayerStateLayout.VIEWHEIGHT);
    boolean crouched = viewHeight < 20;
    // The server sets the precise hull; the client only needs a matching body for its own checks.
    float[] mins = {-15, -15, -24};
    float[] maxs = {15, 15, crouched ? 16 : 32};
    ((BridgeAvatar) player)
        .craftq3$setAvatar(AvatarShape.ofQuake(mins, maxs, viewHeight, transform.unitsPerBlock()));
    double x = transform.minecraftX(ox), y = transform.minecraftY(oz + mins[2]);
    double z = transform.minecraftZ(oy);
    player.setPos(x, y, z);
    if (pose != null) {
      player.setYRot(pose.yRot());
      player.setXRot(Math.clamp(pose.xRot(), -90f, 90f));
      player.setYHeadRot(pose.yRot());
    }
    player.setOldPosAndRot();
    player.setDeltaMovement(Vec3.ZERO);
  }

  private static int word(byte[] b, int at) {
    return (b[at] & 255) | (b[at + 1] & 255) << 8 | (b[at + 2] & 255) << 16 | b[at + 3] << 24;
  }

  @Override
  public void renderInLevel(
      Minecraft mc, Matrix4fc projection, Matrix4fc viewRotation, Vec3 camera) {
    if (failed || closing || presenter == null || session == null) return;
    try {
      RenderTarget target = mc.gameRenderer.mainRenderTarget();
      presenter.renderInLevel(
          target,
          host.windowWidth(),
          host.windowHeight(),
          Q3LevelOverlay.quakeToClip(projection, viewRotation, camera, transform),
          target.getDepthTextureView());
    } catch (RuntimeException | Error e) {
      fail("the bridge stopped unexpectedly", e);
    }
  }

  /** The cgame's camera for this frame, or null (Minecraft's own camera then). */
  public BridgeCameraPose pose() {
    return closing ? null : pose;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    if (failed || closing) return;
    if (session == null || presenter == null || presenter.framesDrawn() == 0)
      graphics.centeredText(
          font, Component.literal("Entering the bridge..."), width / 2, height / 2, 0xffffffff);
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return false;
  }

  // ------------------------------------------------------------------ inspection

  public EngineSession session() {
    return session;
  }

  public Q3FramePresenter presenter() {
    return presenter;
  }

  public MinecraftSessionHost host() {
    return host;
  }

  public CoordinateTransform transform() {
    return transform;
  }

  public boolean failed() {
    return failed;
  }

  /** The payload transport of the current connection, or null. */
  public PayloadClientTransport transport() {
    return transports.current();
  }

  /** Render-thread time of the last {@code count} session frames, oldest first. */
  public long[] recentWorkNanos(int count) {
    int n = (int) Math.min(Math.min(count, HISTORY), sessionFrames);
    long[] out = new long[n];
    for (int i = 0; i < n; i++) out[i] = workNanos[(int) ((sessionFrames - n + i) % HISTORY)];
    return out;
  }

  /** The open bridge screen, if the current screen is one. */
  public static BridgeScreen current(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof BridgeScreen screen ? screen : null;
  }

  // ------------------------------------------------------------------ input

  private static final class InputForwarder implements Q3InputSink {
    private BridgeScreen screen;
    private double mouseX, mouseY;

    @Override
    public void key(int q3Key, boolean down, long time) {
      EngineSession s = live();
      if (s == null) return;
      try {
        s.keyEvent(q3Key, down);
      } catch (RuntimeException | Error e) {
        screen.fail("the bridge stopped unexpectedly", e);
      }
    }

    @Override
    public void character(int codepoint) {
      EngineSession s = live();
      if (s == null) return;
      try {
        s.charEvent(codepoint);
      } catch (RuntimeException | Error e) {
        screen.fail("the bridge stopped unexpectedly", e);
      }
    }

    @Override
    public void mouseMove(double dx, double dy, long time) {
      EngineSession s = live();
      if (s == null) return;
      mouseX += dx;
      mouseY += dy;
      int x = (int) mouseX, y = (int) mouseY;
      mouseX -= x;
      mouseY -= y;
      if (x == 0 && y == 0) return;
      try {
        s.mouseEvent(x, y);
      } catch (RuntimeException | Error e) {
        screen.fail("the bridge stopped unexpectedly", e);
      }
    }

    private EngineSession live() {
      return screen == null || screen.failed || screen.closing ? null : screen.session;
    }
  }
}
