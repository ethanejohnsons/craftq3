package dev.bluevista.craftq3.engine.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The console input line ({@code field_t}): bounded editing, history and tab completion,
 * independent of platform key codes. Accepted lines are returned for the caller to add to its
 * command buffer.
 */
public final class ConsoleField {
  /** {@code MAX_EDIT_LINE}. */
  public static final int LIMIT = 256;

  /** {@code COMMAND_HISTORY}. */
  public static final int HISTORY = 32;

  private final StringBuilder line = new StringBuilder();
  private final List<String> history = new ArrayList<>();
  private int cursor;
  private int historyIndex;
  private String draft = "";

  public String text() {
    return line.toString();
  }

  public int cursor() {
    return cursor;
  }

  /** Inserts a printable Latin-1 character at the cursor; others and overflow are ignored. */
  public void insert(int character) {
    if (character < 32 || character > 255 || character == 127 || line.length() >= LIMIT) return;
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

  public void clear() {
    set("");
  }

  /**
   * Enter: records the line in history, clears the field and returns the command text without a
   * leading {@code /} or {@code \}, or empty for a blank line.
   */
  public Optional<String> accept() {
    String text = line.toString().strip();
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

  /**
   * Tab: completes the token before the cursor through {@code registry}, extending it to the
   * longest common prefix of the candidates (and adding a space when there is exactly one).
   *
   * @return the candidates, for the caller to list when there are several
   */
  public List<String> complete(CommandRegistry registry) {
    List<String> matches = registry.complete(line.substring(0, cursor));
    complete(matches);
    return matches;
  }

  /** Applies already-computed completion candidates to the token before the cursor. */
  public void complete(List<String> matches) {
    if (matches.isEmpty()) return;
    int start = tokenStart();
    String partial = line.substring(start, cursor);
    String common = matches.getFirst();
    for (String match : matches) {
      int index = 0;
      while (index < common.length()
          && index < match.length()
          && Character.toLowerCase(common.charAt(index))
              == Character.toLowerCase(match.charAt(index))) index++;
      common = common.substring(0, index);
    }
    if (!CvarTable.asciiLower(common).startsWith(CvarTable.asciiLower(partial))) return;
    int end = cursor;
    while (end < line.length() && line.charAt(end) > ' ') end++;
    if (line.length() - (end - start) + common.length() > LIMIT) return;
    line.replace(start, end, common);
    cursor = start + common.length();
    if (matches.size() == 1 && cursor == line.length() && line.length() < LIMIT) insert(' ');
  }

  /** Start of the token ending at the cursor, after any leading slash or {@code ;}. */
  private int tokenStart() {
    int start = cursor;
    while (start > 0) {
      char c = line.charAt(start - 1);
      if (c <= ' ' || c == ';' || c == '"') break;
      start--;
    }
    if (start < cursor && start == leadingSlash()) start++;
    return start;
  }

  private int leadingSlash() {
    int i = 0;
    while (i < line.length() && line.charAt(i) <= ' ') i++;
    return i < line.length() && (line.charAt(i) == '/' || line.charAt(i) == '\\') ? i : -1;
  }

  private void set(String value) {
    line.setLength(0);
    line.append(value);
    cursor = line.length();
  }
}
