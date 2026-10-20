package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.formats.entity.EntityParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The map's entities as botlib's own BSP entity table sees them: keys are case-sensitive and a
 * repeated key keeps its last value, because the original prepends each pair to the entity's list
 * and returns the first match. Entity numbers start at 1 ({@link #next} and {@link #value}), as the
 * {@code AAS_NextBSPEntity} cursor does. Numeric values parse like {@code atoi}, {@code atof} and
 * {@code sscanf}: leniently, reading the longest valid prefix.
 */
public final class BspEntities {
  private static final Pattern FLOAT_PREFIX =
      Pattern.compile("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

  private final List<Map<String, String>> entities;

  public BspEntities(List<Map<String, String>> entities) {
    this.entities = entities.stream().map(Map::copyOf).toList();
  }

  /** Parses an entity lump. Malformed text yields the entities read before the problem. */
  public static BspEntities parse(String text) {
    List<BspEntity> parsed;
    try {
      parsed = EntityParser.parse(text);
    } catch (RuntimeException malformed) {
      parsed = List.of();
    }
    var result = new ArrayList<Map<String, String>>(parsed.size());
    for (BspEntity entity : parsed) {
      var pairs = new HashMap<String, String>();
      for (int i = 0; i < entity.size(); i++) pairs.put(entity.key(i), entity.value(i));
      result.add(pairs);
    }
    return new BspEntities(result);
  }

  /** Entities in file order (index 0 is entity number 1). */
  public List<Map<String, String>> list() {
    return entities;
  }

  public int count() {
    return entities.size();
  }

  /** The entity number after {@code number}, or 0 past the end ({@code AAS_NextBSPEntity}). */
  public int next(int number) {
    int next = number + 1;
    return next >= 1 && next <= entities.size() ? next : 0;
  }

  /** The value of {@code key} on entity {@code number}, empty if either is absent. */
  public Optional<String> value(int number, String key) {
    if (number < 1 || number > entities.size()) return Optional.empty();
    return Optional.ofNullable(entities.get(number - 1).get(key));
  }

  public Optional<Float> floatValue(int number, String key) {
    return value(number, key).map(BspEntities::atof);
  }

  public Optional<Integer> intValue(int number, String key) {
    return value(number, key).map(BspEntities::atoi);
  }

  public Optional<Vec3f> vectorValue(int number, String key) {
    return value(number, key).map(BspEntities::vector);
  }

  /** C {@code atoi}: optional whitespace and sign, then digits; anything else stops the parse. */
  public static int atoi(String text) {
    int i = 0, length = text.length();
    while (i < length && Character.isWhitespace(text.charAt(i))) i++;
    boolean negative = false;
    if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-'))
      negative = text.charAt(i++) == '-';
    long value = 0;
    while (i < length && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
      value = value * 10 + (text.charAt(i++) - '0');
      if (value > Integer.MAX_VALUE + 1L) value = Integer.MAX_VALUE + 1L;
    }
    long signed = negative ? -value : value;
    return Math.clamp(signed, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }

  /** C {@code atof} narrowed to float: the longest decimal prefix, or zero. */
  public static float atof(String text) {
    var matcher = FLOAT_PREFIX.matcher(text.stripLeading());
    return matcher.find() ? (float) Double.parseDouble(matcher.group()) : 0;
  }

  /** {@code sscanf("%f %f %f")}: up to three leading numbers; missing components stay zero. */
  public static Vec3f vector(String text) {
    float[] values = new float[3];
    String rest = text;
    for (int i = 0; i < 3; i++) {
      rest = rest.stripLeading();
      var matcher = FLOAT_PREFIX.matcher(rest);
      if (!matcher.find()) break;
      values[i] = (float) Double.parseDouble(matcher.group());
      rest = rest.substring(matcher.end());
    }
    return new Vec3f(values[0], values[1], values[2]);
  }
}
