package dev.bluevista.craftq3.server;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded engine-owned configstrings, with a generation for local or network snapshot consumers.
 */
public final class ConfigStrings {
  public static final int MAX_STRINGS = 1024, MAX_GAMESTATE_BYTES = 16000;
  private final String[] strings = new String[MAX_STRINGS];
  private int characters = 1, generation;

  public ConfigStrings() {
    Arrays.fill(strings, "");
  }

  public String get(int index) {
    check(index);
    return strings[index];
  }

  public void set(int index, String value) {
    check(index);
    if (value == null || value.indexOf('\0') >= 0 || value.chars().anyMatch(c -> c > 255))
      throw new IllegalArgumentException("Invalid configstring");
    int before = strings[index].isEmpty() ? 0 : strings[index].length() + 1;
    int after = value.isEmpty() ? 0 : value.length() + 1;
    if (characters - before + after > MAX_GAMESTATE_BYTES)
      throw new IllegalStateException("Q3 gamestate configstrings exceed 16000 bytes");
    if (!strings[index].equals(value)) {
      characters += after - before;
      strings[index] = value;
      generation++;
    }
  }

  public int generation() {
    return generation;
  }

  public Map<Integer, String> snapshot() {
    Map<Integer, String> result = new LinkedHashMap<>();
    for (int i = 0; i < strings.length; i++) if (!strings[i].isEmpty()) result.put(i, strings[i]);
    return java.util.Collections.unmodifiableMap(result);
  }

  private static void check(int index) {
    if (index < 0 || index >= MAX_STRINGS)
      throw new IllegalArgumentException("Invalid configstring index " + index);
  }
}
