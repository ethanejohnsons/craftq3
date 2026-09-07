package dev.bluevista.craftq3.assets.shader;

import java.io.IOException;

/** A structurally malformed or resource-limit-exceeding material script. */
public final class ShaderFormatException extends IOException {
  private static final long serialVersionUID = 1L;

  public ShaderFormatException(String message) {
    super(message);
  }
}
