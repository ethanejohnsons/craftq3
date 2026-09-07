package dev.bluevista.craftq3.botlib.character;

import dev.bluevista.craftq3.botlib.character.BotCharacter.*;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bounded character handles over a borrowed ScriptSources service; no game or bot decision rules.
 */
public final class BotCharacters implements AutoCloseable {
  public static final int MAX_CHARACTERISTICS = 80;
  public static final int MAX_HANDLES = 64;
  private static final String DEFAULT_PATH = "bots/default_c.c";
  private final ScriptSources sources;
  private final Consumer<String> diagnostics;
  private final Map<Integer, BotCharacter> characters = new LinkedHashMap<>();
  private int nextHandle = 1;
  private boolean reloadCharacters, closed;

  public BotCharacters(ScriptSources sources) {
    this(sources, unused -> {});
  }

  public BotCharacters(ScriptSources sources, Consumer<String> diagnostics) {
    this.sources = Objects.requireNonNull(sources);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  /** Zero is unavailable. Skill is clamped to [1,5]; malformed requests produce a diagnostic. */
  public synchronized int loadCharacter(String path, float requestedSkill) {
    open();
    if (!Float.isFinite(requestedSkill)) return failure("Non-finite character skill");
    try {
      path = new VirtualPath(path).value();
    } catch (IllegalArgumentException badPath) {
      return failure(badPath.getMessage());
    }
    // Accept either original botlib-relative paths or full virtual botfiles paths consistently.
    if (path.startsWith("botfiles/")) path = path.substring("botfiles/".length());
    float skill = Math.clamp(requestedSkill, 1, 5);
    if (skill == 1 || skill == 4 || skill == 5) return anchor(path, (int) skill, false);
    int cached = cached(path, skill);
    if (cached != 0) return cached;
    int lowerSkill = skill < 4 ? 1 : 4, upperSkill = skill < 4 ? 4 : 5;
    int lowerHandle = anchor(path, lowerSkill, false),
        upperHandle = anchor(path, upperSkill, false);
    if (lowerHandle == 0 || upperHandle == 0) return 0;
    BotCharacter lower = characters.get(lowerHandle), upper = characters.get(upperHandle);
    float fraction = (skill - lowerSkill) / (upperSkill - lowerSkill);
    var values = new HashMap<Integer, Value>();
    for (var entry : lower.values().entrySet()) {
      Value value = entry.getValue();
      if (value instanceof FloatValue low) {
        if (upper.values().get(entry.getKey()) instanceof FloatValue high) {
          float interpolated = low.value() + fraction * (high.value() - low.value());
          if (!Float.isFinite(interpolated))
            return failure("Interpolated character value is non-finite");
          values.put(entry.getKey(), new FloatValue(interpolated));
        }
      } else values.put(entry.getKey(), value);
    }
    // The native result inherits the lower source filename, including default-file fallback.
    return publish(new BotCharacter(lower.path(), skill, values));
  }

  private int anchor(String path, int skill, boolean defaultLoad) {
    if (!reloadCharacters || defaultLoad) {
      int cached = cached(path, skill);
      if (cached != 0) return cached;
    }
    int fallback = !path.equals(DEFAULT_PATH) ? anchor(DEFAULT_PATH, skill, true) : 0;
    try {
      var parsed = CharacterParser.read(sources, path, skill);
      if (parsed.isEmpty()) {
        diagnostics.accept("No skill " + skill + " in character " + path);
        return fallback;
      }
      var values = new HashMap<Integer, Value>();
      if (fallback != 0) values.putAll(characters.get(fallback).values());
      values.putAll(parsed.get());
      return publish(new BotCharacter(path, skill, values));
    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
      diagnostics.accept("Character " + path + " skill " + skill + ": " + failure.getMessage());
      return fallback;
    }
  }

  private int cached(String path, float skill) {
    for (var entry : characters.entrySet())
      if (entry.getValue().path().equals(path) && entry.getValue().skill() == skill)
        return entry.getKey();
    return 0;
  }

  private int publish(BotCharacter character) {
    if (characters.size() >= MAX_HANDLES || nextHandle <= 0)
      return failure("Character handle budget exhausted");
    int handle = nextHandle++;
    characters.put(handle, character);
    return handle;
  }

  /** Ordinary mode retains cached characters. Reload mode frees only the requested handle. */
  public synchronized void free(int handle) {
    open();
    if (!characters.containsKey(handle)) {
      diagnostics.accept("Invalid character handle " + handle);
      return;
    }
    if (reloadCharacters) characters.remove(handle);
  }

  public synchronized void setReloadCharacters(boolean enabled) {
    open();
    reloadCharacters = enabled;
  }

  public synchronized int cachedCount() {
    open();
    return characters.size();
  }

  public synchronized float characteristicFloat(int handle, int index) {
    Value value = value(handle, index);
    if (value instanceof FloatValue number) return number.value();
    if (value instanceof IntegerValue number) return number.value();
    if (value != null) diagnostics.accept("Characteristic " + index + " is not numeric");
    return 0;
  }

  public synchronized int characteristicInteger(int handle, int index) {
    Value value = value(handle, index);
    if (value instanceof IntegerValue number) return number.value();
    if (value instanceof FloatValue number) return (int) number.value();
    if (value != null) diagnostics.accept("Characteristic " + index + " is not numeric");
    return 0;
  }

  public synchronized float characteristicBoundedFloat(
      int handle, int index, float min, float max) {
    open();
    if (!Float.isFinite(min) || !Float.isFinite(max) || min > max)
      return failure("Invalid characteristic float bounds");
    return Math.clamp(characteristicFloat(handle, index), min, max);
  }

  public synchronized int characteristicBoundedInteger(int handle, int index, int min, int max) {
    open();
    if (min > max) return failure("Invalid characteristic integer bounds");
    return Math.clamp(characteristicInteger(handle, index), min, max);
  }

  public synchronized String characteristicString(int handle, int index) {
    Value value = value(handle, index);
    if (value instanceof StringValue text) return text.value();
    if (value != null) diagnostics.accept("Characteristic " + index + " is not a string");
    return "";
  }

  public synchronized String characteristicString(int handle, int index, int bufferBytes) {
    open();
    if (bufferBytes < 0 || bufferBytes > 1_048_576)
      throw new IllegalArgumentException("Invalid characteristic string buffer size");
    if (bufferBytes == 0) return "";
    String text = characteristicString(handle, index);
    return text.substring(0, Math.min(text.length(), bufferBytes - 1));
  }

  private Value value(int handle, int index) {
    open();
    BotCharacter character = characters.get(handle);
    if (character == null) {
      failure("Invalid character handle " + handle);
      return null;
    }
    if (index < 0 || index >= MAX_CHARACTERISTICS) {
      failure("Invalid characteristic index " + index);
      return null;
    }
    Value value = character.values().get(index);
    if (value == null) failure("Uninitialized characteristic " + index);
    return value;
  }

  private int failure(String message) {
    diagnostics.accept(message);
    return 0;
  }

  private void open() {
    if (closed) throw new IllegalStateException("Character service is closed");
  }

  @Override
  public synchronized void close() {
    characters.clear();
    closed = true;
  }
}
