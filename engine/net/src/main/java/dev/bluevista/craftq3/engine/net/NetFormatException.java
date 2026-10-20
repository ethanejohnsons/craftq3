package dev.bluevista.craftq3.engine.net;

/**
 * Malformed, truncated or out-of-range wire data. It extends {@link IllegalArgumentException} so
 * callers that validate untrusted input with one catch clause keep working; the message names the
 * field or operation that failed.
 */
public class NetFormatException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public NetFormatException(String message) {
    super(message);
  }

  public NetFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
