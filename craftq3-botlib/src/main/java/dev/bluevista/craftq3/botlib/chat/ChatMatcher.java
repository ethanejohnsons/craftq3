package dev.bluevista.craftq3.botlib.chat;

import dev.bluevista.craftq3.botlib.chat.ChatLibrary.Alternatives;
import dev.bluevista.craftq3.botlib.chat.ChatLibrary.Capture;
import dev.bluevista.craftq3.botlib.chat.ChatLibrary.MatchRule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/** Bounded sequential byte-string pattern matching over original immutable chat rules. */
public final class ChatMatcher {
  public static final int BYTE_SIZE = 328, MAX_TEXT = 255;
  private final List<MatchRule> rules;
  private final int comparisonBudget;

  public ChatMatcher(List<MatchRule> rules) {
    this(rules, 1_000_000);
  }

  public ChatMatcher(List<MatchRule> rules, int comparisonBudget) {
    this.rules = List.copyOf(rules);
    if (comparisonBudget < 1) throw new IllegalArgumentException("Invalid chat matching budget");
    this.comparisonBudget = comparisonBudget;
  }

  /**
   * Preserves opaque padding, unused lengths, and native partial capture writes on a failed rule.
   */
  public boolean find(String input, int context, ByteBuffer output, int offset) {
    Objects.requireNonNull(input);
    var data = slice(output, offset);
    int zero = input.indexOf(0), length = Math.min(input.length(), MAX_TEXT);
    if (zero >= 0) length = Math.min(length, zero);
    String text = input.substring(0, length);
    for (int i = 0; i < text.length(); i++)
      if (text.charAt(i) > 255)
        throw new IllegalArgumentException("Chat matching requires byte text");
    // Work on a private copy so malformed input or budget exhaustion cannot publish half a record.
    var changed = ByteBuffer.allocate(BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    changed.put(0, data, 0, BYTE_SIZE);
    for (int i = 0; i < 256; i++) changed.put(i, i < length ? (byte) text.charAt(i) : 0);
    var work = new Work(comparisonBudget);
    boolean found = false;
    for (var rule : rules) {
      work.use();
      if ((rule.context() & context) == 0) continue;
      for (int i = 0; i < 8; i++) changed.put(264 + i * 8, (byte) 255);
      if (matches(rule.parts(), text, changed, work)) {
        changed.putInt(256, rule.type()).putInt(260, rule.subtype());
        found = true;
        break;
      }
    }
    data.put(0, changed, 0, BYTE_SIZE);
    return found;
  }

  static boolean matches(
      List<ChatLibrary.MatchPart> parts, String text, ByteBuffer result, Work work) {
    int cursor = 0, pending = -1;
    for (var part : parts) {
      work.use();
      if (part instanceof Capture capture) {
        if (pending >= 0)
          throw new IllegalArgumentException(
              "Adjacent chat captures are unsupported by the original grammar");
        pending = capture.index();
        result.put(264 + pending * 8, (byte) cursor);
      } else {
        var alternatives = (Alternatives) part;
        int at = -1;
        String matched = null;
        for (String literal : alternatives.texts()) {
          if (pending >= 0) {
            for (int index = cursor; index <= text.length() - literal.length(); index++)
              if (equalAt(text, index, literal, work)) {
                at = index;
                matched = literal;
                break;
              }
          } else if (equalAt(text, cursor, literal, work)) {
            at = cursor;
            matched = literal;
          }
          if (matched != null) break;
        }
        if (matched == null) return false;
        if (!matched.isEmpty()) {
          if (pending >= 0) {
            int start = Byte.toUnsignedInt(result.get(264 + pending * 8));
            result.putInt(268 + pending * 8, at - start);
            pending = -1;
          }
          cursor = at + matched.length();
        }
      }
    }
    if (pending >= 0) {
      int start = Byte.toUnsignedInt(result.get(264 + pending * 8));
      result.putInt(268 + pending * 8, text.length() - start);
      return true;
    }
    return cursor == text.length();
  }

  /**
   * Offset255 denotes an absent capture; high valid offsets are treated as bounded byte indices.
   */
  public static String variable(ByteBuffer match, int offset, int variable, int capacity) {
    if (variable < 0 || variable >= 8 || capacity < 0)
      throw new IllegalArgumentException("Invalid chat capture query");
    var data = slice(match, offset);
    if (capacity == 0) return "";
    int start = Byte.toUnsignedInt(data.get(264 + variable * 8));
    if (start == 255) return "";
    int length = data.getInt(268 + variable * 8);
    if (length < 0 || start + length > MAX_TEXT)
      throw new IllegalArgumentException("Chat capture outside text");
    var value = new StringBuilder(Math.min(length, capacity - 1));
    for (int i = 0; i < length && i < capacity - 1; i++) {
      int character = Byte.toUnsignedInt(data.get(start + i));
      if (character == 0) break;
      value.append((char) character);
    }
    return value.toString();
  }

  private static boolean equalAt(String text, int offset, String literal, Work work) {
    if (offset + literal.length() > text.length()) return false;
    for (int i = 0; i < literal.length(); i++) {
      work.use();
      if (fold(text.charAt(offset + i)) != fold(literal.charAt(i))) return false;
    }
    return true;
  }

  private static char fold(char c) {
    return c >= 'a' && c <= 'z' ? (char) (c - 32) : c;
  }

  private static ByteBuffer slice(ByteBuffer buffer, int offset) {
    Objects.requireNonNull(buffer);
    if (offset < 0 || (long) offset + BYTE_SIZE > buffer.limit())
      throw new IllegalArgumentException("Chat match outside buffer");
    return buffer.slice(offset, BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
  }

  static final class Work {
    int remaining;

    Work(int count) {
      remaining = count;
    }

    void use() {
      if (--remaining < 0) throw new IllegalStateException("Chat matching work budget exceeded");
    }
  }
}
