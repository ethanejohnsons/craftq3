package dev.bluevista.craftq3.botlib.script;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Bounded Q3 script lexer, including original punctuation numbers and decoded string escapes. */
public final class ScriptLexer {
  // These numeric positions are public Q3 ABI constants, not an engine implementation.
  private static final List<String> PUNCTUATION =
      List.of(
          ">>=", "<<=", "...", "##", "&&", "||", ">=", "<=", "==", "!=", "*=", "/=", "%=", "+=",
          "-=", "++", "--", "&=", "|=", "^=", ">>", "<<", "->", "::", ".*", "*", "/", "%", "+", "-",
          "=", "&", "|", "^", "~", "!", ">", "<", ".", ",", ";", ":", "?", "(", ")", "{", "}", "[",
          "]", "\\", "#", "$");

  record Lexeme(ScriptToken token, String spelling, boolean space, int logicalLine) {
    String text() {
      return token.text();
    }

    SourceLocation location() {
      return token.location();
    }

    Lexeme at(SourceLocation source) {
      return new Lexeme(token.at(source), spelling, space, logicalLine);
    }
  }

  private final String path, text;
  private final int[] physicalLines, physicalColumns;
  private final ScriptLimits limits;
  private int position, line = 1, column = 1, logicalLine = 1;

