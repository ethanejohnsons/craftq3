package dev.bluevista.craftq3.engine.game.map;

import java.util.Objects;

/**
 * {@code trap_GetEntityToken} over an entity string: {@code COM_Parse} tokens (whitespace and
 * {@code //} and block comments skipped, quoted strings unquoted, at most {@link #MAX_TOKEN_CHARS}
 * - 1 characters kept per token). Once the text is exhausted every call returns an empty token and
 * false, as the original's null parse point does.
 */
public final class EntityTokens {
  /** {@code MAX_TOKEN_CHARS}. */
  public static final int MAX_TOKEN_CHARS = 1024;

  private final String text;
  private final StringBuilder token = new StringBuilder();
  private int position;
  private boolean more;

  public EntityTokens(String text) {
    this.text = Objects.requireNonNull(text);
  }

  public String text() {
    return text;
  }

  /** Restarts at the beginning ({@code sv.entityParsePoint = CM_EntityString()}). */
  public void rewind() {
    position = 0;
  }

  /** The next token, or empty at the end; {@link #more()} then tells what the trap returns. */
  public String next() {
    token.setLength(0);
    int length = text.length();
    while (true) {
      while (position < length && text.charAt(position) <= ' ') {
        if (text.charAt(position) == 0) position = length;
        else position++;
      }
      if (position >= length) {
        more = false;
        return "";
      }
      char c = text.charAt(position);
      boolean next = position + 1 < length;
      if (c == '/' && next && text.charAt(position + 1) == '/') {
        while (position < length && text.charAt(position) != '\n') position++;
      } else if (c == '/' && next && text.charAt(position + 1) == '*') {
        position += 2;
        while (position < length
            && !(text.charAt(position) == '*'
                && position + 1 < length
                && text.charAt(position + 1) == '/')) position++;
        if (position < length) position += 2;
      } else break;
    }
    if (text.charAt(position) == '"') {
      position++;
      while (position < length) {
        char q = text.charAt(position++);
        if (q == '"' || q == 0) break;
        if (token.length() < MAX_TOKEN_CHARS - 1) token.append(q);
      }
    } else {
      while (position < length && text.charAt(position) > ' ') {
        if (token.length() < MAX_TOKEN_CHARS - 1) token.append(text.charAt(position));
        position++;
      }
    }
    more = true;
    return token.toString();
  }

  /** What {@code SV_GetEntityToken} returned for the last {@link #next()}. */
  public boolean more() {
    return more;
  }
}
