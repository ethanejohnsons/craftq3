package dev.bluevista.craftq3.core.cvar;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Q3's backslash-delimited key/value wire representation. No filesystem or command interpretation.
 */
public final class InfoString {
  private InfoString() {}

  public static Map<String, String> parse(String text, int capacity) {
    if (capacity < 1 || text.length() >= capacity)
      throw new IllegalArgumentException("Info string too long");
    if (text.isEmpty()) return Map.of();
    if (!text.startsWith("\\"))
      throw new IllegalArgumentException("Info string must start with a separator");
    String[] fields = text.substring(1).split("\\\\", -1);
    if ((fields.length & 1) != 0) throw new IllegalArgumentException("Incomplete info string pair");
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < fields.length; i += 2) {
      check(fields[i]);
      check(fields[i + 1]);
      if (fields[i].isEmpty()) throw new IllegalArgumentException("Empty info key");
      values.put(fields[i], fields[i + 1]);
    }
    return java.util.Collections.unmodifiableMap(values);
  }

  public static String encode(Map<String, String> values, int capacity) {
    StringBuilder result = new StringBuilder();
    values.forEach(
        (key, value) -> {
          check(key);
          check(value);
          if (key.isEmpty()) throw new IllegalArgumentException("Empty info key");
          if (!value.isEmpty()) result.append('\\').append(key).append('\\').append(value);
        });
    if (capacity < 1 || result.length() >= capacity)
      throw new IllegalArgumentException("Info string too long");
    return result.toString();
  }

  public static void check(String value) {
    if (value == null) throw new IllegalArgumentException("Null info field");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 32 || c > 255 || c == 127 || c == '\\' || c == '"' || c == ';')
        throw new IllegalArgumentException("Invalid info string character");
    }
  }
}
