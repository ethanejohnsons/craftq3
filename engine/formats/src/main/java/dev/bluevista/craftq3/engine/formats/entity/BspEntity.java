package dev.bluevista.craftq3.engine.formats.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * One entity block as ordered key/value pairs. Duplicate keys are kept; lookups return the first
 * match and compare keys case-insensitively, as the original game's spawn-variable lookup does.
 */
public final class BspEntity {
  private final String[] pairs;

  BspEntity(List<String> keysAndValues) {
    if ((keysAndValues.size() & 1) != 0) throw new IllegalArgumentException("Unpaired entity key");
    this.pairs = keysAndValues.toArray(String[]::new);
  }

  /** Builds an entity from alternating keys and values. */
  public static BspEntity of(String... keysAndValues) {
    return new BspEntity(Arrays.asList(keysAndValues));
  }

  public int size() {
    return pairs.length / 2;
  }

  public String key(int index) {
    return pairs[index * 2];
  }

  public String value(int index) {
    return pairs[index * 2 + 1];
  }

  /** The first value whose key equals {@code key} ignoring case. */
  public Optional<String> get(String key) {
    for (int i = 0; i < pairs.length; i += 2) {
      if (pairs[i].equalsIgnoreCase(key)) return Optional.of(pairs[i + 1]);
    }
    return Optional.empty();
  }

  public String getOrDefault(String key, String fallback) {
    return get(key).orElse(fallback);
  }

  public Optional<String> classname() {
    return get("classname");
  }

  /** Every key in file order, duplicates included. */
  public List<String> keys() {
    List<String> keys = new ArrayList<>(size());
    for (int i = 0; i < pairs.length; i += 2) keys.add(pairs[i]);
    return List.copyOf(keys);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof BspEntity entity && Arrays.equals(pairs, entity.pairs);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(pairs);
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder("{");
    for (int i = 0; i < pairs.length; i += 2) {
      text.append(" \"").append(pairs[i]).append("\" \"").append(pairs[i + 1]).append('"');
    }
    return text.append(" }").toString();
  }
}
