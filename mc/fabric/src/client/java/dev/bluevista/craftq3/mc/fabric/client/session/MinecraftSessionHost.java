package dev.bluevista.craftq3.mc.fabric.client.session;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.client.RenderSink;
import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.session.SessionHost;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.session.TransportFactory;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudioDevice;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * What Minecraft provides to one {@link dev.bluevista.craftq3.engine.session.EngineSession}:
 *
 * <ul>
 *   <li>frames go to a {@link Q3FramePresenter} (and from there to {@code Q3RenderBackend}); a
 *       loading frame drawn inside a VM call is also presented at once while the screen allows it
 *       ({@link #immediatePresentation});
 *   <li>sound goes to a {@link MinecraftAudioDevice} on Minecraft's sound engine;
 *   <li>the clipboard is read through Minecraft's keyboard handler;
 *   <li>the video size ({@code glconfig}) is Minecraft's framebuffer size as of the last {@link
 *       #applyVideoSize}, so the VMs' layout only changes at a {@code vid_restart}; the presenter
 *       scales frames to the real framebuffer in between;
 *   <li>the installation and game directory come from the mod's config, the home root is {@code
 *       <gameDir>/craftq3/home} (the session writes under {@code <home>/<game>/});
 *   <li>sockets are real UDP ({@code engine:net}); console lines go to Minecraft's log.
 * </ul>
 *
 * <p>{@code quit} only raises {@link #quitRequested()}: the screen closes after the session's frame
 * returns, never from inside a VM call. Render thread only.
 */
public final class MinecraftSessionHost implements SessionHost, AutoCloseable {
  private static final Logger LOGGER = LogUtils.getLogger();

  /**
   * What a particular kind of session adds to the platform: the bridge connects through Minecraft
   * payloads, supplies the level its server synthesizes and joins Minecraft terrain into the
   * client's collision. Render thread.
   */
  public interface Hooks {
    /** Datagram sockets (default: real UDP). */
    default TransportFactory transports() {
      return TransportFactory.UDP;
    }

    /** A map not in the game files ({@link SessionHost#mapOverride}). */
    default java.util.Optional<BspMap> mapOverride(String normalizedPath) {
      return java.util.Optional.empty();
    }

    /** Foreign collision for {@code mapName} ({@link SessionHost#worldSources}). */
    default java.util.List<CollisionSource> worldSources(String mapName) {
      return java.util.List.of();
    }

    /** Pure Quake III: nothing added. */
    Hooks NONE = new Hooks() {};
  }

  /** Where the home root lives, under Minecraft's game directory. */
  public static final String HOME_DIRECTORY = "craftq3/home";

  private final Minecraft minecraft;
  private final MinecraftAudio audio;
  private final Q3FramePresenter presenter;
  private final Path installRoot;
  private final String game;
  private final Path homeRoot;
  private final Clock clock;
  private final RenderSink sink;
  private final Hooks hooks;
  private Runnable immediatePresentation;
  private MinecraftAudioDevice device;
  private int videoWidth, videoHeight;
  private SessionState state = SessionState.MENU;
  private boolean quitRequested;
  private boolean hostUiRequested;
  private boolean closed;

  /**
   * @param installRoot the directory holding {@code baseq3}
   * @param game the game directory mounted over {@code baseq3}
   * @param gameDirectory Minecraft's game directory
   */
  public MinecraftSessionHost(
      Minecraft minecraft,
      MinecraftAudio audio,
      Q3FramePresenter presenter,
      Path installRoot,
      String game,
      Path gameDirectory,
      Clock clock) {
    this(minecraft, audio, presenter, installRoot, game, gameDirectory, clock, Hooks.NONE);
  }

  /** A host with session-specific {@code hooks} (the bridge's transport, map and terrain). */
  public MinecraftSessionHost(
      Minecraft minecraft,
      MinecraftAudio audio,
      Q3FramePresenter presenter,
      Path installRoot,
      String game,
      Path gameDirectory,
      Clock clock,
      Hooks hooks) {
    this.hooks = Objects.requireNonNull(hooks, "hooks");
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.audio = Objects.requireNonNull(audio, "audio");
    this.presenter = Objects.requireNonNull(presenter, "presenter");
    this.installRoot = Objects.requireNonNull(installRoot, "installRoot");
    this.game = Objects.requireNonNull(game, "game");
    this.homeRoot = homeRoot(gameDirectory);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sink =
        new RenderSink() {
          @Override
          public void submit(CgameFrame frame) {
            presenter.submit(frame);
          }

          @Override
          public void submitImmediate(CgameFrame frame) {
            presenter.submit(frame);
            Runnable present = immediatePresentation;
            if (present != null) present.run();
          }
        };
    applyVideoSize();
  }

  /**
   * What to run when the session hands over a frame drawn inside a long VM call ({@code
   * trap_UpdateScreen} during a loading screen): the screen sets it around its session frame to
   * present that frame at once; null keeps such frames until the next Minecraft frame.
   */
  public void immediatePresentation(Runnable present) {
    immediatePresentation = present;
  }

  /** {@code <gameDirectory>/craftq3/home}. */
  public static Path homeRoot(Path gameDirectory) {
    return gameDirectory.resolve(HOME_DIRECTORY).toAbsolutePath().normalize();
  }

  // ------------------------------------------------------------------ ClientHostServices

  @Override
  public RenderSink renderSink() {
    return sink;
  }

  @Override
  public AudioDevice audioDevice() {
    if (closed) throw new IllegalStateException("The session host is closed");
    if (device == null) device = audio.openDevice();
    return device;
  }

  @Override
  public String clipboardText() {
    try {
      String text = minecraft.keyboardHandler.getClipboard();
      return text == null ? "" : text;
    } catch (RuntimeException unavailable) {
      LOGGER.debug("CraftQ3: clipboard unavailable", unavailable);
      return "";
    }
  }

  @Override
  public int windowWidth() {
    return videoWidth;
  }

  @Override
  public int windowHeight() {
    return videoHeight;
  }

  @Override
  public void requestQuit() {
    quitRequested = true;
  }

  // ------------------------------------------------------------------ SessionHost

  @Override
  public Path installRoot() {
    return installRoot;
  }

  @Override
  public String game() {
    return game;
  }

  @Override
  public Path homeDirectory() {
    return homeRoot;
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public void log(String line) {
    LOGGER.info("[Q3] {}", line.stripTrailing());
  }

  @Override
  public void onStateChanged(SessionState previous, SessionState current) {
    state = current;
    LOGGER.info("CraftQ3: session {} -> {}", previous, current);
  }

  @Override
  public void requestHostUi(boolean visible) {
    hostUiRequested = visible;
  }

  @Override
  public TransportFactory transports() {
    return hooks.transports();
  }

  @Override
  public java.util.Optional<BspMap> mapOverride(String normalizedPath) {
    return hooks.mapOverride(normalizedPath);
  }

  @Override
  public java.util.List<CollisionSource> worldSources(String mapName) {
    return hooks.worldSources(mapName);
  }

  /** The cgame loaded a map: the presenter builds its scene from the session's own BSP. */
  @Override
  public void worldLoaded(String normalizedPath, BspMap map, ClientAssets assets) {
    presenter.attachAssets(assets.lookup(), assets.library());
    presenter.mapLoaded(normalizedPath, map);
  }

  @Override
  public void worldUnloaded() {
    presenter.mapUnloaded();
  }

  // ------------------------------------------------------------------ platform state

  /** Minecraft's framebuffer size now. */
  public int framebufferWidth() {
    Window window = minecraft.getWindow();
    return Math.max(1, window.getWidth());
  }

  public int framebufferHeight() {
    Window window = minecraft.getWindow();
    return Math.max(1, window.getHeight());
  }

  /**
   * Takes the framebuffer's size as the video size the VMs see; the caller then runs {@code
   * vid_restart}. Returns whether it changed.
   */
  public boolean applyVideoSize() {
    int width = framebufferWidth(), height = framebufferHeight();
    if (width == videoWidth && height == videoHeight) return false;
    videoWidth = width;
    videoHeight = height;
    return true;
  }

  /** The game's directory under the home root: configs, demos, screenshots. */
  public Path gameHome() {
    return homeRoot.resolve(game);
  }

  /** The session's state as last reported. */
  public SessionState state() {
    return state;
  }

  /** Whether the game asked to quit since the last {@link #clearQuit}. */
  public boolean quitRequested() {
    return quitRequested;
  }

  public void clearQuit() {
    quitRequested = false;
  }

  /** Whether the session asked for the host's own user interface (the ui VM cannot run). */
  public boolean hostUiRequested() {
    return hostUiRequested;
  }

  public Q3FramePresenter presenter() {
    return presenter;
  }

  /** The audio device, if the session opened one. */
  public MinecraftAudioDevice deviceOrNull() {
    return device;
  }

  /** Closes the audio device. The presenter is the screen's to close. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (device != null) device.close();
    device = null;
  }
}
