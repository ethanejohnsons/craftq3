package dev.bluevista.craftq3.engine.formats;

import java.io.Serial;

/**
 * Base of every parser rejection in this module. Parsers reject only what would be unsafe to
 * publish (ranges outside the file, resource exhaustion, references a consumer would follow out of
 * bounds) or what the original engine itself refuses; the message names the reason.
 */
public class FormatException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  public FormatException(String message) {
    super(message);
  }

  public FormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
