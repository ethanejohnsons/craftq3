package dev.bluevista.craftq3.engine.client.script;

import java.util.Objects;

/**
 * Splits one script file into {@link ScriptToken}s: C and C++ comments, double-quoted strings with
 * C escapes (adjacent strings separated only by white space or comments are joined into one),
 * character literals, decimal, hexadecimal, octal and binary integers, decimal floats, names and
 * the {@link ScriptPunctuation} set. Characters outside that set are returned as one-character
 * punctuation with id 0 rather than ending the script.
 */
public final class ScriptLexer {
  private final String file;
  private final String text;
  private int position;
  private int line = 1;
  private ScriptToken pushedBack;

  public ScriptLexer(String file, String text) {
    this.file = Objects.requireNonNull(file);
    this.text = Objects.requireNonNull(text);
  }

  public String file() {
    return file;
  }

  /** The line of the cursor (after the last token read). */
  public int line() {
    return line;
  }

  /** Makes {@code token} the next one {@link #next()} returns. */
  public void unread(ScriptToken token) {
    if (pushedBack != null) throw new IllegalStateException("Only one token can be unread");
    pushedBack = token;
  }

  /** The next token, or null at the end of the file. */
  public ScriptToken next() {
    if (pushedBack != null) {
      ScriptToken token = pushedBack;
      pushedBack = null;
      return token;
    }
    int lineBefore = line;
    int start = position;
    skipWhiteSpace();
    if (position >= text.length()) return null;
    boolean space = position != start;
    int crossed = line - lineBefore;
    char c = text.charAt(position);
    int tokenLine = line;
    if (c == '"') return readString(tokenLine, crossed, space);
    if (c == '\'') return readLiteral(tokenLine, crossed, space);
    if (isDigit(c)
        || (c == '.' && position + 1 < text.length() && isDigit(text.charAt(position + 1))))
      return readNumber(tokenLine, crossed, space);
    if (isNameStart(c)) {
      int begin = position;
      while (position < text.length() && isNamePart(text.charAt(position))) position++;
      String name = text.substring(begin, position);
      checkLength(name.length());
      return new ScriptToken(
          ScriptToken.TT_NAME, name.length(), name, 0, 0, tokenLine, crossed, space);
    }
    int id = ScriptPunctuation.match(text, position);
    String punctuation = id == 0 ? String.valueOf(c) : ScriptPunctuation.text(id);
    position += punctuation.length();
    return new ScriptToken(
        ScriptToken.TT_PUNCTUATION, id, punctuation, 0, 0, tokenLine, crossed, space);
  }

  /**
   * Skips to the end of the current line and returns its raw rest, for directives such as {@code
   * #error}.
   */
  public String restOfLine() {
    int begin = position;
    while (position < text.length() && text.charAt(position) != '\n') position++;
    return text.substring(begin, position).strip();
  }

  /** Whether white space or a comment before the next token contains a line break. */
  public boolean lineBreakAhead() {
    if (pushedBack != null) return pushedBack.linesCrossed() > 0;
    int savedPosition = position, savedLine = line;
    skipWhiteSpace();
    boolean crossed = line != savedLine || position >= text.length();
    position = savedPosition;
    line = savedLine;
    return crossed;
  }

  private void skipWhiteSpace() {
    while (position < text.length()) {
      char c = text.charAt(position);
      if (c == '\n') {
        line++;
        position++;
      } else if (c <= ' ') position++;
      else if (c == '/' && position + 1 < text.length() && text.charAt(position + 1) == '/') {
        while (position < text.length() && text.charAt(position) != '\n') position++;
      } else if (c == '/' && position + 1 < text.length() && text.charAt(position + 1) == '*') {
        position += 2;
        while (position < text.length()
            && !(text.charAt(position) == '*'
                && position + 1 < text.length()
                && text.charAt(position + 1) == '/')) {
          if (text.charAt(position) == '\n') line++;
          position++;
        }
        position = Math.min(text.length(), position + 2);
      } else return;
    }
  }

  private ScriptToken readString(int tokenLine, int crossed, boolean space) {
    StringBuilder value = new StringBuilder();
    while (true) {
      position++; // opening quote
      while (true) {
        if (position >= text.length()) throw error("missing trailing quote");
        char c = text.charAt(position);
        if (c == '"') {
          position++;
          break;
        }
        if (c == '\n') throw error("newline inside string");
        if (c == '\\') value.append(escape());
        else {
          value.append(c);
          position++;
        }
        checkLength(value.length() + 2);
      }
      int savedPosition = position, savedLine = line;
      skipWhiteSpace();
      if (position < text.length() && text.charAt(position) == '"') continue;
      position = savedPosition;
      line = savedLine;
      break;
    }
    String contents = value.toString();
    return new ScriptToken(
        ScriptToken.TT_STRING,
        contents.length(),
        '"' + contents + '"',
        0,
        0,
        tokenLine,
        crossed,
        space);
  }

