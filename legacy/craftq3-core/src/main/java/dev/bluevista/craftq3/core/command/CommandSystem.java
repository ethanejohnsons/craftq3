package dev.bluevista.craftq3.core.command;

import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.fs.WritableFiles;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Single simulation-thread command buffer. Recursive exec/vstr consumes the same per-frame budget.
 */
public final class CommandSystem {
  public enum Execution {
    NOW,
    INSERT,
    APPEND
  }

  private final Map<String, Consumer<CommandParser.Command>> handlers = new LinkedHashMap<>();
  private final ArrayDeque<CommandParser.Command> queue = new ArrayDeque<>();
  private final CvarSystem cvars;
  private final VirtualFileSystem fs;
  private final Consumer<String> output;
  private Consumer<CommandParser.Command> unknown;
  private CommandParser.Command current = new CommandParser.Command("", List.of());
  private int queuedChars, waitFrames, remainingBudget, dispatchDepth;
  private boolean executing;
  private boolean frameBoundary;
  private WritableFiles savedFiles;

  public CommandSystem(CvarSystem cvars, VirtualFileSystem fs, Consumer<String> output) {
    this.cvars = cvars;
    this.fs = fs;
    this.output = output;
    unknown = command -> output.accept("Unknown command: " + command.argument(0));
    register("echo", command -> output.accept(command.argumentsFrom(1)));
    register("set", command -> set(command, 0));
    register("seta", command -> set(command, CvarSystem.ARCHIVE));
    register("setu", command -> set(command, CvarSystem.USERINFO));
    register("sets", command -> set(command, CvarSystem.SERVERINFO));
    register(
        "reset", command -> report(cvars.reset(command.argument(1), CvarSystem.Source.CONSOLE)));
    register(
        "toggle",
        command -> {
          if (command.arguments().size() < 2)
            throw new IllegalArgumentException("toggle <cvar> [values...]");
          String name = command.argument(1), value;
          if (command.arguments().size() == 2) value = cvars.number(name) == 0 ? "1" : "0";
          else {
            List<String> choices = command.arguments().subList(2, command.arguments().size());
            value = choices.get((choices.indexOf(cvars.string(name)) + 1) % choices.size());
          }
          report(cvars.set(name, value, CvarSystem.Source.CONSOLE));
        });
    register("vstr", command -> submit(cvars.string(command.argument(1)), Execution.INSERT));
    register(
        "wait",
        command -> {
          try {
            waitFrames =
                command.arguments().size() > 1
                    ? Math.clamp(Integer.parseInt(command.argument(1)), 1, 10000)
                    : 1;
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("wait [frames]", e);
          }
        });
    register(
        "exec",
        command -> {
          if (command.arguments().size() != 2)
            throw new IllegalArgumentException("exec <file.cfg>");
          String name = command.argument(1);
          if (name.lastIndexOf('.') <= name.lastIndexOf('/')) name += ".cfg";
          try {
            var path = new VirtualPath(name);
            var saved =
                savedFiles == null ? java.util.Optional.<byte[]>empty() : savedFiles.read(path);
            String text =
                saved.isPresent()
                    ? new String(saved.get(), java.nio.charset.StandardCharsets.ISO_8859_1)
                    : fs.readText(path);
            submit(text, Execution.INSERT);
          } catch (IOException e) {
            output.accept("Could not exec " + name + ": " + e.getMessage());
          }
        });
    register(
        "cvarlist",
        command -> cvars.all().forEach(v -> output.accept(v.name() + " = " + v.value())));
    register("cmdlist", command -> handlers.keySet().stream().sorted().forEach(output));
  }

  public void register(String name, Consumer<CommandParser.Command> handler) {
    String key = name.toLowerCase(Locale.ROOT);
    if (!key.matches("[a-z0-9_+.-]{1,255}"))
      throw new IllegalArgumentException("Invalid command name");
    if (handlers.putIfAbsent(key, handler) != null)
      throw new IllegalArgumentException("Duplicate command " + name);
  }

  public void unregister(String name) {
    handlers.remove(name.toLowerCase(Locale.ROOT));
  }

  public void unknownHandler(Consumer<CommandParser.Command> handler) {
    unknown = java.util.Objects.requireNonNull(handler);
  }

  /** Borrowed, host-selected home storage; never changes the archive mount or write permissions. */
  public void savedFiles(WritableFiles files) {
    savedFiles = files;
  }

  public CommandParser.Command current() {
    return current;
  }

  public int pending() {
    return queue.size();
  }

  /** Stops buffered dispatch after this command so the host can change maps outside VM calls. */
  public void frameBoundary() {
    if (!executing) throw new IllegalStateException("A frame boundary requires a running command");
    frameBoundary = true;
  }

