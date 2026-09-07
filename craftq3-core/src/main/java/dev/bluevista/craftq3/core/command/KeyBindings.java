package dev.bluevista.craftq3.core.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Quake key numbers are supplied by the host. Held bindings are released even after a rebind. */
public final class KeyBindings {
  private final Map<Integer, String> bindings = new LinkedHashMap<>();
  private final Map<Integer, List<CommandParser.Command>> held = new LinkedHashMap<>();

  public void bind(int key, String text) {
    checkKey(key);
    if (text.length() > 1024) throw new IllegalArgumentException("Binding too long");
    CommandParser.parse(text);
    if (text.isEmpty()) bindings.remove(key);
    else bindings.put(key, text);
  }

  public String binding(int key) {
    checkKey(key);
    return bindings.getOrDefault(key, "");
  }

  public Map<Integer, String> all() {
    return Map.copyOf(bindings);
  }

  public void unbindAll() {
    bindings.clear();
  }

  public List<String> press(int key, int milliseconds) {
    checkKey(key);
    if (held.containsKey(key)) return List.of();
    List<CommandParser.Command> commands = CommandParser.parse(binding(key));
    held.put(key, commands);
    return commands.stream()
        .map(c -> c.text() + (c.argument(0).startsWith("+") ? " " + key + " " + milliseconds : ""))
        .toList();
  }

  public List<String> release(int key, int milliseconds) {
    checkKey(key);
    List<CommandParser.Command> commands = held.remove(key);
    if (commands == null) return List.of();
    return commands.stream()
        .filter(c -> c.argument(0).startsWith("+"))
        .map(c -> releaseText(c) + " " + key + " " + milliseconds)
        .toList();
  }

  private static String releaseText(CommandParser.Command command) {
    // Preserve quoted arguments and the optional quotes around the first token itself.
    String text = command.text();
    int plus = text.startsWith("\"") ? 1 : 0;
    return text.substring(0, plus) + '-' + text.substring(plus + 1);
  }

  public List<String> releaseAll(int milliseconds) {
    List<String> result = new ArrayList<>();
    for (int key : List.copyOf(held.keySet())) result.addAll(release(key, milliseconds));
    return List.copyOf(result);
  }

  private static void checkKey(int key) {
    if (key < 0 || key > 255) throw new IllegalArgumentException("Invalid Quake key " + key);
  }
}
