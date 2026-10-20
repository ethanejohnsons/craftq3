package dev.bluevista.craftq3.mc.fabric.client.session;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.client.ClientHostServices;
import dev.bluevista.craftq3.engine.client.RenderSink;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudioDevice;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * What Minecraft provides to one Quake III session: frames go to a {@link Q3FramePresenter} (and
 * from there to {@code Q3RenderBackend}), sound to a {@link MinecraftAudioDevice} on Minecraft's
 * sound engine, the clipboard through Minecraft's keyboard handler, the window size from
 * Minecraft's window, the installation and game directory from the mod's config, and the home
 * directory {@code <gameDir>/craftq3/home/<game>}.
 *
 * <p>{@code quit} only raises {@link #quitRequested()}: the screen closes after the session's frame
 * returns, never from inside a VM call. Render thread only.
 */
public final class MinecraftSessionHost implements ClientHostServices, AutoCloseable {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Where the per-game home directories live, under Minecraft's game directory. */
  public static final String HOME_DIRECTORY = "craftq3/home";

  private final Minecraft minecraft;
  private final MinecraftAudio audio;
  private final Q3FramePresenter presenter;
  private final Path installRoot;
  private final String game;
  private final Path homeDirectory;
  private final RenderSink sink;
  private MinecraftAudioDevice device;
  private boolean quitRequested;
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
      Path gameDirectory) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.audio = Objects.requireNonNull(audio, "audio");
    this.presenter = Objects.requireNonNull(presenter, "presenter");
    this.installRoot = Objects.requireNonNull(installRoot, "installRoot");
    this.game = Objects.requireNonNull(game, "game");
    this.homeDirectory = homeDirectory(gameDirectory, game);
    this.sink = presenter::submit;
  }

  /** {@code <gameDirectory>/craftq3/home/<game>}. */
  public static Path homeDirectory(Path gameDirectory, String game) {
    return gameDirectory.resolve(HOME_DIRECTORY).resolve(game).toAbsolutePath().normalize();
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
    Window window = minecraft.getWindow();
    return Math.max(1, window.getWidth());
  }

  @Override
  public int windowHeight() {
    Window window = minecraft.getWindow();
    return Math.max(1, window.getHeight());
  }

  @Override
  public void requestQuit() {
    quitRequested = true;
  }

  // ------------------------------------------------------------------ session platform

  /** The installation (the directory containing {@code baseq3}). */
  public Path installRoot() {
    return installRoot;
  }

  /** The game directory mounted over {@code baseq3} ({@code fs_game}, or {@code baseq3}). */
  public String game() {
    return game;
  }

  /** The writable home directory: configs, demos, screenshots, downloads. */
  public Path homeDirectory() {
    return homeDirectory;
  }

  /** The session's file view and shader scripts, before its first frame. */
  public void attachAssets(AssetLookup assets, ShaderLibrary shaders) {
    presenter.attachAssets(assets, shaders);
  }

  /** The session loaded a map ({@code trap_R_LoadWorldMap}): the renderer prepares it. */
  public void mapLoaded(String name, BspMap map) {
    presenter.mapLoaded(name, map);
  }

  /** The session left its map. */
  public void mapUnloaded() {
    presenter.mapUnloaded();
  }

  /** Whether the game asked to quit since the last {@link #clearQuit}. */
  public boolean quitRequested() {
    return quitRequested;
  }

  public void clearQuit() {
    quitRequested = false;
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
