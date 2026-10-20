package dev.bluevista.craftq3.assets.image;

import java.io.IOException;

/** A present image could not be decoded; this is distinct from a missing-image fallback. */
public final class ImageFormatException extends IOException {
  private static final long serialVersionUID = 1L;

  public ImageFormatException(String message) {
    super(message);
  }

  public ImageFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
