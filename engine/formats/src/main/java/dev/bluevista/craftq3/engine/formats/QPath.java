package dev.bluevista.craftq3.engine.formats;

import java.util.Locale;

/**
 * Quake III name handling for asset references found inside game data. Names are compared like the
 * original engine: case-insensitively with {@code \} equivalent to {@code /}. Nothing here touches
 * a host path; whether a name is safe to open is the filesystem's decision.
 */
public final class QPath {
  /** The original {@code MAX_QPATH}, including the terminator. */
  public static final int MAX_QPATH = 64;

  private QPath() {}

  /** Lower case, {@code /} separators, without leading separators or surrounding whitespace. */
  public static String normalize(String name) {
    String value = name.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
    int start = 0;
    while (start < value.length() && value.charAt(start) == '/') start++;
    return start == 0 ? value : value.substring(start);
  }

  /** The extension after the last dot of the final component, lower case, or empty. */
  public static String extension(String name) {
    int dot = name.lastIndexOf('.');
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return dot > slash ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
  }

  /** {@code name} without the extension of its final component ({@code COM_StripExtension}). */
  public static String stripExtension(String name) {
    int dot = name.lastIndexOf('.');
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return dot > slash ? name.substring(0, dot) : name;
  }

  /**
   * True for a relative name with no empty, {@code .} or {@code ..} component and no control or
   * drive characters. Parsers use it to refuse to publish a reference that could never name a game
   * file.
   */
  public static boolean isPlausible(String name) {
    if (name.isEmpty() || name.length() >= 256) return false;
    String value = name.replace('\\', '/');
    if (value.startsWith("/") || value.endsWith("/")) return false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 32 || c == 127 || c == ':') return false;
    }
    for (String part : value.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
    }
    return true;
  }
}
