package dev.bluevista.craftq3.core.net;

import dev.bluevista.craftq3.core.command.CommandParser;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Configstring effects at one command-consumption boundary. The wire session and cgame each own an
 * independent instance; receiving future commands must not advance the presentation view.
 */
public final class ConfigstringCommands {
  private Map<Integer, String> strings;
  private String assembly;

  /** Native-observed outbound cs/bcs framing; wire sanitation remains in MessageWriter. */
  public static java.util.List<String> outbound(int index, String value) {
    java.util.Objects.requireNonNull(value);
    if (index < 0
        || index >= ServerMessageCodec.MAX_CONFIGSTRINGS
        || value.length() >= ServerMessageCodec.MAX_GAMESTATE_BYTES
        || value.chars().anyMatch(c -> c == 0 || c > 255))
      throw new IllegalArgumentException("Invalid outbound configstring");
    if (value.length() < 1000) return java.util.List.of("cs " + index + " \"" + value + "\"\n");
    var result = new java.util.ArrayList<String>();
    for (int offset = 0; offset < value.length(); offset += 999) {
      int end = Math.min(value.length(), offset + 999);
      String kind = offset == 0 ? "bcs0" : end == value.length() ? "bcs2" : "bcs1";
      result.add(kind + " " + index + " \"" + value.substring(offset, end) + "\"\n");
    }
    return java.util.List.copyOf(result);
  }

  public ConfigstringCommands(Map<Integer, String> strings) {
    this.strings = validated(strings);
  }

  /** Copying includes incomplete big-string assembly, for transactional message application. */
  public ConfigstringCommands(ConfigstringCommands from) {
    strings = from.strings;
    assembly = from.assembly;
  }

  public Map<Integer, String> strings() {
    return strings;
  }

  /**
   * Applies only cs/bcs effects. Hidden bcs0/1 fragments return empty; completed bcs2 returns the
   * native rewritten cs text. Every other command returns unchanged and is never executed here.
   * Individual failures cannot partly change the strings or assembly.
   */
  public Optional<String> consume(String text) {
    var command = CommandParser.tokenize(text);
    return switch (command.argument(0)) {
      case "cs" -> {
        apply(command);
        yield Optional.of(text);
      }
      case "bcs0" -> {
        index(command.argument(1));
        // Keep the original decimal spelling in the rewritten command, including leading zeros.
        String value = "cs " + command.argument(1) + " \"" + command.argument(2);
        checkAssembly(value);
        assembly = value;
        yield Optional.empty();
      }
      case "bcs1", "bcs2" -> {
        if (assembly == null) throw new IllegalArgumentException("Missing configstring fragment");
        // The later index tokens are unused by the native consumer; each fragment is argv2 only.
        boolean complete = command.argument(0).equals("bcs2");
        String value = assembly + command.argument(2) + (complete ? "\"" : "");
        checkAssembly(value);
        if (complete) apply(CommandParser.tokenize(value));
        assembly = value;
        yield complete ? Optional.of(value) : Optional.empty();
      }
      default -> Optional.of(text);
    };
  }

  private void apply(CommandParser.Command command) {
    int index = index(command.argument(1));
    String value = command.argumentsFrom(2);
    var next = new TreeMap<>(strings);
    if (value.isEmpty()) next.remove(index);
    else next.put(index, value);
    strings = validated(next);
  }

  private static int index(String text) {
    int index = Integer.parseInt(text);
    if (index < 0 || index >= ServerMessageCodec.MAX_CONFIGSTRINGS)
      throw new IllegalArgumentException("Invalid configstring index");
    return index;
  }

  private static void checkAssembly(String text) {
    if (text.length() >= 8192)
      throw new IllegalArgumentException("Big configstring command exceeds 8191 bytes");
  }

  private static Map<Integer, String> validated(Map<Integer, String> strings) {
    return new ServerMessageCodec.GameState(0, strings, SnapshotDeltaCodec.Baselines.EMPTY, 0, 0)
        .configstrings();
  }
}
