package dev.bluevista.craftq3.engine.formats.aas;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** An AAS file the reader refuses to publish; the message names the lump and reason. */
public final class AasFormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public AasFormatException(String message) {
    super(message);
  }
}
