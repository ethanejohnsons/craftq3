package dev.bluevista.craftq3.engine.formats.md3;

import dev.bluevista.craftq3.engine.formats.QPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code .skin} files: {@code surface,shader} pairs read with the original renderer's comma
 * tokenizer (whitespace and commas separate words, quotes group, comments are skipped). Surface
 * names are lower-cased, any name containing {@code tag_} is skipped, and the first pair for a
 * surface wins. Nothing is rejected except over-long input; a missing shader is noted.
 */
public final class SkinParser {
  public static final int MAX_CHARS = 1024 * 1024;
  public static final int MAX_SURFACES = 4096;

  private SkinParser() {}

  /** Surface-to-shader mapping in file order. */
  public record Skin(Map<String, String> surfaces, List<String> diagnostics) {
    public Skin {
      surfaces = Map.copyOf(surfaces);
      diagnostics = List.copyOf(diagnostics);
    }

    /**
     * The shader for an MD3 surface name. Like the original model loader, a trailing {@code _N}
     * level-of-detail suffix of the surface name is ignored when the full name has no entry.
     */
    public Optional<String> shaderForSurface(String surface) {
      String name = surface.toLowerCase(Locale.ROOT);
      String shader = surfaces.get(name);
      if (shader == null && name.length() > 2 && name.charAt(name.length() - 2) == '_')
        shader = surfaces.get(name.substring(0, name.length() - 2));
      return Optional.ofNullable(shader);
    }
  }

  public static Skin parse(String text) {
    if (text == null || text.length() > MAX_CHARS)
      throw new Md3FormatException("Skin text exceeds the 1 MiB limit");
    int nul = text.indexOf('\0');
    CommaTokens tokens = new CommaTokens(nul < 0 ? text : text.substring(0, nul));
    Map<String, String> surfaces = new LinkedHashMap<>();
    List<String> diagnostics = new ArrayList<>();
    while (tokens.hasMore()) {
      String surface = tokens.next().toLowerCase(Locale.ROOT);
      if (surface.isEmpty()) break;
      tokens.skipComma();
      if (surface.contains("tag_")) continue;
      String shader = tokens.next();
      if (shader.isEmpty()) {
        diagnostics.add("Skin surface '" + surface + "' has no shader");
        continue;
      }
      surfaces.putIfAbsent(surface, QPath.normalize(shader));
      if (surfaces.size() > MAX_SURFACES)
        throw new Md3FormatException("Skin surface budget exceeded");
    }
    return new Skin(surfaces, diagnostics);
  }

  /** The renderer's comma-aware word splitter. */
  private static final class CommaTokens {
    private final String text;
    private int cursor;

    CommaTokens(String text) {
      this.text = text;
    }

    boolean hasMore() {
      return cursor < text.length();
    }

    void skipComma() {
      if (cursor < text.length() && text.charAt(cursor) == ',') cursor++;
    }

    String next() {
      skipWhitespaceAndComments();
      if (cursor >= text.length()) return "";
      char c = text.charAt(cursor);
      if (c == '"') {
        int start = ++cursor;
        while (cursor < text.length() && text.charAt(cursor) != '"') cursor++;
        String token = text.substring(start, cursor);
        if (cursor < text.length()) cursor++;
        return token;
      }
      int start = cursor++;
      while (cursor < text.length() && text.charAt(cursor) > ' ' && text.charAt(cursor) != ',')
        cursor++;
      return text.substring(start, cursor);
    }

    private void skipWhitespaceAndComments() {
      while (cursor < text.length()) {
        char c = text.charAt(cursor);
        if (c <= ' ') {
          cursor++;
        } else if (text.startsWith("//", cursor)) {
          while (cursor < text.length() && text.charAt(cursor) != '\n') cursor++;
        } else if (text.startsWith("/*", cursor)) {
          int close = text.indexOf("*/", cursor + 2);
          cursor = close < 0 ? text.length() : close + 2;
        } else {
          return;
        }
      }
    }
  }
}
