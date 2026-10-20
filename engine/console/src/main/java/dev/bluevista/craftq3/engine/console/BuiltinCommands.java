package dev.bluevista.craftq3.engine.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The engine-neutral built-in commands, with the original usage and status messages. */
final class BuiltinCommands {
  private final Console console;
  private final CvarTable cvars;
  private final CommandRegistry commands;
  private final CommandBuffer buffer;
  private final ConsoleOutput out;

  private BuiltinCommands(Console console) {
    this.console = console;
    this.cvars = console.cvars();
    this.commands = console.commands();
    this.buffer = console.buffer();
    this.out = console.output();
  }

  static void install(Console console) {
    new BuiltinCommands(console).install();
  }

  private void install() {
    CommandCompleter cvarNames = (line, argument) -> argument == 1 ? cvarNames() : List.of();
    commands.register("set", args -> set(args, "set", 0), cvarNames);
    commands.register("sets", args -> set(args, "sets", CvarFlags.SERVERINFO), cvarNames);
    commands.register("setu", args -> set(args, "setu", CvarFlags.USERINFO), cvarNames);
    commands.register("seta", args -> set(args, "seta", CvarFlags.ARCHIVE), cvarNames);
    commands.register("reset", this::reset, cvarNames);
    commands.register("toggle", this::toggle, cvarNames);
    commands.register("vstr", this::vstr, cvarNames);
    commands.register("cvarlist", this::cvarList);
    commands.register("cmdlist", this::commandList);
    commands.register("echo", args -> out.print(args.args()));
    commands.register("wait", this::waitCommand);
    commands.register("exec", this::exec);
    commands.register("writeconfig", this::writeConfig);
    console.bindings().ifPresent(this::installBindings);
  }

  private List<String> cvarNames() {
    List<String> names = new ArrayList<>();
    for (Cvar cvar : cvars.all()) names.add(cvar.name());
    return names;
  }

  /** {@code Cvar_Set_f} and its flagging variants. */
  private void set(CommandArgs args, String command, int flags) {
    if (args.argc() < 3) {
      out.print("usage: " + command + " <variable> <value>");
      return;
    }
    String name = args.argv(1);
    CommandRegistry.report(out, name, cvars.setUser(name, args.argsFrom(2)));
    if (flags != 0) cvars.addFlags(name, flags);
  }

  private void reset(CommandArgs args) {
    if (args.argc() != 2) {
      out.print("usage: reset <variable>");
      return;
    }
    CommandRegistry.report(out, args.argv(1), cvars.reset(args.argv(1)));
  }

  /**
   * {@code toggle name} flips between 0 and 1. As an extension, {@code toggle name a b c} cycles
   * through the listed values.
   */
  private void toggle(CommandArgs args) {
    if (args.argc() < 2) {
      out.print("usage: toggle <variable> [value1 value2 ...]");
      return;
    }
    String name = args.argv(1);
    String next;
    if (args.argc() == 2) {
      next = cvars.value(name) != 0 ? "0" : "1";
    } else {
      List<String> values = args.tokens().subList(2, args.argc());
      next = values.get((values.indexOf(cvars.string(name)) + 1) % values.size());
    }
    CommandRegistry.report(out, name, cvars.setUser(name, next));
  }

  /** {@code Cmd_Vstr_f}: runs a cvar's value as commands, ahead of the rest of the buffer. */
  private void vstr(CommandArgs args) {
    if (args.argc() != 2) {
      out.print("vstr <variablename> : execute a variable command");
      return;
    }
    buffer.insert(cvars.string(args.argv(1)) + "\n");
  }

