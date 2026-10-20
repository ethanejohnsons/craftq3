package dev.bluevista.craftq3.engine.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The original command line ({@code Com_ParseCommandLine}): the arguments are joined with spaces
 * and split at every {@code +} outside quotes, so {@code +set dedicated 1 +map q3dm17} becomes the
 * two commands {@code set dedicated 1} and {@code map q3dm17}. Text before the first {@code +} is
 * ignored, as are empty commands.
 */
public final class CommandLine {
  private CommandLine() {}

  public static List<String> parse(String... args) {
    String joined = String.join(" ", args);
    List<String> commands = new ArrayList<>();
    StringBuilder current = null;
    boolean quoted = false;
    for (int i = 0; i < joined.length(); i++) {
      char c = joined.charAt(i);
      if (c == '"') quoted = !quoted;
      if ((c == '+' && !quoted) || c == '\n' || c == '\r') {
        add(commands, current);
        current = new StringBuilder();
        continue;
      }
      if (current != null) current.append(c);
    }
    add(commands, current);
    return List.copyOf(commands);
  }

  private static void add(List<String> commands, StringBuilder text) {
    if (text == null) return;
    String command = text.toString().strip();
    if (!command.isEmpty()) commands.add(command);
  }

  /** True for {@code set}-family lines, which apply before the configs run. */
  public static boolean isSet(String command) {
    String lower = command.toLowerCase(Locale.ROOT);
    return lower.startsWith("set ")
        || lower.startsWith("seta ")
        || lower.startsWith("sets ")
        || lower.startsWith("setu ");
  }

  /** The value a {@code +set name value} line gives {@code name}, or null. */
  public static String setValue(List<String> commands, String name) {
    String value = null;
    for (String command : commands) {
      if (!isSet(command)) continue;
      String[] words = command.split("\\s+", 3);
      if (words.length >= 3 && words[1].equalsIgnoreCase(name))
        value = words[2].replace("\"", "").strip();
    }
    return value;
  }
}
