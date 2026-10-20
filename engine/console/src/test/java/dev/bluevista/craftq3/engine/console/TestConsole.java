package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A client console over in-memory files that records its output. */
final class TestConsole {
  final List<String> output = new ArrayList<>();
  final Map<String, String> files = new HashMap<>();
  final Map<String, String> written = new HashMap<>();
  boolean failWrites;
  final Console console;

  TestConsole() {
    this(true);
  }

  TestConsole(boolean client) {
    ConfigSource source = path -> Optional.ofNullable(files.get(path));
    ConfigSink sink =
        (path, text) -> {
          if (failWrites) throw new IOException("disk full");
          written.put(path, text);
        };
    ThreadOwner owner = new ThreadOwner("test console");
    console =
        client
            ? Console.client(owner, output::add, source, sink)
            : Console.server(owner, output::add, source, sink);
  }

  TestConsole file(String path, String text) {
    files.put(path, text);
    return this;
  }

  CvarTable cvars() {
    return console.cvars();
  }

  CommandBuffer buffer() {
    return console.buffer();
  }

  CommandRegistry commands() {
    return console.commands();
  }

  KeyBindings bindings() {
    return console.bindings().orElseThrow();
  }

  /** Appends a line (adding the newline) and runs one frame. */
  int run(String line) {
    buffer().add(line + "\n");
    return buffer().execute();
  }
}
