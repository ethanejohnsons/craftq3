package dev.bluevista.craftq3.engine.console;

import java.util.ArrayList;
import java.util.List;

/**
 * Quake III command text handling: splitting buffered text into command lines and tokenizing one
 * line ({@code Cmd_TokenizeString}). Never a shell: no escapes, no expansion.
 *
 * <p>Line splitting ends a command at a newline or at a {@code ;} outside quotes and comments. A
 * {@code //} comment runs to the end of the line; a {@code /* *}{@code /} comment may span lines.
 * Tokenizing splits on whitespace, keeps quoted strings (including newlines inside them) as one
 * token, ends a plain token at a quote or comment, and drops comments.
 */
public final class CommandParser {
  /** {@code MAX_CMD_LINE}: a buffered command line is cut to this length minus its terminator. */
  public static final int MAX_LINE = 1024;

  /** {@code BIG_INFO_STRING}: tokenized text is cut to this length minus its terminator. */
  public static final int MAX_TOKENIZE = 8192;

  /** {@code MAX_STRING_TOKENS}: further tokens are ignored. */
  public static final int MAX_TOKENS = 1024;

  private CommandParser() {}

  /**
   * Splits text into lines and tokenizes each; blank lines and comment-only lines are dropped.
   * Lines longer than {@link #MAX_LINE} are cut as the command buffer cuts them.
   */
  public static List<CommandArgs> parse(String text) {
    String source = cutAtNul(text);
    List<CommandArgs> commands = new ArrayList<>();
    int from = 0;
    while (from < source.length()) {
      int end = lineEnd(source, from);
      CommandArgs command = tokenize(line(source, from, end));
      if (command.argc() > 0) commands.add(command);
      from = end + 1;
    }
    return List.copyOf(commands);
  }

  /**
   * {@code Cmd_TokenizeString}: tokenizes one already-framed command, such as a buffered line or a
   * reliable server command. Semicolons do not separate commands here.
   */
  public static CommandArgs tokenize(String text) {
    String source = cutAtNul(text);
    if (source.length() > MAX_TOKENIZE - 1) source = source.substring(0, MAX_TOKENIZE - 1);
    List<String> tokens = new ArrayList<>();
    int n = source.length();
    int i = 0;
    scan:
    while (tokens.size() < MAX_TOKENS) {
      while (true) {
        while (i < n && source.charAt(i) <= ' ') i++;
        if (i >= n || source.startsWith("//", i)) break scan;
        if (!source.startsWith("/*", i)) break;
        int close = source.indexOf("*/", i + 2);
        if (close < 0) break scan;
        i = close + 2;
      }
      if (source.charAt(i) == '"') {
        int start = ++i;
        while (i < n && source.charAt(i) != '"') i++;
        tokens.add(source.substring(start, i));
        if (i >= n) break;
        i++;
        continue;
      }
      int start = i;
      while (i < n && source.charAt(i) > ' ' && source.charAt(i) != '"' && !commentAt(source, i))
        i++;
      tokens.add(source.substring(start, i));
    }
    return new CommandArgs(source, tokens);
  }

  /**
   * The index of the character that ends the command line starting at {@code from}: a newline, an
   * unquoted {@code ;} outside comments, or the end of the text.
   */
  static int lineEnd(CharSequence text, int from) {
    int n = text.length();
    boolean quoted = false;
    boolean lineComment = false;
    boolean blockComment = false;
    for (int i = from; i < n; i++) {
      char c = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : 0;
      if (blockComment) {
        if (c == '*' && next == '/') {
          blockComment = false;
          i++;
        }
        continue;
      }
      if (c == '\n' || c == '\r') return i;
      if (lineComment) continue;
      if (c == '"') quoted = !quoted;
      else if (!quoted && c == '/' && next == '/') {
        lineComment = true;
        i++;
      } else if (!quoted && c == '/' && next == '*') {
        blockComment = true;
        i++;
      } else if (!quoted && c == ';') return i;
    }
    return n;
  }

  /** The command line between {@code from} and {@code end}, cut to {@link #MAX_LINE} - 1. */
  static String line(CharSequence text, int from, int end) {
    return text.subSequence(from, Math.min(end, from + MAX_LINE - 1)).toString();
  }

  /** C strings end at NUL; text from VM memory or files is cut there. */
  static String cutAtNul(String text) {
    int nul = text.indexOf('\0');
    return nul < 0 ? text : text.substring(0, nul);
  }

  private static boolean commentAt(String text, int i) {
    return text.charAt(i) == '/'
        && i + 1 < text.length()
        && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '*');
  }
}