  private ScriptLexer(String path, String text, ScriptLimits limits) {
    this.path = path;
    this.limits = limits;
    physicalLines = new int[text.length() + 1];
    physicalColumns = new int[text.length() + 1];
    var logical = new StringBuilder(text.length());
    int sourceLine = 1, sourceColumn = 1;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\'
          && i + 1 < text.length()
          && (text.charAt(i + 1) == '\r' || text.charAt(i + 1) == '\n')) {
        i++;
        if (text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        sourceLine++;
        sourceColumn = 1;
        continue;
      }
      physicalLines[logical.length()] = sourceLine;
      physicalColumns[logical.length()] = sourceColumn;
      logical.append(c);
      if (c == '\r' || c == '\n' && (i == 0 || text.charAt(i - 1) != '\r')) {
        sourceLine++;
        sourceColumn = 1;
      } else if (c != '\n') sourceColumn++;
    }
    physicalLines[logical.length()] = sourceLine;
    physicalColumns[logical.length()] = sourceColumn;
    this.text = logical.toString();
  }

  public static List<ScriptToken> tokenize(String virtualPath, String text) throws ScriptException {
    return lex(new VirtualPath(virtualPath).value(), text, ScriptLimits.DEFAULT).stream()
        .map(Lexeme::token)
        .toList();
  }

  static List<Lexeme> lex(String path, String text, ScriptLimits limits) throws ScriptException {
    if (text.length() > limits.maxFileBytes())
      throw new ScriptException(new SourceLocation(path, 1, 1), "Source byte budget exceeded");
    return new ScriptLexer(path, text, limits).scan();
  }

  private List<Lexeme> scan() throws ScriptException {
    if (text.length() > limits.maxFileBytes()) throw failure("Source byte budget exceeded");
    var result = new ArrayList<Lexeme>();
    while (true) {
      int previous = position;
      whitespace();
      if (position == text.length()) return List.copyOf(result);
      boolean space = previous != position;
      int start = position, logical = logicalLine;
      SourceLocation source = location();
      char first = peek();
      ScriptToken token;
      if (first == '"' || first == '\'') token = quoted(source, first);
      else if (digit(first) || (first == '.' && digit(ahead(1)))) token = number(source);
      else if (nameStart(first)) {
        advance();
        while (nameStart(peek()) || digit(peek())) advance();
        String name = text.substring(start, position);
        token = new ScriptToken(ScriptToken.NAME, name.length(), 0, 0, name, source);
      } else {
        String punctuation = null;
        for (String candidate : PUNCTUATION)
          if (text.startsWith(candidate, position)
              && (punctuation == null || candidate.length() > punctuation.length()))
            punctuation = candidate;
        if (punctuation == null)
          throw failure("Unrecognized source character U+" + Integer.toHexString(first));
        for (int i = 0; i < punctuation.length(); i++) advance();
        token =
            new ScriptToken(
                ScriptToken.PUNCTUATION,
                PUNCTUATION.indexOf(punctuation) + 1,
                0,
                0,
                punctuation,
                source);
      }
      if (position - start > limits.maxTokenChars()
          || token.text().length() > limits.maxTokenChars())
        throw new ScriptException(source, "Token length budget exceeded");
      result.add(new Lexeme(token, text.substring(start, position), space, logical));
      if (result.size() > limits.maxTokens()) throw failure("Source token budget exceeded");
    }
  }

  private void whitespace() throws ScriptException {
    boolean more = true;
    while (more && position < text.length()) {
      if (peek() == '\0') throw failure("Embedded NUL in script");
      if (Character.isWhitespace(peek())) advance();
      else if (peek() == '\\' && (ahead(1) == '\n' || ahead(1) == '\r')) {
        int logical = logicalLine;
        advance();
        advance();
        logicalLine = logical;
      } else if (text.startsWith("//", position)) {
        while (position < text.length() && peek() != '\n' && peek() != '\r') advance();
      } else if (text.startsWith("/*", position)) {
        SourceLocation start = location();
        advance();
        advance();
        while (position < text.length() && !text.startsWith("*/", position)) advance();
        if (position == text.length())
          throw new ScriptException(start, "Unterminated block comment");
        advance();
        advance();
      } else more = false;
    }
  }

  private ScriptToken quoted(SourceLocation source, char quote) throws ScriptException {
    advance();
    var value = new StringBuilder();
    while (position < text.length() && peek() != quote) {
      if (peek() == '\n' || peek() == '\r' || peek() == '\0')
        throw new ScriptException(source, "Newline or NUL inside quoted token");
      char c = peek();
      advance();
      if (c == '\\') {
        if (position == text.length()) throw new ScriptException(source, "Unterminated escape");
        c = peek();
        advance();
        c =
            switch (c) {
              case 'n' -> '\n';
              case 'r' -> '\r';
              case 't' -> '\t';
              case 'v' -> 11;
              case 'b' -> '\b';
              case 'f' -> '\f';
              case 'a' -> 7;
              case '\\', '\'', '"', '?' -> c;
              case 'x' -> escapedNumber(16, source);
              default -> {
                if (digit(c)) {
                  position--;
                  column--;
                  yield escapedNumber(10, source);
                }
                throw new ScriptException(source, "Unsupported escape \\" + c);
              }
            };
      }
      if (c == 0 || c > 255)
        throw new ScriptException(source, "Character cannot fit an 8-bit PC token string");
      value.append(c);
      if (value.length() + 2 > limits.maxTokenChars())
        throw new ScriptException(source, "Token length budget exceeded");
    }
    if (position == text.length()) throw new ScriptException(source, "Unterminated quoted token");
    advance();
    if (quote == '\'') {
      if (value.length() != 1)
        throw new ScriptException(source, "Literal must contain one character");
      return new ScriptToken(ScriptToken.LITERAL, value.charAt(0), 0, 0, "'" + value + "'", source);
    }
    return new ScriptToken(ScriptToken.STRING, value.length() + 2, 0, 0, value.toString(), source);
  }

  private char escapedNumber(int radix, SourceLocation source) throws ScriptException {
    int value = 0, count = 0;
    while (Character.digit(peek(), radix) >= 0) {
      value = value * radix + Character.digit(peek(), radix);
      if (value > 255) throw new ScriptException(source, "Escape is outside the byte range");
      advance();
      count++;
    }
    if (count == 0) throw new ScriptException(source, "Empty numeric escape");
    return (char) value;
  }

  private ScriptToken number(SourceLocation source) throws ScriptException {
    int start = position, radix = 10, base;
    boolean floating = false;
    if (peek() == '0'
        && (ahead(1) == 'x' || ahead(1) == 'X' || ahead(1) == 'b' || ahead(1) == 'B')) {
      radix = Character.toLowerCase(ahead(1)) == 'x' ? 16 : 2;
      base = radix == 16 ? ScriptToken.HEX : ScriptToken.BINARY;
      advance();
      advance();
      int digits = position;
      while (Character.digit(peek(), radix) >= 0) advance();
      if (position == digits) throw new ScriptException(source, "Missing radix digits");
    } else {
      while (digit(peek())) advance();
      if (peek() == '.') {
        floating = true;
        advance();
        while (digit(peek())) advance();
      }
      if (peek() == 'e' || peek() == 'E') {
        floating = true;
        advance();
        if (peek() == '+' || peek() == '-') advance();
        int digits = position;
        while (digit(peek())) advance();
        if (digits == position) throw new ScriptException(source, "Missing exponent digits");
      }
      String digits = text.substring(start, position);
      // Q3 tags leading-zero numbers as octal unless an 8 or 9 occurs, even for 0.x floats.
      base =
          digits.charAt(0) == '0' && digits.indexOf('8') < 0 && digits.indexOf('9') < 0
              ? ScriptToken.OCTAL
              : ScriptToken.DECIMAL;
      if (!floating && base == ScriptToken.OCTAL) radix = 8;
    }
    String value = text.substring(start, position);
    if (value.length() > limits.maxTokenChars())
      throw new ScriptException(source, "Numeric token length budget exceeded");
    int subtype = base | (floating ? ScriptToken.FLOAT : ScriptToken.INTEGER);
    for (int count = 0; count < 2; count++) {
      char suffix = Character.toLowerCase(peek());
      if (suffix == 'l' && (subtype & ScriptToken.LONG) == 0) subtype |= ScriptToken.LONG;
      else if (suffix == 'u' && !floating && (subtype & ScriptToken.UNSIGNED) == 0)
        subtype |= ScriptToken.UNSIGNED;
      else break;
      advance();
    }
    try {
      if (floating) {
        float number = decimalFloat(value);
        if (!Float.isFinite(number)) throw new NumberFormatException();
        return new ScriptToken(ScriptToken.NUMBER, subtype, (int) number, number, value, source);
      }
      String digits =
          (base == ScriptToken.HEX || base == ScriptToken.BINARY) ? value.substring(2) : value;
      var number = new BigInteger(digits, radix);
      if (number.bitLength() > 32) throw new NumberFormatException();
      return new ScriptToken(
          ScriptToken.NUMBER, subtype, number.intValue(), number.floatValue(), value, source);
    } catch (NumberFormatException error) {
      throw new ScriptException(source, "Numeric token exceeds the 32-bit PC ABI");
    }
  }

  static Lexeme number(int value, SourceLocation source) {
    String text = Integer.toString(value);
    return new Lexeme(
        new ScriptToken(
            ScriptToken.NUMBER,
            ScriptToken.DECIMAL | ScriptToken.INTEGER,
            value,
            value,
            text,
            source),
        text,
        false,
        1);
  }

  /**
   * Fixed-point tokens expose Q3's digit-by-digit float rounding, observable through PC token
   * values. For example, the separately rounded tenths and hundredths in 0.45 sum to 0.45000002f.
   */
  private static float decimalFloat(String text) {
    if (text.indexOf('e') >= 0 || text.indexOf('E') >= 0) return Float.parseFloat(text);
    int dot = text.indexOf('.');
    float value = 0;
    int wholeEnd = dot < 0 ? text.length() : dot;
    for (int i = 0; i < wholeEnd; i++) value = (float) ((double) value * 10 + text.charAt(i) - '0');
    float divisor = 10;
    for (int i = wholeEnd + 1; i < text.length(); i++) {
      value += (text.charAt(i) - '0') / divisor;
      divisor *= 10;
    }
    return value;
  }

  static Lexeme string(String value, SourceLocation source) {
    String spelling = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    return new Lexeme(
        new ScriptToken(ScriptToken.STRING, value.length() + 2, 0, 0, value, source),
        spelling,
        false,
        1);
  }

  private SourceLocation location() {
    return new SourceLocation(path, physicalLines[position], physicalColumns[position]);
  }

  private ScriptException failure(String message) {
    return new ScriptException(location(), message);
  }

  private char peek() {
    return ahead(0);
  }

  private char ahead(int distance) {
    return position + distance < text.length() ? text.charAt(position + distance) : '\0';
  }

  private void advance() {
    char c = text.charAt(position++);
    if (c == '\r') {
      if (position < text.length() && text.charAt(position) == '\n') position++;
      line++;
      logicalLine++;
      column = 1;
    } else if (c == '\n') {
      line++;
      logicalLine++;
      column = 1;
    } else column++;
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean nameStart(char c) {
    return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }
}
