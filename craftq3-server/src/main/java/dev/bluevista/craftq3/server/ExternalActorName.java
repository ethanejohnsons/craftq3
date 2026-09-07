package dev.bluevista.craftq3.server;

import java.util.Objects;

/** Plain host entity labels encoded for legacy Quake userinfo, without embedded color commands. */
public final class ExternalActorName {
  private ExternalActorName() {}

  public static String clean(String name) {
    Objects.requireNonNull(name, "name");
    var result = new StringBuilder();
    for (int offset = 0; offset < Math.min(name.length(), 1024) && result.length() < 34; ) {
      int code = name.codePointAt(offset);
      offset += Character.charCount(code);
      char value;
      if (Character.isWhitespace(code) || Character.isISOControl(code)) value = ' ';
      else if (code > 255 || code == '^' || code == '\\' || code == '"' || code == ';') value = '?';
      else value = (char) code;
      if (value != ' ' || !result.isEmpty() && result.charAt(result.length() - 1) != ' ')
        result.append(value);
    }
    var cleaned = result.toString().strip();
    return cleaned.isEmpty() ? "Minecraft mob" : cleaned;
  }
}
