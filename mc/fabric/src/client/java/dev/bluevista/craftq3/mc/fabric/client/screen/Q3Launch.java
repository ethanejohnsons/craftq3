package dev.bluevista.craftq3.mc.fabric.client.screen;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link Q3Screen} does once its session is up: show the main menu, or run one console
 * command ({@code map}, {@code connect}, {@code demo}) exactly as typed into the Quake console.
 */
public record Q3Launch(Optional<String> command) {
  public Q3Launch {
    Objects.requireNonNull(command, "command");
  }

  /** The original main menu. */
  public static Q3Launch menu() {
    return new Q3Launch(Optional.empty());
  }

  /** {@code map <name>}: a local game with this map. */
  public static Q3Launch map(String name) {
    return new Q3Launch(Optional.of("map " + token(name)));
  }

  /** {@code connect <host[:port]>}. */
  public static Q3Launch connect(String address) {
    return new Q3Launch(Optional.of("connect " + token(address)));
  }

  /** {@code demo <name>}. */
  public static Q3Launch demo(String name) {
    return new Q3Launch(Optional.of("demo " + token(name)));
  }

  /**
   * One console token: quotes, semicolons and line breaks would let a Minecraft command smuggle
   * further Quake commands, so they are rejected.
   */
  static String token(String value) {
    String v = Objects.requireNonNull(value, "value").strip();
    if (v.isEmpty()) throw new IllegalArgumentException("empty argument");
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == '"' || c == ';' || c < ' ' || c == 0x7f || Character.isWhitespace(c))
        throw new IllegalArgumentException(
            String.format(Locale.ROOT, "invalid character U+%04X in %s", (int) c, v));
    }
    return v;
  }
}
