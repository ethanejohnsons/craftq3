package dev.bluevista.craftq3.botlib.chat;

import static dev.bluevista.craftq3.botlib.chat.ChatLibrary.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Reply predicates and capture ownership, independently verified through the public chat API. */
final class ChatReplies {
  record Selection(Template template, List<String> variables) {}

  private ChatReplies() {}

  static Selection select(
      List<ReplyRule> rules,
      String text,
      String name,
      int gender,
      ToIntFunction<List<Template>> choose) {
    var work = new ChatMatcher.Work(1_000_000);
    var captures = ByteBuffer.allocate(ChatMatcher.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) > 255) throw new IllegalArgumentException("Chat reply requires byte text");
      captures.put(i, (byte) text.charAt(i));
    }
    for (int i = 0; i < 8; i++) captures.put(264 + i * 8, (byte) 255);
    Template selected = null;
    float priority = -1;
    for (int index = rules.size() - 1; index >= 0; index--) {
      work.use();
      var rule = rules.get(index);
      if (rule.priority() <= priority) continue;
      var candidate = copy(captures);
      boolean any = false, valid = true;
      for (int key = rule.conditions().size() - 1; key >= 0; key--) {
        work.use();
        var condition = rule.conditions().get(key);
        boolean matches;
        if (condition.key() instanceof WordKey word) {
          matches = containsWord(text, word.text(), work);
        } else if (condition.key() instanceof PatternKey pattern) {
          var attempt = copy(candidate);
          matches = ChatMatcher.matches(pattern.parts(), text, attempt, work);
          if (matches) candidate = attempt;
        } else {
          String special = ((SpecialKey) condition.key()).name();
          matches =
              switch (special) {
                case "name" -> containsName(text, name, work);
                case "it" -> gender == 0;
                case "female" -> gender == 1;
                case "male" -> gender == 2;
                default -> throw new IllegalArgumentException("Unknown reply key " + special);
              };
        }
        if (condition.constraint() == Constraint.ANY) any |= matches;
        else if (condition.constraint() == Constraint.REQUIRED ? !matches : matches) {
          valid = false;
          break;
        }
      }
      if (!valid || !any) continue;
      int choice = choose.applyAsInt(rule.messages());
      if (choice < 0 || choice >= rule.messages().size()) continue;
      selected = rule.messages().get(rule.messages().size() - 1 - choice);
      captures = candidate;
      priority = rule.priority();
    }
    if (selected == null) return null;
    var variables = new ArrayList<String>(8);
    for (int i = 0; i < 8; i++) variables.add(ChatMatcher.variable(captures, 0, i, 256));
    return new Selection(selected, List.copyOf(variables));
  }

  private static ByteBuffer copy(ByteBuffer original) {
    var result = ByteBuffer.allocate(ChatMatcher.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    return result.put(0, original, 0, ChatMatcher.BYTE_SIZE);
  }

  private static boolean containsWord(String text, String word, ChatMatcher.Work work) {
    int at = 0;
    while (at <= text.length()) {
      work.use();
      int end = at + word.length();
      if (end <= text.length() && (end == text.length() || delimiter(text.charAt(end)))) {
        int index = 0;
        while (index < word.length()) {
          work.use();
          if (fold(text.charAt(at + index)) != fold(word.charAt(index))) break;
          index++;
        }
        if (index == word.length()) return true;
      }
      // The observed scan advances once before seeking the next boundary. This preserves its
      // behavior for leading and repeated delimiters rather than normalizing the incoming text.
      if (++at >= text.length()) return false;
      while (at < text.length() && !delimiter(text.charAt(at))) {
        work.use();
        at++;
      }
      if (at == text.length()) return false;
      at++;
    }
    return false;
  }

  private static boolean delimiter(char c) {
    return c == ' ' || c == '.' || c == ',' || c == '!';
  }

  private static boolean containsName(String text, String name, ChatMatcher.Work work) {
    for (int at = 0; at <= text.length() - name.length(); at++) {
      work.use();
      int index = 0;
      while (index < name.length()) {
        work.use();
        if (fold(text.charAt(at + index)) != fold(name.charAt(index))) break;
        index++;
      }
      if (index == name.length()) return true;
    }
    return false;
  }

  private static char fold(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
  }
}
