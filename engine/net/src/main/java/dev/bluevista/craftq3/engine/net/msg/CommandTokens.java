package dev.bluevista.craftq3.engine.net.msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizes one already-framed protocol text line (a reliable server command or a connectionless
 * request) the way {@code Cmd_TokenizeString} does: whitespace separates arguments, double quotes
 * group them, and {@code //} or {@code /* *}{@code /} comments end or skip text. Semicolons and
 * newlines never split here, and nothing is executed. engine:console owns the full command buffer;
 * this module cannot depend on it, so the wire-level tokenizer lives here.
 */
public record CommandTokens(String text, List<String> arguments) {
  /** {@code MAX_STRING_TOKENS}-style bound on arguments; longer lines are rejected. */
  public static final int MAX_ARGUMENTS = 1024;

  /** Longest tokenizable line. */
  public static final int MAX_TEXT = 8192;

  public CommandTokens {
    arguments = List.copyOf(arguments);
  }

  /** The argument at {@code index}, or empty text past the end. */
  public String argument(int index) {
    return index >= 0 && index < arguments.size() ? arguments.get(index) : "";
  }

  /** Arguments from {@code index} on, joined by single spaces ({@code Cmd_ArgsFrom}). */
  public String argumentsFrom(int index) {
    return String.join(
        " ", arguments.subList(Math.clamp(index, 0, arguments.size()), arguments.size()));
  }

  public int count() {
    return arguments.size();
  }

  public static CommandTokens tokenize(String text) {
    if (text.length() > MAX_TEXT || text.indexOf('\0') >= 0)
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
      if (args.size() > MAX_ARGUMENTS)
        throw new IllegalArgumentException("Too many command arguments");
    }
    return new CommandTokens(text, args);
  }
}