  private ScriptToken readLiteral(int tokenLine, int crossed, boolean space) {
    position++;
    if (position >= text.length()) throw error("missing trailing quote");
    char value;
    if (text.charAt(position) == '\\') value = escape();
    else value = text.charAt(position++);
    if (position >= text.length() || text.charAt(position) != '\'')
      throw error("too many characters in literal");
    position++;
    return new ScriptToken(
        ScriptToken.TT_LITERAL, value, "'" + value + "'", value, value, tokenLine, crossed, space);
  }

  /** Reads an escape sequence at the backslash and returns the character it stands for. */
  private char escape() {
    position++; // backslash
    if (position >= text.length()) throw error("unterminated escape");
    char c = text.charAt(position++);
    switch (c) {
      case 'n':
        return '\n';
      case 'r':
        return '\r';
      case 't':
        return '\t';
      case 'v':
        return 0x0b;
      case 'b':
        return '\b';
      case 'f':
        return '\f';
      case 'a':
        return 0x07;
      case 'x':
        {
          int value = 0, digits = 0;
          while (position < text.length() && digits < 2) {
            int digit = Character.digit(text.charAt(position), 16);
            if (digit < 0) break;
            value = value * 16 + digit;
            position++;
            digits++;
          }
          return (char) (value & 0xff);
        }
      default:
        if (c >= '0' && c <= '9') {
          int value = c - '0', digits = 1;
          while (position < text.length() && digits < 3 && isDigit(text.charAt(position))) {
            value = value * 10 + text.charAt(position++) - '0';
            digits++;
          }
          return (char) (value & 0xff);
        }
        // \\ \' \" \? and, leniently, any other character stand for themselves.
        return c;
    }
  }

  private ScriptToken readNumber(int tokenLine, int crossed, boolean space) {
    int begin = position;
    char c = text.charAt(position);
    char next = position + 1 < text.length() ? text.charAt(position + 1) : 0;
    long value = 0;
    double real = 0;
    int subtype;
    if (c == '0' && (next == 'x' || next == 'X')) {
      position += 2;
      while (position < text.length() && Character.digit(text.charAt(position), 16) >= 0)
        value = value * 16 + Character.digit(text.charAt(position++), 16);
      subtype = ScriptToken.TT_HEX | ScriptToken.TT_INTEGER;
      real = value;
    } else if (c == '0' && (next == 'b' || next == 'B')) {
      position += 2;
      while (position < text.length()
          && (text.charAt(position) == '0' || text.charAt(position) == '1'))
        value = value * 2 + text.charAt(position++) - '0';
      subtype = ScriptToken.TT_BINARY | ScriptToken.TT_INTEGER;
      real = value;
    } else {
      boolean dot = false;
      while (position < text.length()) {
        char d = text.charAt(position);
        if (isDigit(d)) position++;
        else if (d == '.' && !dot) {
          dot = true;
          position++;
        } else break;
      }
      boolean exponent = false;
      if (position < text.length()
          && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
        int mark = position + 1;
        if (mark < text.length() && (text.charAt(mark) == '+' || text.charAt(mark) == '-')) mark++;
        if (mark < text.length() && isDigit(text.charAt(mark))) {
          position = mark;
          while (position < text.length() && isDigit(text.charAt(position))) position++;
          exponent = true;
        }
      }
      String digits = text.substring(begin, position);
      if (dot || exponent) {
        real = Double.parseDouble(digits.startsWith(".") ? "0" + digits : digits);
        value = (long) real;
        subtype = ScriptToken.TT_DECIMAL | ScriptToken.TT_FLOAT;
      } else if (digits.length() > 1
          && digits.charAt(0) == '0'
          && digits.chars().allMatch(d -> d < '8')) {
        for (int i = 1; i < digits.length(); i++) value = value * 8 + digits.charAt(i) - '0';
        real = value;
        subtype = ScriptToken.TT_OCTAL | ScriptToken.TT_INTEGER;
      } else {
        for (int i = 0; i < digits.length(); i++) value = value * 10 + digits.charAt(i) - '0';
        real = value;
        subtype = ScriptToken.TT_DECIMAL | ScriptToken.TT_INTEGER;
      }
    }
    // Integer suffixes (and a float's f suffix) belong to the number.
    while (position < text.length()) {
      char s = Character.toLowerCase(text.charAt(position));
      if (s == 'l' && (subtype & ScriptToken.TT_INTEGER) != 0) subtype |= ScriptToken.TT_LONG;
      else if (s == 'u' && (subtype & ScriptToken.TT_INTEGER) != 0)
        subtype |= ScriptToken.TT_UNSIGNED;
      else if (s == 'f' && (subtype & ScriptToken.TT_FLOAT) != 0) {
        // float suffix
      } else break;
      position++;
    }
    String spelled = text.substring(begin, position);
    checkLength(spelled.length());
    return new ScriptToken(
        ScriptToken.TT_NUMBER, subtype, spelled, value, real, tokenLine, crossed, space);
  }

  private void checkLength(int length) {
    if (length >= ScriptToken.MAX_LENGTH) throw error("token longer than MAX_TOKEN");
  }

  private ScriptException error(String message) {
    return new ScriptException(file, line, message);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isNameStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private static boolean isNamePart(char c) {
    return isNameStart(c) || isDigit(c);
  }
}
