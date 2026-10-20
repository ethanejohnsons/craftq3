package dev.bluevista.craftq3.botlib.character;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable selected character values. Missing indices remain absent instead of becoming numbers.
 */
public record BotCharacter(String path, float skill, Map<Integer, Value> values) {
  public BotCharacter {
    Objects.requireNonNull(path);
    values = Map.copyOf(values);
  }

  public sealed interface Value permits IntegerValue, FloatValue, StringValue {}

  public record IntegerValue(int value) implements Value {}

  public record FloatValue(float value) implements Value {
    public FloatValue {
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite characteristic");
    }
  }

  public record StringValue(String value) implements Value {
    public StringValue {
      Objects.requireNonNull(value);
    }
  }
}
