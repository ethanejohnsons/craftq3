package dev.bluevista.craftq3.engine.formats.bsp;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** A BSP the reader refuses to publish; the message names the lump and reason. */
public final class BspFormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public BspFormatException(String message) {
    super(message);
  }

  public BspFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
