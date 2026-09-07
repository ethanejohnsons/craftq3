package dev.bluevista.craftq3.vm;

import java.io.IOException;

public final class QvmFormatException extends IOException {
  private static final long serialVersionUID = 1L;

  public QvmFormatException(String message) {
    super(message);
  }
}
