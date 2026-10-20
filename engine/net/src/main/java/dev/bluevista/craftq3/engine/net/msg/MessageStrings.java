package dev.bluevista.craftq3.engine.net.msg;

/** The native-observed MSG byte sanitation table. */
public final class MessageStrings {
  private MessageStrings() {}

  /** The native reader and writer replace {@code %} and every byte above 127 with a dot. */
  public static char sanitize(int c) {
    return c == '%' || c > 127 ? '.' : (char) c;
  }
}
