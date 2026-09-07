package dev.bluevista.craftq3.assets.md3;

/** Comment removal preserves line numbers and does not interpret backslashes as Java escapes. */
final class ModelText {
  private ModelText() {}

  static String withoutComments(String text) throws Md3FormatException {
    StringBuilder result = new StringBuilder(text.length());
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') quoted = !quoted;
      if (!quoted && c == '/' && i + 1 < text.length()) {
        if (text.charAt(i + 1) == '/') {
          while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
          if (i < text.length()) result.append(text.charAt(i));
          continue;
        }
        if (text.charAt(i + 1) == '*') {
          result.append(' ');
          int end = text.indexOf("*/", i + 2);
          if (end < 0) throw new Md3FormatException("Unterminated model-text block comment");
          for (int j = i + 2; j < end; j++) {
            if (text.charAt(j) == '\n' || text.charAt(j) == '\r') result.append(text.charAt(j));
          }
          i = end + 1;
          continue;
        }
      }
      if (c == 0 || (c < 32 && !Character.isWhitespace(c)))
        throw new Md3FormatException("Control character in model text");
      result.append(c);
    }
    if (quoted) throw new Md3FormatException("Unterminated model-text quoted string");
    return result.toString();
  }
}
