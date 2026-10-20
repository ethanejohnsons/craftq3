package dev.bluevista.craftq3.engine.fs;

/** A PK3 that cannot be indexed safely: empty, truncated, multi-volume, ZIP64 or over a limit. */
public final class Pk3FormatException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public Pk3FormatException(String message) {
    super(message);
  }

  public Pk3FormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
