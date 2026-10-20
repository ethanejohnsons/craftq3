package dev.bluevista.craftq3.engine.botlib;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Library variables ({@code LibVarSet}/{@code LibVarGet}): a case-insensitive string table the game
 * fills before setup ({@code maxclients}, {@code g_gametype}, {@code sv_mapChecksum}, ...) and
 * botlib reads with defaults.
 */
public final class BotLibVars {
  public static final int MAX_VARIABLES = 1024;
  public static final int MAX_VALUE_LENGTH = 8191;

  private final Map<String, String> values = new LinkedHashMap<>();
  private int version;

  /** False when the name is empty or the table is full; values are truncated like the original. */
  public boolean set(String name, String value) {
    if (name == null || name.isEmpty() || name.length() > 255 || value == null) return false;
    String key = name.toLowerCase(Locale.ROOT);
    if (!values.containsKey(key) && values.size() >= MAX_VARIABLES) return false;
    int nul = value.indexOf(0);
    if (nul >= 0) value = value.substring(0, nul);
    if (value.length() > MAX_VALUE_LENGTH) value = value.substring(0, MAX_VALUE_LENGTH);
    values.put(key, value);
    version++;
    return true;
  }

  /** The value, or null when unset. */
  public String get(String name) {
    return name == null ? null : values.get(name.toLowerCase(Locale.ROOT));
  }

  public String string(String name, String fallback) {
    String value = get(name);
    return value == null ? fallback : value;
  }

  /** {@code LibVarValue}: the variable read like {@code atof}, or {@code fallback} when unset. */
  public float value(String name, float fallback) {
    String value = get(name);
    return value == null ? fallback : BspEntities.atof(value);
  }

  public int integer(String name, int fallback) {
    String value = get(name);
    return value == null ? fallback : (int) BspEntities.atof(value);
  }

  public boolean flag(String name) {
    return value(name, 0) != 0;
  }

  /** Increments on every change, so services built from variables can tell they are stale. */
  public int version() {
    return version;
  }

  public void clear() {
    values.clear();
    version++;
  }
}
