package dev.bluevista.craftq3.client.input;

import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.fs.WritableFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

/** Q3-style archived settings and binds in the host-selected game home directory. */
public final class GameConfig {
  private final CommandSystem commands;
  private final KeyBindings bindings;
  private final VirtualFileSystem files;
  private final WritableFiles home;
  private final Consumer<String> output;

  public GameConfig(
      CommandSystem commands,
      KeyBindings bindings,
      VirtualFileSystem files,
      WritableFiles home,
      Consumer<String> output) {
    this.commands = commands;
    this.bindings = bindings;
    this.files = files;
    this.home = home;
    this.output = output;
    commands.savedFiles(home);
    commands.register(
        "writeconfig",
        command -> {
          try {
            save(command.arguments().size() < 2 ? "q3config.cfg" : command.argument(1));
          } catch (IOException e) {
            output.accept("Could not write config: " + e.getMessage());
          }
        });
  }

  /** The caller advances the normal command buffer; exec/wait retains its ordinary ordering. */
  public void load() {
    for (String name : new String[] {"q3config.cfg", "autoexec.cfg"}) {
      try {
        var path = new VirtualPath(name);
        if ((home != null && home.read(path).isPresent()) || files.which(path).isPresent())
          commands.submit("exec " + name, CommandSystem.Execution.APPEND);
      } catch (IOException e) {
        output.accept("Could not read " + name + ": " + e.getMessage());
      }
    }
  }

  public void save(String name) throws IOException {
    if (home == null) throw new IOException("Writable game home is unavailable");
    if (!name.toLowerCase(Locale.ROOT).endsWith(".cfg")) name += ".cfg";
    VirtualPath path = new VirtualPath(name);
    StringBuilder text = new StringBuilder("// CraftQ3 key bindings\nunbindall\n");
    for (var entry :
        bindings.all().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
      String value = entry.getValue();
      // Q3 command syntax has no general string escape. Refuse an ambiguous export transaction.
      if (value.indexOf('"') >= 0 || value.chars().anyMatch(c -> c < 32 || c > 255))
        throw new IOException(
            "Binding for key " + entry.getKey() + " cannot be represented in a Q3 config");
      text.append(String.format(Locale.ROOT, "bind 0x%02x \"%s\"\n", entry.getKey(), value));
    }
    text.append(commands.archivedConfig());
    if (text.length() > 65536)
      throw new IOException("Archived config exceeds command buffer capacity");
    if (dev.bluevista.craftq3.core.command.CommandParser.parse(text.toString()).size() > 4096)
      throw new IOException("Archived config exceeds command count capacity");
    home.write(path, text.toString().getBytes(StandardCharsets.ISO_8859_1));
  }
}
