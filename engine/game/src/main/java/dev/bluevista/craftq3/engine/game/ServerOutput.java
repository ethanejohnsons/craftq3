package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * The server console's output with {@code Com_BeginRedirect}: while a redirect is open (an rcon
 * command), lines go to the redirect buffer instead of the console.
 */
final class ServerOutput implements ConsoleOutput {
  private final ConsoleOutput console;
  private final Deque<StringBuilder> redirects = new ArrayDeque<>();

  ServerOutput(ConsoleOutput console) {
    this.console = Objects.requireNonNull(console);
  }

  @Override
  public void print(String line) {
    StringBuilder redirect = redirects.peek();
    if (redirect != null) redirect.append(line).append('\n');
    else console.print(line);
  }

  /** Starts capturing; every {@link #beginRedirect} needs one {@link #endRedirect}. */
  void beginRedirect() {
    redirects.push(new StringBuilder());
  }

  /** Stops capturing and returns the captured text. */
  String endRedirect() {
    return redirects.pop().toString();
  }
}
