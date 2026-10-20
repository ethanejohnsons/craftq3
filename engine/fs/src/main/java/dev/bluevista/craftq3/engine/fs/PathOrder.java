package dev.bluevista.craftq3.engine.fs;

import java.util.Comparator;

/**
 * The original engine's path comparison, used to sort PK3 names: ASCII letters compare as upper
 * case, {@code \} and {@code :} compare as {@code /}, and other characters compare as signed bytes.
 * Upper-case folding matters: {@code _} sorts after letters, so {@code zz_x.pk3} beats {@code
 * zzz.pk3}.
 */
final class PathOrder implements Comparator<String> {
  static final PathOrder INSTANCE = new PathOrder();

  private PathOrder() {}

  @Override
  public int compare(String a, String b) {
    int length = Math.max(a.length(), b.length());
    for (int i = 0; i < length; i++) {
      int c1 = i < a.length() ? fold(a.charAt(i)) : 0;
      int c2 = i < b.length() ? fold(b.charAt(i)) : 0;
      if (c1 != c2) return c1 < c2 ? -1 : 1;
    }
    return 0;
  }

  private static int fold(char c) {
    if (c >= 'a' && c <= 'z') return c - ('a' - 'A');
    if (c == '\\' || c == ':') return '/';
    return c <= 0xff ? (byte) c : c;
  }
}
