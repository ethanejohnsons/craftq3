package dev.bluevista.craftq3.engine.vmabi;

import dev.bluevista.craftq3.engine.vmabi.layout.GameStateLayout;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The engine's configstrings with the {@code gameState_t} budget: {@link #MAX_STRINGS} strings
 * whose non-empty values plus terminators, after the shared leading empty string, fit in {@link
 * GameStateLayout#STRING_DATA_BYTES}. A generation counter lets snapshot consumers notice changes.
 * Single owner thread.
 */
public final class ConfigStrings {
  /** {@code MAX_CONFIGSTRINGS}. */
  public static final int MAX_STRINGS = 1024;

  /** {@code CS_SERVERINFO}: the serverinfo cvars, owned by the engine. */
  public static final int CS_SERVERINFO = 0;

  /** {@code CS_SYSTEMINFO}: the systeminfo cvars, owned by the engine. */
  public static final int CS_SYSTEMINFO = 1;

  /** {@code RESERVED_CONFIGSTRINGS}: game modules own every index from here on. */
  public static final int RESERVED = 2;

  private final String[] strings = new String[MAX_STRINGS];
  private int characters = 1;
  private int generation;

  public ConfigStrings() {
    Arrays.fill(strings, "");
  }

  public String get(int index) {
    check(index);
    return strings[index];
  }

  /**
   * @throws IllegalArgumentException for an invalid index or a value with NUL or non-Latin-1
   *     characters
   * @throws IllegalStateException when the gamestate budget would be exceeded
   */
  public void set(int index, String value) {
    check(index);
    if (value == null || value.indexOf('\0') >= 0 || value.chars().anyMatch(c -> c > 255))
      throw new IllegalArgumentException("Invalid configstring " + index);
    int before = strings[index].isEmpty() ? 0 : strings[index].length() + 1;
    int after = value.isEmpty() ? 0 : value.length() + 1;
    if (characters - before + after > GameStateLayout.STRING_DATA_BYTES)
      throw new IllegalStateException(
          "Configstrings exceed the " + GameStateLayout.STRING_DATA_BYTES + "-byte gamestate");
    if (!strings[index].equals(value)) {
      characters += after - before;
      strings[index] = value;
      generation++;
    }
  }

  /** Bytes the gamestate string data uses, including the leading empty string. */
  public int characters() {
    return characters;
  }

  public int generation() {
    return generation;
  }

  /** Non-empty strings by index, ascending. */
  public Map<Integer, String> snapshot() {
    Map<Integer, String> result = new LinkedHashMap<>();
    for (int i = 0; i < strings.length; i++) if (!strings[i].isEmpty()) result.put(i, strings[i]);
    return Collections.unmodifiableMap(result);
  }

  private static void check(int index) {
    if (index < 0 || index >= MAX_STRINGS)
      throw new IllegalArgumentException("Invalid configstring index " + index);
  }
}
