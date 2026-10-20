package dev.bluevista.craftq3.engine.formats.roq;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** Malformed, truncated or over-budget RoQ data. */
public final class RoqFormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public RoqFormatException(String message) {
    super(message);
  }

  public RoqFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
