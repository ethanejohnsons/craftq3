package dev.bluevista.craftq3.engine.formats;

/**
 * The original engine's general text tokenizer: whitespace is any character up to space, line and
 * block comments are skipped (an unterminated block comment ends the text), quoted strings have no
 * escapes and end at the closing quote or the end of text, and other tokens run to the next
 * whitespace. An empty token marks the end of input (or of the line, when line breaks are not
 * allowed).
 */
public final class TextTokenizer {
  private final String text;
  private int cursor;
  private int line = 1;

  public TextTokenizer(String text) {
    int nul = text.indexOf('\0');
    this.text = nul < 0 ? text : text.substring(0, nul);
  }

  /** The next token, crossing line breaks. */
  public String next() {
    return next(true);
  }

  /** The next token, or {@code ""} at the end (or at a line break when they are not allowed). */
  public String next(boolean allowLineBreaks) {
    if (!skip(allowLineBreaks)) return "";
    char c = text.charAt(cursor);
    if (c == '"') {
      int start = ++cursor;
      while (cursor < text.length() && text.charAt(cursor) != '"') {
        if (text.charAt(cursor) == '\n') line++;
        cursor++;
      }
      String token = text.substring(start, cursor);
      if (cursor < text.length()) cursor++;
      return token;
    }
    int start = cursor;
    while (cursor < text.length() && text.charAt(cursor) > ' ') cursor++;
    return text.substring(start, cursor);
  }

  /** One-based line of the cursor. */
  public int line() {
    return line;
  }

  public int position() {
    return cursor;
  }

  /** Restores a position previously returned by {@link #position()} (an "unget"). */
  public void reset(int position, int lineAtPosition) {
    this.cursor = position;
    this.line = lineAtPosition;
  }

  private boolean skip(boolean allowLineBreaks) {
    while (cursor < text.length()) {
      char c = text.charAt(cursor);
      if (c <= ' ') {
        if (c == '\n') {
          if (!allowLineBreaks) return false;
          line++;
        }
        cursor++;
      } else if (text.startsWith("//", cursor)) {
        while (cursor < text.length() && text.charAt(cursor) != '\n') cursor++;
      } else if (text.startsWith("/*", cursor)) {
        int close = text.indexOf("*/", cursor + 2);
        int end = close < 0 ? text.length() : close + 2;
        for (int i = cursor; i < end; i++) if (text.charAt(i) == '\n') line++;
        cursor = end;
      } else {
        return true;
      }
    }
    return false;
  }

  /** C {@code atoi}: optional sign and leading digits; anything else yields 0. */
  public static int atoi(String token) {
    int i = 0;
    boolean negative = false;
    if (i < token.length() && (token.charAt(i) == '-' || token.charAt(i) == '+'))
      negative = token.charAt(i++) == '-';
    long value = 0;
    while (i < token.length() && Character.isDigit(token.charAt(i)) && value <= Integer.MAX_VALUE)
      value = value * 10 + (token.charAt(i++) - '0');
    value = negative ? -value : value;
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
  }

  /** C {@code atof}: the longest numeric prefix, or 0. */
  public static float atof(String token) {
    int end = 0;
    int length = token.length();
    if (end < length && (token.charAt(end) == '-' || token.charAt(end) == '+')) end++;
    boolean digits = false;
    while (end < length && Character.isDigit(token.charAt(end))) {
      end++;
      digits = true;
    }
    if (end < length && token.charAt(end) == '.') {
      end++;
      while (end < length && Character.isDigit(token.charAt(end))) {
        end++;
        digits = true;
      }
    }
    if (!digits) return 0;
    int mantissaEnd = end;
    if (end < length && (token.charAt(end) == 'e' || token.charAt(end) == 'E')) {
      int exponent = end + 1;
      if (exponent < length && (token.charAt(exponent) == '-' || token.charAt(exponent) == '+'))
        exponent++;
      int exponentDigits = exponent;
      while (exponentDigits < length && Character.isDigit(token.charAt(exponentDigits)))
        exponentDigits++;
      if (exponentDigits > exponent) mantissaEnd = exponentDigits;
    }
    return Float.parseFloat(token.substring(0, mantissaEnd));
  }
}
