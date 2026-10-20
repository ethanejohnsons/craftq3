package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Key bindings for the {@link KeyNames#MAX_KEYS} Quake III key numbers. {@link #press} and {@link
 * #release} turn a key event into command lines for the caller to add to its command buffer: each
 * {@code +command} in the binding gets the key number and event time appended, and the matching
 * {@code -command} is produced on release from the binding as it was at press time, so a rebind
 * while held never leaves a button stuck.
 *
 * <p>Single owner thread (the client input thread).
 */
public final class KeyBindings {
  private final ThreadOwner owner;
  private final CvarTable cvars;
  private final String[] bindings = new String[KeyNames.MAX_KEYS];
  private final Map<Integer, List<CommandArgs>> held = new LinkedHashMap<>();

  /** Bindings owned by the cvar table's thread; changes mark the table's archive flag modified. */
  public KeyBindings(CvarTable cvars) {
    this.cvars = Objects.requireNonNull(cvars);
    this.owner = cvars.owner();
  }

  /**
   * {@code Key_SetBinding}; an empty binding unbinds. Marks {@link CvarFlags#ARCHIVE} modified so
   * the config is rewritten.
   */
  public void bind(int key, String binding) {
    owner.check();
    checkKey(key);
    bindings[key] = binding == null || binding.isEmpty() ? null : CommandParser.cutAtNul(binding);
    cvars.markModified(CvarFlags.ARCHIVE);
  }

  /** {@code Key_GetBinding}: the binding, or empty when unbound. */
  public String binding(int key) {
    owner.check();
    checkKey(key);
    return bindings[key] == null ? "" : bindings[key];
  }

  public boolean isBound(int key) {
    owner.check();
    checkKey(key);
    return bindings[key] != null;
  }

  /** {@code Key_GetKey}: the lowest key bound exactly to {@code binding}, or -1. */
  public int keyFor(String binding) {
    owner.check();
    for (int key = 0; key < bindings.length; key++)
      if (bindings[key] != null && bindings[key].equalsIgnoreCase(binding)) return key;
    return -1;
  }

  /** Bound keys in ascending key order with their bindings. */
  public Map<Integer, String> all() {
    owner.check();
    Map<Integer, String> result = new LinkedHashMap<>();
    for (int key = 0; key < bindings.length; key++)
      if (bindings[key] != null) result.put(key, bindings[key]);
    return result;
  }

  /** {@code unbindall}. Keys held now still release with their press-time binding. */
  public void unbindAll() {
    owner.check();
    Arrays.fill(bindings, null);
    cvars.markModified(CvarFlags.ARCHIVE);
  }

  /**
   * A key went down. Returns the binding's command lines; {@code +commands} carry {@code key} and
   * {@code time}. Repeats while held return nothing.
   */
  public List<String> press(int key, int time) {
    owner.check();
    checkKey(key);
    if (held.containsKey(key)) return List.of();
    List<CommandArgs> commands =
        bindings[key] == null ? List.of() : CommandParser.parse(bindings[key]);
    held.put(key, commands);
    List<String> lines = new ArrayList<>(commands.size());
    for (CommandArgs command : commands)
      lines.add(
          command.name().startsWith("+")
              ? command.text().strip() + " " + key + " " + time
              : command.text().strip());
    return List.copyOf(lines);
  }

  /** A key went up: the {@code -commands} for the {@code +commands} sent at press time. */
  public List<String> release(int key, int time) {
    owner.check();
    checkKey(key);
    List<CommandArgs> commands = held.remove(key);
    if (commands == null) return List.of();
    List<String> lines = new ArrayList<>();
    for (CommandArgs command : commands)
      if (command.name().startsWith("+"))
        lines.add(releaseText(command.text().strip()) + " " + key + " " + time);
    return List.copyOf(lines);
  }

  /** Releases every held key, for focus loss or a menu opening. */
  public List<String> releaseAll(int time) {
    owner.check();
    List<String> lines = new ArrayList<>();
    for (int key : List.copyOf(held.keySet())) lines.addAll(release(key, time));
    return List.copyOf(lines);
  }

  public boolean isHeld(int key) {
    owner.check();
    return held.containsKey(key);
  }

  /** Swaps the leading {@code +} for {@code -}, keeping quotes around the first token. */
  private static String releaseText(String text) {
    int plus = text.startsWith("\"") ? 1 : 0;
    return text.substring(0, plus) + '-' + text.substring(plus + 1);
  }

  private static void checkKey(int key) {
    if (key < 0 || key >= KeyNames.MAX_KEYS)
      throw new IllegalArgumentException("Invalid Quake key " + key);
  }
}
