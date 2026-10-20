package dev.bluevista.craftq3.engine.console;

/**
 * A console failure: resource exhaustion (cvar limit) or a command reporting a usage error. The
 * command registry prints it instead of letting it end the session.
 */
public final class ConsoleException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ConsoleException(String message) {
    super(message);
  }
}
