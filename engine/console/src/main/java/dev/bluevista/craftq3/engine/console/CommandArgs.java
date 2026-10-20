package dev.bluevista.craftq3.engine.console;

import java.util.List;

/**
 * One tokenized command: the {@code Cmd_Argc}/{@code Cmd_Argv}/{@code Cmd_Args} view of a line.
 *
 * @param text the command text as received ({@code Cmd_Cmd}), after length limiting
 * @param tokens the arguments; token 0 is the command name
 */
public record CommandArgs(String text, List<String> tokens) {
  public static final CommandArgs EMPTY = new CommandArgs("", List.of());

  public CommandArgs {
    tokens = List.copyOf(tokens);
  }

  public int argc() {
    return tokens.size();
  }

  /** {@code Cmd_Argv}: the token, or empty when out of range. */
  public String argv(int index) {
    return index >= 0 && index < tokens.size() ? tokens.get(index) : "";
  }

  /** The command name (token 0), or empty. */
  public String name() {
    return argv(0);
  }

  /** {@code Cmd_Args}: tokens 1 onwards joined by single spaces. */
  public String args() {
    return argsFrom(1);
  }

  /** Tokens from {@code index} onwards joined by single spaces. */
  public String argsFrom(int index) {
    int from = Math.clamp(index, 0, tokens.size());
    return String.join(" ", tokens.subList(from, tokens.size()));
  }
}
