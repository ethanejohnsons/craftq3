package dev.bluevista.craftq3.engine.formats.wav;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** A sound the reader refuses: not RIFF/WAVE, compressed, or without a usable format. */
public final class WavFormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public WavFormatException(String message) {
    super(message);
  }
}
