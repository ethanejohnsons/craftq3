package dev.bluevista.craftq3.engine.console;

/** Case-insensitive {@code *} and {@code ?} matching for list filters ({@code Com_Filter}). */
final class Wildcard {
  private Wildcard() {}

  static boolean matches(String pattern, String text) {
    String p = CvarTable.asciiLower(pattern);
    String t = CvarTable.asciiLower(text);
    int pi = 0;
    int ti = 0;
    int star = -1;
    int resume = 0;
    while (ti < t.length()) {
      if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == t.charAt(ti))) {
        pi++;
        ti++;
      } else if (pi < p.length() && p.charAt(pi) == '*') {
        star = pi++;
        resume = ti;
      } else if (star >= 0) {
        pi = star + 1;
        ti = ++resume;
      } else {
        return false;
      }
    }
    while (pi < p.length() && p.charAt(pi) == '*') pi++;
    return pi == p.length();
  }
}
