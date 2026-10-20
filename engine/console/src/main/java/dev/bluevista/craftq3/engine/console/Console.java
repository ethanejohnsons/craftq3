package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * One console: a cvar table, command registry, command buffer, cross-thread inbox and (on a client)
 * key bindings, all owned by a single thread, with the built-in commands installed: {@code set},
 * {@code sets}, {@code setu}, {@code seta}, {@code reset}, {@code toggle}, {@code vstr}, {@code
 * cvarlist}, {@code cmdlist}, {@code echo}, {@code wait}, {@code exec}, {@code writeconfig} and, on
 * a client, {@code bind}, {@code unbind}, {@code unbindall}, {@code bindlist}.
 *
 * <p>Typical owner-thread frame: {@link #frame()}. At startup, {@link #queueStartupConfigs} then
 * {@link #frame()}.
 */
public final class Console {
  /** The archive file written at shutdown and when archived state changes. */
  public static final String CONFIG_FILE = "q3config.cfg";

  private final CvarTable cvars;
  private final CommandRegistry commands;
  private final CommandBuffer buffer;
  private final CommandInbox inbox;
  private final KeyBindings bindings;
  private final ConfigSource source;
  private final ConfigSink sink;

  private Console(
      ThreadOwner owner,
      ConsoleOutput output,
      ConfigSource source,
      ConfigSink sink,
      boolean withBindings) {
    this.cvars = new CvarTable(owner);
    this.commands = new CommandRegistry(cvars, output);
    this.buffer = new CommandBuffer(commands);
    this.inbox = new CommandInbox(owner);
    this.bindings = withBindings ? new KeyBindings(cvars) : null;
    this.source = Objects.requireNonNull(source);
    this.sink = Objects.requireNonNull(sink);
    BuiltinCommands.install(this);
  }

  /** A client console, with key bindings and the bind commands. */
  public static Console client(
      ThreadOwner owner, ConsoleOutput output, ConfigSource source, ConfigSink sink) {
    return new Console(owner, output, source, sink, true);
  }

  /** A server (or dedicated) console without key bindings. */
  public static Console server(
      ThreadOwner owner, ConsoleOutput output, ConfigSource source, ConfigSink sink) {
    return new Console(owner, output, source, sink, false);
  }

  public ThreadOwner owner() {
    return cvars.owner();
  }

  public CvarTable cvars() {
    return cvars;
  }

  public CommandRegistry commands() {
    return commands;
  }

  public CommandBuffer buffer() {
    return buffer;
  }

  /** Thread-safe entry for other threads. */
  public CommandInbox inbox() {
    return inbox;
  }

  public Optional<KeyBindings> bindings() {
    return Optional.ofNullable(bindings);
  }

  public ConfigSource configSource() {
    return source;
  }

  ConsoleOutput output() {
    return commands.output();
  }

  /** Moves posted commands into the buffer and executes it with the default bound. */
  public int frame() {
    inbox.drainTo(buffer);
    return buffer.execute();
  }

  /**
   * Queues the startup scripts in the original order: {@code default.cfg}, then {@code
   * q3config.cfg} unless {@code safeMode}, then {@code autoexec.cfg}.
   */
  public void queueStartupConfigs(boolean safeMode) {
    buffer.add("exec default.cfg\n");
    if (!safeMode) buffer.add("exec " + CONFIG_FILE + "\n");
    buffer.add("exec autoexec.cfg\n");
  }

  /** The config text for this console's archived cvars and bindings. */
  public ConfigWriter.Result configText() {
    return ConfigWriter.write(cvars, bindings);
  }

  /**
   * {@code Com_WriteConfigToFile}: writes the config to {@code path} through the sink.
   *
   * @return false (after printing why) when the sink failed
   */
  public boolean writeConfig(String path) {
    cvars.owner().check();
    try {
      sink.write(path, configText().text());
      return true;
    } catch (IOException e) {
      commands.output().print("Couldn't write " + path + ": " + e.getMessage());
      return false;
    }
  }

  /**
   * {@code Com_WriteConfiguration}: writes {@link #CONFIG_FILE} only when an archived cvar or a
   * binding changed since the last write. A failed write leaves the change pending.
   */
  public boolean writeConfigIfModified() {
    if (!cvars.takeModified(CvarFlags.ARCHIVE)) return false;
    if (writeConfig(CONFIG_FILE)) return true;
    cvars.markModified(CvarFlags.ARCHIVE);
    return false;
  }
}
