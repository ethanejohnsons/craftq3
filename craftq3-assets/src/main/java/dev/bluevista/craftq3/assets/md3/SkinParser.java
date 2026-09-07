package dev.bluevista.craftq3.assets.md3;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Surface-to-material mappings used by multipart Q3 player models. */
public final class SkinParser {
  private static final int MAX_CHARS = 1024 * 1024;
  private static final int MAX_SURFACES = 4096;

  private SkinParser() {}

  public record Skin(Map<String, String> surfaces) {
    public Skin {
      surfaces = Map.copyOf(surfaces);
    }

    public Optional<String> shaderForSurface(String surface) {
      String name = surface.toLowerCase(Locale.ROOT);
      String shader = surfaces.get(name);
      if (shader == null
          && name.length() > 2
          && name.charAt(name.length() - 2) == '_'
          && Character.isDigit(name.charAt(name.length() - 1))) {
        shader = surfaces.get(name.substring(0, name.length() - 2));
      }
      return Optional.ofNullable(shader);
    }
  }

  public static Skin parse(String text) throws Md3FormatException {
    if (text == null || text.length() > MAX_CHARS)
      throw new Md3FormatException("Skin text exceeds 1MiB limit");
    String clean = ModelText.withoutComments(text);
    Map<String, String> surfaces = new LinkedHashMap<>();
    int lineNumber = 0;
    for (String line : clean.split("\\R")) {
      lineNumber++;
      line = line.strip();
      if (line.isEmpty()) continue;
      int comma = line.indexOf(',');
      if (comma < 0 || comma != line.lastIndexOf(','))
        throw new Md3FormatException("Expected surface,shader at skin line " + lineNumber);
      String surface = unquote(line.substring(0, comma).strip()).toLowerCase(Locale.ROOT);
      String shader = unquote(line.substring(comma + 1).strip());
      if (surface.startsWith("tag_")) continue;
      if (surface.isEmpty()
          || surface.length() > 64
          || surface.chars().anyMatch(Character::isWhitespace)) {
        throw new Md3FormatException("Invalid skin surface name at line " + lineNumber);
      }
      if (shader.isEmpty())
        throw new Md3FormatException("Missing skin material at line " + lineNumber);
      try {
        shader = new VirtualPath(shader).value();
      } catch (IllegalArgumentException invalid) {
        throw new Md3FormatException("Invalid skin material path at line " + lineNumber);
      }
      // The first matching surface wins, matching the original ordered skin lookup.
      surfaces.putIfAbsent(surface, shader);
      if (surfaces.size() > MAX_SURFACES)
        throw new Md3FormatException("Skin surface budget exceeded");
    }
    return new Skin(surfaces);
  }

  private static String unquote(String value) throws Md3FormatException {
    if (value.startsWith("\"")) {
      if (value.length() < 2 || !value.endsWith("\""))
        throw new Md3FormatException("Unterminated skin string");
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
