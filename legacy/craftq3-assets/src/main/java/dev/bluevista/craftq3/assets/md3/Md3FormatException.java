package dev.bluevista.craftq3.assets.md3;

import java.io.IOException;

/** Malformed or over-budget MD3, skin, or player-animation input. */
public final class Md3FormatException extends IOException {
  private static final long serialVersionUID = 1L;

  public Md3FormatException(String message) {
    super(message);
  }
}
