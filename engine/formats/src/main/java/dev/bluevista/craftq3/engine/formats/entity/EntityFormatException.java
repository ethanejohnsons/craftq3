package dev.bluevista.craftq3.engine.formats.entity;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** A structurally damaged entity lump. */
public final class EntityFormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public EntityFormatException(String message) {
    super(message);
  }
}
