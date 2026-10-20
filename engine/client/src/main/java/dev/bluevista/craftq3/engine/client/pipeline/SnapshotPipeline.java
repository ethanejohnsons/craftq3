package dev.bluevista.craftq3.engine.client.pipeline;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.ConfigstringCommands;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;

/**
 * The one path server messages take to the cgame, whatever produced them: a remote connection, the
 * loopback connection to the local server, or a demo. Each accepted message ({@link #accept}) is
 * applied in wire order:
 *
 * <ul>
 *   <li>a gamestate starts a new level: configstrings, baselines, client number and checksum feed,
 *       the reliable command sequence; snapshot history is cleared and {@link #generation()}
 *       advances;
 *   <li>reliable server commands are kept in a ring of {@value Protocol68#MAX_RELIABLE_COMMANDS}
 *       (duplicates of already received sequences are dropped), and configstring commands also
 *       update {@link #configStrings()} (the wire view; the cgame consumes its own copy as it
 *       executes commands);
 *   <li>snapshots go into a history of {@value Protocol68#PACKET_BACKUP} by message number with
 *       the command sequence at their arrival and their ping;
 *   <li>downloads go to the listeners.
 * </ul>
 *
 * Every accepted message is offered to the {@link Listener}s, with its clear payload when the
 * source has one, so a {@code DemoRecorder} can record it. Owned by the render thread.
 */
public final class SnapshotPipeline {
  /** Observes the stream. */
  public interface Listener {
    /** A message was applied; {@code payload} is its clear server-message bytes, or null. */
    default void message(int sequence, ServerMessageCodec.Message message, byte[] payload) {}

    default void gamestate(ServerMessageCodec.GameState gameState, int messageSequence) {}

    default void snapshot(ClientSnapshot snapshot) {}

    default void serverCommand(int sequence, String text) {}

    default void download(ServerMessageCodec.Download download) {}
  }

  private final List<Listener> listeners = new ArrayList<>();
  private final TreeMap<Integer, String> commands = new TreeMap<>();
  private final TreeMap<Integer, ClientSnapshot> history = new TreeMap<>();
  private ServerMessageCodec.GameState gameState;
  private ConfigstringCommands config = new ConfigstringCommands(Map.of());
  private ClientSnapshot latest;
  private int gameStateSequence;
  private int commandSequence;
  private int lastMessage;
  private int generation;

  public void addListener(Listener listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  /** Forgets everything (disconnect, a new demo). */
  public void reset() {
    commands.clear();
    history.clear();
    gameState = null;
    config = new ConfigstringCommands(Map.of());
    latest = null;
    gameStateSequence = commandSequence = lastMessage = 0;
    generation++;
  }

  /**
   * Applies one decoded server message.
   *
   * @param sequence the message sequence (netchan or demo record)
   * @param payload the clear message bytes for recording, or null
   * @param ping the ping of a snapshot by its message number
   */
  public void accept(
      int sequence, ServerMessageCodec.Message message, byte[] payload, IntUnaryOperator ping) {
    Objects.requireNonNull(message);
    lastMessage = sequence;
    for (ServerMessageCodec.Operation operation : message.operations()) {
      switch (operation) {
        case ServerMessageCodec.NoOp ignored -> {}
        case ServerMessageCodec.Command command -> command(command);
        case ServerMessageCodec.GameState state -> gamestate(state, sequence);
        case ServerMessageCodec.Frame frame -> frame(frame, ping);
        case ServerMessageCodec.Download download -> {
          for (Listener listener : List.copyOf(listeners)) listener.download(download);
        }
      }
    }
    for (Listener listener : List.copyOf(listeners)) listener.message(sequence, message, payload);
  }

  private void command(ServerMessageCodec.Command command) {
    if (command.sequence() <= commandSequence) return;
    commandSequence = command.sequence();
    commands.put(command.sequence(), command.text());
    while (commands.size() > Protocol68.MAX_RELIABLE_COMMANDS) commands.pollFirstEntry();
    config.consume(command.text());
    for (Listener listener : List.copyOf(listeners))
      listener.serverCommand(command.sequence(), command.text());
  }

  private void gamestate(ServerMessageCodec.GameState state, int sequence) {
    gameState = state;
    gameStateSequence = sequence;
    commandSequence = state.commandSequence();
    commands.clear();
    history.clear();
    latest = null;
    config = new ConfigstringCommands(state.configstrings());
    generation++;
    for (Listener listener : List.copyOf(listeners)) listener.gamestate(state, sequence);
  }

  private void frame(ServerMessageCodec.Frame frame, IntUnaryOperator ping) {
    if (gameState == null) return;
    var snapshot = frame.current();
    int number = snapshot.sequence();
    ClientSnapshot parsed =
        new ClientSnapshot(
            snapshot,
            commandSequence,
            ping.applyAsInt(number),
            frame.previous() == null ? 0 : frame.previous().sequence());
    history.put(number, parsed);
    history.headMap(number - Protocol68.PACKET_BACKUP, true).clear();
    if (latest == null || number >= latest.messageNum()) latest = parsed;
    for (Listener listener : List.copyOf(listeners)) listener.snapshot(parsed);
  }

  // ---------------------------------------------------------------- level state

  public Optional<ServerMessageCodec.GameState> gameState() {
    return Optional.ofNullable(gameState);
  }

  /** The message sequence that carried the current gamestate. */
  public int gameStateSequence() {
    return gameStateSequence;
  }

  /** Advances with every gamestate and reset; a cgame of an older generation is stale. */
  public int generation() {
    return generation;
  }

  /** The newest message sequence applied. */
  public int lastMessage() {
    return lastMessage;
  }

  /** Configstrings with every received configstring command applied. */
  public Map<Integer, String> configStrings() {
    return config.strings();
  }

  public String configString(int index) {
    return config.strings().getOrDefault(index, "");
  }

  // ---------------------------------------------------------------- reliable commands

  /** The newest reliable server command sequence received. */
  public int serverCommandSequence() {
    return commandSequence;
  }

  /** A retained command, or empty when it was never received or has cycled out. */
  public Optional<String> serverCommand(int sequence) {
    return Optional.ofNullable(commands.get(sequence));
  }

  // ---------------------------------------------------------------- snapshots

  /** The newest snapshot of this level ({@code cl.snap}). */
  public Optional<ClientSnapshot> latestSnapshot() {
    return Optional.ofNullable(latest);
  }

  /** A snapshot still in the history window. */
  public Optional<ClientSnapshot> snapshot(int messageNum) {
    return Optional.ofNullable(history.get(messageNum));
  }
}
