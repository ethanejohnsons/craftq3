package dev.bluevista.craftq3.engine.formats.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the BSP entity lump: {@code { "key" "value" ... }} blocks. Tokens follow the original text
 * parser: whitespace is any character up to space, line and block comments are skipped (an
 * unterminated block comment ends the text), quoted strings have no escapes, and unquoted words are
 * accepted. Text ends at the first NUL. Only structural damage (a missing brace or value) and
 * resource limits are rejected, since the game itself refuses such a lump.
 */
public final class EntityParser {
  public static final int MAX_TEXT_CHARS = 4 * 1024 * 1024;
  public static final int MAX_ENTITIES = 65_536;
  public static final int MAX_PAIRS_PER_ENTITY = 4096;
  public static final int MAX_TOKEN_CHARS = 8192;

  private final String text;
  private final int end;
  private int cursor;
  private int tokenStart;

  private EntityParser(String text) {
    this.text = text;
    int nul = text.indexOf('\0');
    this.end = nul < 0 ? text.length() : nul;
  }

  public static List<BspEntity> parse(String text) {
    if (text.length() > MAX_TEXT_CHARS)
      throw new EntityFormatException("Entity text exceeds 4 MiB");
    return new EntityParser(text).entities();
  }

  private List<BspEntity> entities() {
    List<BspEntity> entities = new ArrayList<>();
    String token;
    while ((token = next()) != null) {
      if (!token.equals("{")) throw error("Expected '{' but found '" + token + "'");
      entities.add(entity());
      if (entities.size() > MAX_ENTITIES) throw error("Too many entities");
    }
    return List.copyOf(entities);
  }

  private BspEntity entity() {
    List<String> pairs = new ArrayList<>();
    while (true) {
      String key = next();
      if (key == null) throw error("Unterminated entity");
      if (key.equals("}") && !quoted()) return new BspEntity(pairs);
      String value = next();
      if (value == null) throw error("Missing entity value for key '" + key + "'");
      if (value.equals("}") && !quoted()) throw error("Missing entity value for key '" + key + "'");
      pairs.add(key);
      pairs.add(value);
      if (pairs.size() / 2 > MAX_PAIRS_PER_ENTITY) throw error("Too many entity keys");
    }
  }

  private boolean quoted() {
    return text.charAt(tokenStart) == '"';
  }

  /** The next token, or null at the end of the text. */
  private String next() {
    if (!skipWhitespaceAndComments()) return null;
    tokenStart = cursor;
    char c = text.charAt(cursor);
    String token;
    if (c == '"') {
      int start = ++cursor;
      while (cursor < end && text.charAt(cursor) != '"') cursor++;
      token = text.substring(start, cursor);
      if (cursor < end) cursor++;
    } else if (c == '{' || c == '}') {
      cursor++;
      token = String.valueOf(c);
    } else {
      int start = cursor;
      while (cursor < end && text.charAt(cursor) > ' ') cursor++;
      token = text.substring(start, cursor);
    }
    if (token.length() > MAX_TOKEN_CHARS) throw error("Entity token too long");
    return token;
  }

  private boolean skipWhitespaceAndComments() {
    while (cursor < end) {
      char c = text.charAt(cursor);
      if (c <= ' ') {
        cursor++;
      } else if (text.startsWith("//", cursor)) {
        while (cursor < end && text.charAt(cursor) != '\n') cursor++;
      } else if (text.startsWith("/*", cursor)) {
        int close = text.indexOf("*/", cursor + 2);
        cursor = close < 0 || close >= end ? end : close + 2;
      } else {
        return true;
      }
    }
    return false;
  }

  private EntityFormatException error(String message) {
    return new EntityFormatException(message + " at entity text offset " + cursor);
  }
}
