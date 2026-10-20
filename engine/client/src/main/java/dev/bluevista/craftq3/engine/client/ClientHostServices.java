package dev.bluevista.craftq3.engine.client;

import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.audio.NullAudioDevice;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the platform (the Fabric mod, the headless runner, tests) provides to the client: where
 * frames go, the audio output, the clipboard, the window size and a way to ask the application to
 * quit. Every method is called on the render thread.
 */
public interface ClientHostServices {
  /** Receives every rendered frame. */
  RenderSink renderSink();

  /** The audio output the sound system plays through. */
  AudioDevice audioDevice();

  /** Clipboard text for {@code trap_GetClipboardData}; empty when unavailable. */
  default String clipboardText() {
    return "";
  }

  /** The framebuffer width in pixels. */
  int windowWidth();

  /** The framebuffer height in pixels. */
  int windowHeight();

  /** The game asked to quit ({@code quit} command). */
  default void requestQuit() {}

  /**
   * A map the host supplies instead of reading it from the game files, consulted first whenever the
   * client loads a world ({@code trap_CM_LoadMap}, {@code trap_R_LoadWorldMap}): for levels a
   * server synthesizes (the Minecraft bridge's {@code maps/craftq3_bridge.bsp}). The renderer's
   * world ({@code ClientAssets.world()}, from which render scenes are built) is the same object.
   *
   * @param normalizedPath the map's normalized game path, e.g. {@code maps/q3dm17.bsp}
   */
  default Optional<BspMap> mapOverride(String normalizedPath) {
    return Optional.empty();
  }

  /**
   * Foreign collision geometry joined into the client's world (model 0) traces and point contents
   * for {@code mapName}, as the server joins a map source's sources: the cgame's prediction then
   * collides with what the server's movement does. Called once per world load.
   *
   * @param mapName the map's base name as the server names it, e.g. {@code q3dm17}
   */
  default List<CollisionSource> worldSources(String mapName) {
    return List.of();
  }

  /** Services with no output: a null audio device and the given sink and size. */
  static ClientHostServices headless(RenderSink sink, int width, int height) {
    Objects.requireNonNull(sink);
    if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid window size");
    AudioDevice audio = new NullAudioDevice(64, false);
    return new ClientHostServices() {
      @Override
      public RenderSink renderSink() {
        return sink;
      }

      @Override
      public AudioDevice audioDevice() {
        return audio;
      }

      @Override
      public int windowWidth() {
        return width;
      }

      @Override
      public int windowHeight() {
        return height;
      }
    };
  }
}
