package dev.bluevista.craftq3.botlib.chat;

import java.util.List;
import java.util.function.DoubleSupplier;

/** Bounded synonym substitution, independently specified by native input/output probes. */
final class ChatSynonyms {
  private ChatSynonyms() {}

  static String apply(
      String text,
      List<ChatLibrary.SynonymGroup> groups,
      int context,
      boolean weighted,
      DoubleSupplier random) {
    for (int i = 0; i < groups.size(); i++) {
      var group = groups.get(i);
      if ((group.context() & context) == 0) continue;
      int selected = 0;
      if (weighted) {
        float sum = 0;
        for (var entry : group.entries()) sum += entry.weight();
        if (!Float.isFinite(sum)) throw new IllegalStateException("Synonym weight sum overflow");
        float chosen = (float) random.getAsDouble() * sum;
        if (chosen <= 0) continue;
        for (int j = 0; j < group.entries().size(); j++) {
          chosen -= group.entries().get(j).weight();
          if (chosen <= 0) {
            selected = j;
            break;
          }
        }
      }
      String replacement = group.entries().get(selected).text();
      for (int j = 0; j < group.entries().size(); j++) {
        if (j != selected) text = replaceWords(text, group.entries().get(j).text(), replacement);
      }
    }
    return text;
  }

  static String replaceWords(String text, String word, String replacement) {
    var out = new StringBuilder(text);
    var protectedCharacters = new StringBuilder("0".repeat(text.length()));
    int search = 0;
    while (search < text.length()) {
      int found = find(text, replacement, search);
      if (found < 0) break;
      for (int i = found; i < found + replacement.length(); i++)
        protectedCharacters.setCharAt(i, '1');
      search = found + replacement.length() + 1;
    }
    int at = 0;
    while (at < out.length()) {
      int end = at + word.length();
      if (end <= out.length()
          && matches(out, at, word)
          && (end == out.length() || delimiter(out.charAt(end)))) {
        if (protectedCharacters.charAt(at) != '1') {
          if (out.length() - word.length() + replacement.length() >= 256)
            throw new IllegalStateException("Synonym expansion exceeds 255 bytes");
          out.replace(at, end, replacement);
          protectedCharacters.replace(at, end, "0".repeat(replacement.length()));
        }
        at += replacement.length();
      }
      // The observed scanner advances once before seeking its next delimiter. In particular,
      // replacing a word followed by one space skips the immediately following word.
      at++;
      while (at < out.length() && !delimiter(out.charAt(at))) at++;
      if (at < out.length()) at++;
    }
    return out.toString();
  }

  private static int find(String text, String word, int at) {
    while (at < text.length()) {
      int end = at + word.length();
      if (end <= text.length()
          && matches(text, at, word)
          && (end == text.length() || delimiter(text.charAt(end)))) return at;
      at++;
      while (at < text.length() && !delimiter(text.charAt(at))) at++;
      if (at < text.length()) at++;
    }
    return -1;
  }

  private static boolean matches(CharSequence text, int at, String word) {
    for (int i = 0; i < word.length(); i++)
      if (lower(text.charAt(at + i)) != lower(word.charAt(i))) return false;
    return true;
  }

  private static char lower(char value) {
    return value >= 'A' && value <= 'Z' ? (char) (value + 'a' - 'A') : value;
  }

  private static boolean delimiter(char value) {
    return value == ' ' || value == '.' || value == ',' || value == '!';
  }
}
