package dev.bluevista.craftq3.botlib.script;

/** Native PC token metadata. String text is unquoted; literal text retains its single quotes. */
public record ScriptToken(
    int type, int subtype, int intValue, float floatValue, String text, SourceLocation location) {
  public static final int STRING = 1, LITERAL = 2, NUMBER = 3, NAME = 4, PUNCTUATION = 5;
  public static final int DECIMAL = 8, HEX = 0x100, OCTAL = 0x200, BINARY = 0x400;
  public static final int FLOAT = 0x800, INTEGER = 0x1000, LONG = 0x2000, UNSIGNED = 0x4000;

  public ScriptToken {
    if (type < STRING
        || type > PUNCTUATION
        || text == null
        || location == null
        || !Float.isFinite(floatValue)) throw new IllegalArgumentException("Invalid script token");
  }

  ScriptToken at(SourceLocation source) {
    return new ScriptToken(type, subtype, intValue, floatValue, text, source);
  }
}
