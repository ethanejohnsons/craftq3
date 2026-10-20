package dev.bluevista.craftq3.engine.client;

import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.audio.NullAudioDevice;
import java.util.Objects;

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
