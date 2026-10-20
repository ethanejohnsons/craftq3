package dev.bluevista.craftq3.assets.bsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Q3 entity key/value blocks. No Java-style escape processing of Q3 quoted strings. */
public final class EntityParser {
  private final String text;
  private int cursor;

  private EntityParser(String text) {
    this.text = text;
  }

  public static List<Map<String, String>> parse(String text) throws BspFormatException {
    if (text.length() > 4 * 1024 * 1024) throw new BspFormatException("Entity text exceeds 4 MiB");
    return new EntityParser(text).read();
  }

  private List<Map<String, String>> read() throws BspFormatException {
    List<Map<String, String>> entities = new ArrayList<>();
    while (skip()) {
      expect('{');
      Map<String, String> entity = new LinkedHashMap<>();
      int pairs = 0;
      while (skip() && text.charAt(cursor) != '}') {
        String key = quoted();
        if (!skip()) throw error("Missing entity value");
        entity.put(key, quoted());
        if (++pairs > 4096) throw error("Too many entity keys");
      }
      expect('}');
      entities.add(Map.copyOf(entity));
      if (entities.size() > 65536) throw error("Too many entities");
    }
    return List.copyOf(entities);
  }

  private boolean skip() throws BspFormatException {
    while (cursor < text.length()) {
      char c = text.charAt(cursor);
      if (c == 0) {
        for (; cursor < text.length(); cursor++) {
          if (text.charAt(cursor) != 0 && !Character.isWhitespace(text.charAt(cursor)))
            throw error("Data after entity terminator");
        }
        return false;
      }
      if (Character.isWhitespace(c)) {
        cursor++;
        continue;
      }
      if (text.startsWith("//", cursor)) {
        while (cursor < text.length() && text.charAt(cursor) != '\n') cursor++;
        continue;
      }
      if (text.startsWith("/*", cursor)) {
        int end = text.indexOf("*/", cursor + 2);
        if (end < 0) throw error("Unterminated entity comment");
        cursor = end + 2;
        continue;
      }
      return true;
    }
    return false;
  }

  private String quoted() throws BspFormatException {
    expect('"');
    int start = cursor;
    while (cursor < text.length() && text.charAt(cursor) != '"' && text.charAt(cursor) != 0)
      cursor++;
    if (cursor - start > 8192) throw error("Entity token too long");
    String value = text.substring(start, cursor);
    expect('"');
    return value;
  }

  private void expect(char c) throws BspFormatException {
    if (cursor >= text.length() || text.charAt(cursor++) != c) throw error("Expected '" + c + "'");
  }

  private BspFormatException error(String message) {
    return new BspFormatException(message + " at entity byte " + cursor);
  }
}
