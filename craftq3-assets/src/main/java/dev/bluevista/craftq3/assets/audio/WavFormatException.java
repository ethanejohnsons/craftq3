package dev.bluevista.craftq3.assets.audio;

import java.io.IOException;

public final class WavFormatException extends IOException {
  private static final long serialVersionUID = 1L;

  public WavFormatException(String message) {
    super(message);
  }
}
