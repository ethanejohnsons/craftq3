package dev.bluevista.craftq3.core.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded Q3 command tokenization: semicolons/newlines, quotes and C/C++ comments; never a shell.
 */
public final class CommandParser {
  public static final int MAX_TEXT = 65536, MAX_COMMAND = 8192, MAX_ARGS = 1024;

  private CommandParser() {}

  public record Command(String text, List<String> arguments) {
    public Command {
      arguments = List.copyOf(arguments);
    }

    public String argument(int index) {
      return index >= 0 && index < arguments.size() ? arguments.get(index) : "";
    }

    public String argumentsFrom(int index) {
      return String.join(
          " ", arguments.subList(Math.clamp(index, 0, arguments.size()), arguments.size()));
    }
  }

  public static List<Command> parse(String text) {
    if (text.length() > MAX_TEXT || text.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid command buffer");
    List<Command> commands = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    boolean quote = false, comment = false, block = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
      if (block) {
        if (c == '*' && next == '/') {
          block = false;
          i++;
          line.append(' ');
        }
        continue;
      }
      if (c == '\n' || c == '\r') {
        emit(line, commands);
        quote = false;
        comment = false;
        continue;
      }
      if (comment) continue;
      if (!quote && c == '/' && next == '/') {
        comment = true;
        i++;
        continue;
      }
      if (!quote && c == '/' && next == '*') {
        block = true;
        i++;
        continue;
      }
      if (c == '"') quote = !quote;
      if (c == ';' && !quote) emit(line, commands);
      else {
        line.append(c);
        if (line.length() > MAX_COMMAND) throw new IllegalArgumentException("Command too long");
      }
    }
    if (block) throw new IllegalArgumentException("Unterminated command comment");
    emit(line, commands);
    return List.copyOf(commands);
  }

  private static void emit(StringBuilder line, List<Command> commands) {
    String text = line.toString().trim();
    line.setLength(0);
    if (text.isEmpty()) return;
    Command command = tokenize(text);
    if (!command.arguments().isEmpty()) commands.add(command);
  }

  /**
   * Tokenizes one already-framed command, such as a reliable server message. Semicolons never
   * execute another command here, and quoted newlines are retained in arguments such as print text.
   */
  public static Command tokenize(String text) {
    if (text.length() > MAX_COMMAND || text.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid command text");
    List<String> args = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      while (i < text.length() && text.charAt(i) <= ' ') i++;
      if (i == text.length()) break;
      if (text.startsWith("//", i)) break;
      if (text.startsWith("/*", i)) {
        int end = text.indexOf("*/", i + 2);
        if (end < 0) break;
        i = end + 2;
        continue;
      }
      int start;
      if (text.charAt(i) == '"') {
        start = ++i;
        while (i < text.length() && text.charAt(i) != '"') i++;
        args.add(text.substring(start, i));
        if (i < text.length()) i++;
      } else {
        start = i;
        while (i < text.length() && text.charAt(i) > ' ' && text.charAt(i) != '"') i++;
        args.add(text.substring(start, i));
      }
      if (args.size() > MAX_ARGS) throw new IllegalArgumentException("Too many command arguments");
    }
    return new Command(text, args);
  }
}
