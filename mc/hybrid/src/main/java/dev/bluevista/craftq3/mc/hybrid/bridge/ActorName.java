package dev.bluevista.craftq3.mc.hybrid.bridge;

import java.util.Objects;

/**
 * Minecraft entity names as Quake player names: at most {@link #MAX_LENGTH} Latin-1 characters, no
 * colour escapes ({@code ^}), info-string delimiters ({@code \ " ;}) or control characters,
 * whitespace collapsed. Anything unrepresentable becomes {@code ?}; an empty result becomes {@link
 * #FALLBACK}.
 */
public final class ActorName {
  /** {@code MAX_NETNAME} (36) less room for the game's own decoration. */
  public static final int MAX_LENGTH = 34;

  public static final String FALLBACK = "Minecraft mob";

  private ActorName() {}

  public static String clean(String name) {
    Objects.requireNonNull(name, "name");
    StringBuilder result = new StringBuilder(MAX_LENGTH);
    int limit = Math.min(name.length(), 1024);
    for (int offset = 0; offset < limit && result.length() < MAX_LENGTH; ) {
      int code = name.codePointAt(offset);
      offset += Character.charCount(code);
      char value;
      if (Character.isWhitespace(code) || Character.isISOControl(code)) value = ' ';
      else if (code > 255 || code == '^' || code == '\\' || code == '"' || code == ';') value = '?';
      else value = (char) code;
      if (value != ' ' || (!result.isEmpty() && result.charAt(result.length() - 1) != ' '))
        result.append(value);
    }
    String cleaned = result.toString().strip();
    return cleaned.isEmpty() ? FALLBACK : cleaned;
  }
}
