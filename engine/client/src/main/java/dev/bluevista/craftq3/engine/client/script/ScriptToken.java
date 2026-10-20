package dev.bluevista.craftq3.engine.client.script;

import java.util.Objects;

/**
 * One token of a menu or HUD script, as {@code trap_PC_ReadToken} reports it ({@code pc_token_t}).
 *
 * @param type one of the {@code TT_*} constants
 * @param subtype for numbers the {@code TT_DECIMAL}.. flags, for punctuation its {@link
 *     ScriptPunctuation} id, for names and strings their length
 * @param text the token text; strings keep their quotes here and lose them in {@link #pcText()}
 * @param line the 1-based source line the token started on
 * @param linesCrossed line breaks between the previous token and this one
 * @param spaceBefore whether white space or a comment preceded the token
 */
public record ScriptToken(
    int type,
    int subtype,
    String text,
    long intValue,
    double floatValue,
    int line,
    int linesCrossed,
    boolean spaceBefore) {
  /** {@code TT_STRING}: a double-quoted string. */
  public static final int TT_STRING = 1;

  /** {@code TT_LITERAL}: a single-quoted character literal. */
  public static final int TT_LITERAL = 2;

  /** {@code TT_NUMBER}. */
  public static final int TT_NUMBER = 3;

  /** {@code TT_NAME}: an identifier. */
  public static final int TT_NAME = 4;

  /** {@code TT_PUNCTUATION}. */
  public static final int TT_PUNCTUATION = 5;

  /** Number subtype flags. */
  public static final int TT_DECIMAL = 0x0008,
      TT_HEX = 0x0100,
      TT_OCTAL = 0x0200,
      TT_BINARY = 0x0400,
      TT_FLOAT = 0x0800,
      TT_INTEGER = 0x1000,
      TT_LONG = 0x2000,
      TT_UNSIGNED = 0x4000;

  /** {@code MAX_TOKENLENGTH}: the longest token text, including its terminator. */
  public static final int MAX_LENGTH = 1024;

  public ScriptToken {
    Objects.requireNonNull(text);
  }

  public boolean is(int tokenType, String value) {
    return type == tokenType && text.equals(value);
  }

  public boolean isPunctuation(String value) {
    return type == TT_PUNCTUATION && text.equals(value);
  }

  public boolean isName(String value) {
    return type == TT_NAME && text.equals(value);
  }

  /** The text the guest sees: strings without their enclosing double quotes. */
  public String pcText() {
    if (type == TT_STRING && text.length() >= 2) return text.substring(1, text.length() - 1);
    return text;
  }

  /** A copy that appears at another source position (macro expansion). */
  public ScriptToken at(int newLine, int newLinesCrossed, boolean newSpaceBefore) {
    return new ScriptToken(
        type, subtype, text, intValue, floatValue, newLine, newLinesCrossed, newSpaceBefore);
  }

  /** A decimal integer token. */
  public static ScriptToken integer(long value, int line) {
    return new ScriptToken(
        TT_NUMBER,
        TT_DECIMAL | TT_INTEGER,
        Long.toString(value),
        value,
        value,
        line,
        0,
        true);
  }

  /** A decimal float token. */
  public static ScriptToken real(double value, int line) {
    String text = Double.toString(value);
    return new ScriptToken(
        TT_NUMBER, TT_DECIMAL | TT_FLOAT, text, (long) value, value, line, 0, true);
  }

  /** A string token; {@code contents} is without quotes. */
  public static ScriptToken string(String contents, int line) {
    return new ScriptToken(
        TT_STRING, contents.length(), '"' + contents + '"', 0, 0, line, 0, true);
  }

  @Override
  public String toString() {
    return text;
  }
}
