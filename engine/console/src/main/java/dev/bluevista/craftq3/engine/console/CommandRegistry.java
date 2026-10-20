package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Registered console commands and {@code Cmd_ExecuteString} dispatch. A command name is looked up
 * case-insensitively among registered commands, then as a cvar ({@code Cvar_Command}: {@code name}
 * prints, {@code name value} sets), then offered to each {@link CommandFallback}; if nobody takes
 * it, {@code Unknown command} is printed.
 *
 * <p>Single owner thread, shared with the cvar table it falls back to.
 */
public final class CommandRegistry {
  /** Bound on immediate (nested) dispatch, which the original engine left to the C stack. */
  public static final int MAX_DEPTH = 32;

  private static final Log LOG = Log.get(CommandRegistry.class);

  private record Entry(String name, CommandHandler handler, CommandCompleter completer) {}

  private final ThreadOwner owner;
  private final CvarTable cvars;
  private final ConsoleOutput output;
  private final Map<String, Entry> commands = new HashMap<>();
  private final List<CommandFallback> fallbacks = new ArrayList<>();
  private CommandArgs current = CommandArgs.EMPTY;
  private int depth;

  public CommandRegistry(CvarTable cvars, ConsoleOutput output) {
    this.cvars = Objects.requireNonNull(cvars);
    this.owner = cvars.owner();
    this.output = Objects.requireNonNull(output);
  }

  public ThreadOwner owner() {
    return owner;
  }

  public CvarTable cvars() {
    return cvars;
  }

  public ConsoleOutput output() {
    return output;
  }

  /** {@code Cmd_AddCommand}. */
  public boolean register(String name, CommandHandler handler) {
    return register(name, handler, null);
  }

  /**
   * Registers a command with an optional argument completer. Like the original engine, a duplicate
   * name is reported and ignored rather than replacing the existing command.
   *
   * @return false when the name was already registered
   */
  public boolean register(String name, CommandHandler handler, CommandCompleter completer) {
    owner.check();
    Objects.requireNonNull(handler, "handler");
    validateName(name);
    String key = CvarTable.key(name);
    if (commands.containsKey(key)) {
      LOG.warn("Cmd_AddCommand: " + name + " already defined");
      return false;
    }
    commands.put(key, new Entry(name, handler, completer));
    return true;
  }

  /** {@code Cmd_RemoveCommand}. */
  public boolean unregister(String name) {
    owner.check();
    return commands.remove(CvarTable.key(name)) != null;
  }

  public boolean exists(String name) {
    owner.check();
    return commands.containsKey(CvarTable.key(name));
  }

  /** Registered command names, sorted case-insensitively. */
  public List<String> names() {
    owner.check();
    List<String> names = new ArrayList<>();
    for (Entry entry : commands.values()) names.add(entry.name());
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return names;
  }

  public void addFallback(CommandFallback fallback) {
    owner.check();
    fallbacks.add(Objects.requireNonNull(fallback));
  }

  public boolean removeFallback(CommandFallback fallback) {
    owner.check();
    return fallbacks.remove(fallback);
  }

  /**
   * The command being dispatched (restored after nested dispatch), or {@link CommandArgs#EMPTY}.
   */
  public CommandArgs current() {
    owner.check();
    return current;
  }

  /** {@code Cmd_ExecuteString}: tokenizes {@code text} as one command and dispatches it. */
  public boolean execute(String text) {
    owner.check();
    return execute(CommandParser.tokenize(text));
  }

  /**
   * Dispatches one tokenized command.
   *
   * @return true when a command, cvar or fallback handled it
   */
  public boolean execute(CommandArgs args) {
    owner.check();
    if (args.argc() == 0) return false;
    if (depth >= MAX_DEPTH) {
      output.print("Command recursion limit reached at " + args.name());
      return false;
    }
    CommandArgs parent = current;
    current = args;
    depth++;
    try {
      Entry entry = commands.get(CvarTable.key(args.name()));
      if (entry != null) {
        run(entry, args);
        return true;
      }
      if (cvarCommand(args)) return true;
      for (CommandFallback fallback : List.copyOf(fallbacks))
        if (fallback.handle(args)) return true;
      output.print("Unknown command \"" + args.name() + "\"");
      return false;
    } finally {
      depth--;
      current = parent;
    }
  }

  /**
   * Completion candidates for the token at the end of {@code line} (text up to the cursor). The
   * first token completes against command and cvar names; later tokens use the command's completer.
   * A leading {@code /} or {@code \} and earlier {@code ;}-separated commands are ignored.
   */
  public List<String> complete(String line) {
    owner.check();
    String segment = lastSegment(line);
    CommandArgs args = CommandParser.tokenize(segment);
    boolean fresh = segment.isEmpty() || segment.charAt(segment.length() - 1) <= ' ';
    int argument = fresh ? args.argc() : args.argc() - 1;
    String partial = fresh ? "" : args.argv(argument);
    Collection<String> candidates;
    if (argument == 0) {
      List<String> all = new ArrayList<>();
      for (Entry entry : commands.values()) all.add(entry.name());
      for (Cvar cvar : cvars.all()) all.add(cvar.name());
      candidates = all;
    } else {
      Entry entry = commands.get(CvarTable.key(args.name()));
      if (entry == null || entry.completer() == null) return List.of();
      candidates = entry.completer().candidates(args, argument);
    }
    String prefix = CvarTable.asciiLower(partial);
    Set<String> matches = new LinkedHashSet<>();
    for (String candidate : candidates)
      if (CvarTable.asciiLower(candidate).startsWith(prefix)) matches.add(candidate);
    List<String> sorted = new ArrayList<>(matches);
    sorted.sort(String.CASE_INSENSITIVE_ORDER);
    return sorted;
  }

  private void run(Entry entry, CommandArgs args) {
    try {
      entry.handler().execute(args);
    } catch (ConsoleException | IllegalArgumentException e) {
      output.print(entry.name() + ": " + e.getMessage());
    }
  }

  /** {@code Cvar_Command}. */
  private boolean cvarCommand(CommandArgs args) {
    var found = cvars.find(args.name());
    if (found.isEmpty()) return false;
    Cvar cvar = found.get();
    if (args.argc() == 1) {
      output.print(
          "\""
              + cvar.name()
              + "\" is:\""
              + cvar.string()
              + "^7\" default:\""
              + cvar.resetString()
              + "^7\"");
      cvar.latchedString().ifPresent(latched -> output.print("latched: \"" + latched + "\""));
      return true;
    }
    // The original engine sets only the first argument; "name Player One" sets "Player".
    report(output, cvar.name(), cvars.setUser(cvar.name(), args.argv(1)));
    return true;
  }

  static void report(ConsoleOutput output, String name, CvarTable.SetResult result) {
    String message = result.message(name);
    if (!message.isEmpty()) output.print(message);
  }

  private static String lastSegment(String line) {
    int start = 0;
    while (start < line.length() && line.charAt(start) <= ' ') start++;
    if (start < line.length() && (line.charAt(start) == '/' || line.charAt(start) == '\\')) start++;
    while (true) {
      int end = CommandParser.lineEnd(line, start);
      if (end >= line.length()) return line.substring(start);
      start = end + 1;
    }
  }

  private static void validateName(String name) {
    if (name == null || name.isEmpty() || name.length() > CvarTable.MAX_NAME_LENGTH)
      throw new IllegalArgumentException("Invalid command name length");
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c <= ' ' || c == '"' || c == ';')
        throw new IllegalArgumentException("Invalid command name " + name);
    }
  }
}