  /** {@code Cvar_List_f}: flag letters S U R I A L C, newest cvar first, optional wildcard. */
  private void cvarList(CommandArgs args) {
    String filter = args.argc() > 1 ? args.argv(1) : null;
    List<Cvar> all = cvars.all();
    for (int i = all.size() - 1; i >= 0; i--) {
      Cvar cvar = all.get(i);
      if (filter != null && !Wildcard.matches(filter, cvar.name())) continue;
      int f = cvar.flags();
      String letters =
          ""
              + ((f & CvarFlags.SERVERINFO) != 0 ? 'S' : ' ')
              + ((f & CvarFlags.USERINFO) != 0 ? 'U' : ' ')
              + ((f & CvarFlags.ROM) != 0 ? 'R' : ' ')
              + ((f & CvarFlags.INIT) != 0 ? 'I' : ' ')
              + ((f & CvarFlags.ARCHIVE) != 0 ? 'A' : ' ')
              + ((f & CvarFlags.LATCH) != 0 ? 'L' : ' ')
              + ((f & CvarFlags.CHEAT) != 0 ? 'C' : ' ');
      out.print(letters + " " + cvar.name() + " \"" + cvar.string() + "\"");
    }
    out.print("");
    out.print(all.size() + " total cvars");
    out.print(all.size() + " cvar indexes");
  }

  private void commandList(CommandArgs args) {
    String filter = args.argc() > 1 ? args.argv(1) : null;
    List<String> names = commands.names();
    for (String name : names) if (filter == null || Wildcard.matches(filter, name)) out.print(name);
    out.print(names.size() + " commands");
  }

  /** {@code Cmd_Wait_f}: {@code wait} waits one frame, {@code wait N} waits N ({@code atoi}). */
  private void waitCommand(CommandArgs args) {
    buffer.waitFrames(args.argc() == 2 ? Cvar.parseInt(args.argv(1)) : 1);
  }

  /** {@code Cmd_Exec_f}: inserts the file so it runs before the rest of the buffer. */
  private void exec(CommandArgs args) {
    if (args.argc() != 2) {
      out.print("exec <filename> : execute a script file");
      return;
    }
    String path = defaultExtension(args.argv(1), ".cfg");
    Optional<String> text = console.configSource().read(path);
    if (text.isEmpty()) {
      out.print("couldn't exec " + args.argv(1));
      return;
    }
    out.print("execing " + args.argv(1));
    buffer.insert(text.get());
  }

  /** {@code Com_WriteConfig_f}. */
  private void writeConfig(CommandArgs args) {
    if (args.argc() != 2) {
      out.print("Usage: writeconfig <filename>");
      return;
    }
    String path = defaultExtension(args.argv(1), ".cfg");
    out.print("Writing " + path + ".");
    console.writeConfig(path);
  }

  private void installBindings(KeyBindings keys) {
    CommandCompleter keyNames = (line, argument) -> argument == 1 ? KeyNames.names() : List.of();
    commands.register("bind", args -> bind(keys, args), keyNames);
    commands.register("unbind", args -> unbind(keys, args), keyNames);
    commands.register("unbindall", args -> keys.unbindAll());
    commands.register(
        "bindlist",
        args -> {
          for (Map.Entry<Integer, String> entry : keys.all().entrySet())
            out.print(KeyNames.toName(entry.getKey()) + " \"" + entry.getValue() + "\"");
        });
  }

  /** {@code Key_Bind_f}: {@code bind key} shows the binding; more arguments are joined. */
  private void bind(KeyBindings keys, CommandArgs args) {
    if (args.argc() < 2) {
      out.print("bind <key> [command] : attach a command to a key");
      return;
    }
    int key = KeyNames.toKey(args.argv(1));
    if (key < 0) {
      out.print("\"" + args.argv(1) + "\" isn't a valid key");
      return;
    }
    if (args.argc() == 2) {
      if (keys.isBound(key)) out.print("\"" + args.argv(1) + "\" = \"" + keys.binding(key) + "\"");
      else out.print("\"" + args.argv(1) + "\" is not bound");
      return;
    }
    keys.bind(key, args.argsFrom(2));
  }

  private void unbind(KeyBindings keys, CommandArgs args) {
    if (args.argc() != 2) {
      out.print("unbind <key> : remove commands from a key");
      return;
    }
    int key = KeyNames.toKey(args.argv(1));
    if (key < 0) {
      out.print("\"" + args.argv(1) + "\" isn't a valid key");
      return;
    }
    keys.bind(key, "");
  }

  /** {@code COM_DefaultExtension}: appends {@code extension} when the file name has none. */
  static String defaultExtension(String path, String extension) {
    for (int i = path.length() - 1; i >= 0; i--) {
      char c = path.charAt(i);
      if (c == '/' || c == '\\') break;
      if (c == '.') return path;
    }
    return path + extension;
  }
}
