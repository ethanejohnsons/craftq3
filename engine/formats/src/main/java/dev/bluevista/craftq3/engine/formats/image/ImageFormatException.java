package dev.bluevista.craftq3.engine.formats.image;

import dev.bluevista.craftq3.engine.formats.FormatException;
import java.io.Serial;

/** A present image could not be decoded; this is distinct from a missing image. */
public final class ImageFormatException extends FormatException {
  @Serial private static final long serialVersionUID = 1L;

  public ImageFormatException(String message) {
    super(message);
  }

  public ImageFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