  public List<String> complete(String prefix) {
    String key = prefix.toLowerCase(Locale.ROOT);
    return java.util.stream.Stream.concat(
            handlers.keySet().stream(), cvars.all().stream().map(CvarSystem.Snapshot::name))
        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(key))
        .distinct()
        .sorted()
        .toList();
  }

  public void submit(String text, Execution execution) {
    List<CommandParser.Command> commands = CommandParser.parse(text);
    if (execution == Execution.NOW) {
      // Immediate dispatch must never consume unrelated buffered APPEND/INSERT commands.
      boolean outermost = !executing;
      if (outermost) {
        executing = true;
        remainingBudget = 1024;
        frameBoundary = false;
      }
      try {
        for (int i = 0; i < commands.size(); i++) {
          dispatch(commands.get(i));
          if (frameBoundary && i + 1 < commands.size()) {
            submit(
                commands.subList(i + 1, commands.size()).stream()
                    .map(CommandParser.Command::text)
                    .collect(java.util.stream.Collectors.joining("\n")),
                Execution.INSERT);
            break;
          }
        }
      } finally {
        if (outermost) executing = false;
      }
      return;
    }
    int size = commands.stream().mapToInt(c -> c.text().length() + 1).sum();
    if (queuedChars + size > CommandParser.MAX_TEXT || queue.size() + commands.size() > 4096)
      throw new IllegalStateException("Command buffer capacity exceeded");
    queuedChars += size;
    if (execution == Execution.APPEND) queue.addAll(commands);
    else for (int i = commands.size() - 1; i >= 0; i--) queue.addFirst(commands.get(i));
  }

  public int runFrame(int budget) {
    if (budget < 1) throw new IllegalArgumentException("Command budget must be positive");
    if (executing) throw new IllegalStateException("Recursive command frame");
    if (waitFrames > 0) {
      waitFrames--;
      return 0;
    }
    remainingBudget = budget;
    frameBoundary = false;
    executing = true;
    try {
      while (!queue.isEmpty() && remainingBudget > 0 && waitFrames == 0 && !frameBoundary) {
        CommandParser.Command command = queue.removeFirst();
        queuedChars -= command.text().length() + 1;
        dispatch(command);
      }
    } finally {
      current = new CommandParser.Command("", List.of());
      executing = false;
    }
    // A wait encountered this frame counts this frame, so `wait 1` resumes on the next one.
    if (waitFrames > 0) waitFrames--;
    return budget - remainingBudget;
  }

  private void dispatch(CommandParser.Command command) {
    if (remainingBudget <= 0) throw new IllegalStateException("Immediate command budget exhausted");
    if (dispatchDepth >= 32) throw new IllegalStateException("Immediate command recursion limit");
    remainingBudget--;
    dispatchDepth++;
    CommandParser.Command parent = current;
    current = command;
    try {
      String name = command.argument(0);
      Consumer<CommandParser.Command> handler = handlers.get(name.toLowerCase(Locale.ROOT));
      if (handler != null) handler.accept(command);
      else if (cvars.find(name).isPresent()) {
        if (command.arguments().size() == 1)
          output.accept(name + " = \"" + cvars.string(name) + "\"");
        else report(cvars.set(name, command.argumentsFrom(1), CvarSystem.Source.CONSOLE));
      } else unknown.accept(command);
    } catch (IllegalArgumentException | IllegalStateException e) {
      output.accept("Command error: " + e.getMessage());
    } finally {
      current = parent;
      dispatchDepth--;
    }
  }

  public String archivedConfig() {
    StringBuilder text = new StringBuilder("// CraftQ3 archived cvars\n");
    for (var variable : cvars.all())
      if ((variable.flags() & CvarSystem.ARCHIVE) != 0) {
        String value = variable.latchedValue().orElse(variable.value());
        if (value.indexOf('"') >= 0)
          throw new IllegalStateException("Cannot serialize quoted cvar " + variable.name());
        text.append("seta ").append(variable.name()).append(" \"").append(value).append("\"\n");
      }
    return text.toString();
  }

  private void set(CommandParser.Command command, int flags) {
    if (command.arguments().size() < 3) {
      if (command.arguments().size() == 2)
        output.accept(command.argument(1) + " = \"" + cvars.string(command.argument(1)) + "\"");
      else throw new IllegalArgumentException("set <name> <value>");
      return;
    }
    String name = command.argument(1), value = command.argumentsFrom(2);
    // Validate info values before mutation, including newly-created variables.
    if ((flags & (CvarSystem.USERINFO | CvarSystem.SERVERINFO)) != 0)
      dev.bluevista.craftq3.core.cvar.InfoString.check(value);
    report(cvars.set(name, value, CvarSystem.Source.CONSOLE));
    cvars.addFlags(name, flags);
  }

  private void report(CvarSystem.Change change) {
    if (change != CvarSystem.Change.CHANGED && change != CvarSystem.Change.UNCHANGED)
      output.accept("Cvar: " + change.name().toLowerCase(Locale.ROOT));
  }
}
