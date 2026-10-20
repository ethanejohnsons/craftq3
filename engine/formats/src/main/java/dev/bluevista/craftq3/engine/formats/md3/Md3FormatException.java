package dev.bluevista.craftq3.engine.formats.md3;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** A malformed or over-budget MD3 model, skin or player animation file. */
public final class Md3FormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public Md3FormatException(String message) {
    super(message);
  }
}
