package dev.bluevista.craftq3.botlib.chat;

import dev.bluevista.craftq3.botlib.chat.ChatLibrary.SynonymGroup;
import java.util.List;

/** Reply-variable canonicalization has different boundary/iteration rules from message synonyms. */
final class ChatReplyVariables {
  private ChatReplyVariables() {}

  static String normalize(String value, List<SynonymGroup> groups, int context) {
    var text = new StringBuilder(value);
    var work = new ChatMatcher.Work(1_000_000);
    for (int at = 0; at < text.length(); at++) {
      work.use();
      if (at != 0 && text.charAt(at - 1) > ' ') continue;
      groupsAtPosition:
      for (var group : groups) {
        work.use();
        if ((group.context() & context) == 0) continue;
        for (int index = 0; index < group.entries().size(); index++) {
          work.use();
          String word = group.entries().get(index).text();
          int end = at + word.length();
          if (end > text.length() || end < text.length() && !rightBoundary(text.charAt(end)))
            continue;
          int character = 0;
          while (character < word.length()) {
            work.use();
            if (fold(text.charAt(at + character)) != fold(word.charAt(character))) break;
            character++;
          }
          if (character != word.length()) continue;
          if (index != 0) {
            String replacement = group.entries().getFirst().text();
            if ((long) text.length() - word.length() + replacement.length() >= 256)
              throw new IllegalStateException("Reply variable expansion exceeds 255 bytes");
            text.replace(at, end, replacement);
            break groupsAtPosition;
          }
          // Canonical entries protect aliases in their group but allow subsequent groups.
          break;
        }
      }
    }
    return text.toString();
  }

  private static boolean rightBoundary(char c) {
    return c == ' ' || c == '.' || c == ',' || c == '!';
  }

  private static char fold(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
  }
}
