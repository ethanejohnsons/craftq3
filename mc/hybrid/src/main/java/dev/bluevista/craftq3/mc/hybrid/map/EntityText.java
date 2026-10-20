package dev.bluevista.craftq3.mc.hybrid.map;

import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import java.util.List;
import java.util.Locale;

/** Writes entity blocks in the BSP entity-lump syntax the game's spawn parser reads. */
public final class EntityText {
  /** {@code MAX_TOKEN_CHARS} less the terminator: longer keys or values do not survive parsing. */
  public static final int MAX_TOKEN = 1023;

  private EntityText() {}

  public static String write(List<BspEntity> entities) {
    StringBuilder out = new StringBuilder(entities.size() * 64);
    for (BspEntity entity : entities) append(out, entity);
    return out.toString();
  }

  /** Appends one entity block. */
  public static void append(StringBuilder out, BspEntity entity) {
    out.append("{\n");
    for (int i = 0; i < entity.size(); i++)
      out.append('"')
          .append(checked(entity.key(i)))
          .append("\" \"")
          .append(checked(entity.value(i)))
          .append("\"\n");
    out.append("}\n");
  }

  /** A Quake vector value: integers print without a fraction, as map compilers write them. */
  public static String vector(float x, float y, float z) {
    return number(x) + " " + number(y) + " " + number(z);
  }

  public static String number(float value) {
    if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite entity number");
    if (value == Math.rint(value) && Math.abs(value) < 1e9f) return Long.toString((long) value);
    return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "");
  }

  private static String checked(String token) {
    if (token.length() > MAX_TOKEN)
      throw new IllegalArgumentException("Entity token longer than " + MAX_TOKEN + " characters");
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '"' || c == '\n' || c == '\r' || c == 0)
        throw new IllegalArgumentException("Entity token contains a quote or line break");
    }
    return token;
  }
}
