package dev.bluevista.craftq3.core.net;

/** Published MSG string capacities and the native-observed byte sanitation table. */
final class MessageStrings {
  static final int NORMAL_CAPACITY = 1024, BIG_CAPACITY = 8192;

  private MessageStrings() {}

  static char sanitize(int c) {
    return c == '%' || c > 127 ? '.' : (char) c;
  }
}
