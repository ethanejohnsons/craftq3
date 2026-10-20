package dev.bluevista.craftq3.engine.formats.shader;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** A structurally malformed or resource-limit-exceeding shader script or shader. */
public final class ShaderFormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public ShaderFormatException(String message) {
    super(message);
  }
}
