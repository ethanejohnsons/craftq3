package dev.bluevista.craftq3.client.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Bounded line editing and history, independent of platform key codes and command execution. */
public final class ConsoleEditor {
  private static final int LIMIT = 1024, HISTORY = 64;
  private final StringBuilder line = new StringBuilder();
  private final List<String> history = new ArrayList<>();
  private int cursor, historyIndex;
  private String draft = "";

  public String text() {
    return line.toString();
  }

  public int cursor() {
    return cursor;
  }

  public void insert(int character) {
    if (character < 32 || character > 255 || line.length() >= LIMIT) return;
    line.insert(cursor++, (char) character);
  }

  public void left() {
    cursor = Math.max(0, cursor - 1);
  }

  public void right() {
    cursor = Math.min(line.length(), cursor + 1);
  }

  public void home() {
    cursor = 0;
  }

  public void end() {
    cursor = line.length();
  }

  public void backspace() {
    if (cursor > 0) line.deleteCharAt(--cursor);
  }

  public void delete() {
    if (cursor < line.length()) line.deleteCharAt(cursor);
  }

  public Optional<String> accept() {
    String text = line.toString().trim();
    if (text.isEmpty()) return Optional.empty();
    if (history.isEmpty() || !history.getLast().equals(text)) history.add(text);
    if (history.size() > HISTORY) history.removeFirst();
    historyIndex = history.size();
    draft = "";
    set("");
    if (text.charAt(0) == '/' || text.charAt(0) == '\\') text = text.substring(1);
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  public void previous() {
    if (historyIndex == 0) return;
    if (historyIndex == history.size()) draft = text();
    set(history.get(--historyIndex));
  }

  public void next() {
    if (historyIndex >= history.size()) return;
    historyIndex++;
    set(historyIndex == history.size() ? draft : history.get(historyIndex));
  }

  /** Completion applies to the first command/cvar token and retains the rest of the line. */
  public Optional<String> completionPrefix() {
    int start = tokenStart();
    if (cursor < start) return Optional.empty();
    String prefix = line.substring(start, cursor);
    return prefix.chars().anyMatch(Character::isWhitespace)
        ? Optional.empty()
        : Optional.of(prefix);
  }

  public void complete(List<String> matches) {
    var prefix = completionPrefix();
    if (prefix.isEmpty() || matches.isEmpty()) return;
    String common = matches.getFirst();
    for (String match : matches) {
      int index = 0;
      while (index < common.length()
          && index < match.length()
          && Character.toLowerCase(common.charAt(index))
              == Character.toLowerCase(match.charAt(index))) index++;
      common = common.substring(0, index);
    }
    if (!common
        .toLowerCase(java.util.Locale.ROOT)
        .startsWith(prefix.get().toLowerCase(java.util.Locale.ROOT))) return;
    int start = tokenStart();
    int end = cursor;
    while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
    if (line.length() - (end - start) + common.length() > LIMIT) return;
    line.replace(start, end, common);
    cursor = start + common.length();
    if (matches.size() == 1 && cursor == line.length() && line.length() < LIMIT) insert(' ');
  }

  private int tokenStart() {
    int start = 0;
    while (start < line.length() && Character.isWhitespace(line.charAt(start))) start++;
    if (start < line.length() && (line.charAt(start) == '/' || line.charAt(start) == '\\')) start++;
    return start;
  }

  private void set(String value) {
    line.setLength(0);
    line.append(value);
    cursor = line.length();
  }
}
