package dev.bluevista.craftq3.client.demo;

import dev.bluevista.craftq3.core.demo.DemoReader;
import dev.bluevista.craftq3.core.demo.Protocol68DemoReader;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Incremental demo connection state. The host owns read-ahead timing and original cgame lifetime.
 */
public final class DemoPlayback implements AutoCloseable {
  public interface Listener {
    default void gamestate() {}

    default void snapshot(int time, int flags) {}

    default void end(DemoReader.End reason) {}
  }

  public record SnapshotState(Snapshot snapshot, int commandSequence) {}

  private final Protocol68DemoReader reader;
  private final Listener listener;
  private final TreeMap<Integer, String> commands = new TreeMap<>();
  private final TreeMap<Integer, SnapshotState> history = new TreeMap<>();
  private ServerMessageCodec.GameState game;
  private Snapshot latest;
  private int generation, gameMessageSequence, commandSequence;
  private boolean ended, closed;

  public DemoPlayback(InputStream input, Listener listener) {
    this.reader = new Protocol68DemoReader(input, DemoReader.Policy.PLAYBACK);
    this.listener = Objects.requireNonNull(listener);
  }

  /** Reads exactly one record and publishes only fully decoded operations in their wire order. */
  public void read() throws IOException {
    if (closed) throw new IOException("Demo playback is closed");
    if (ended) return;
    var next = reader.next();
    if (next.isEmpty()) {
      ended = true;
      listener.end(reader.end());
      return;
    }
    var record = next.orElseThrow();
    for (var operation : record.message().operations()) {
      switch (operation) {
        case ServerMessageCodec.NoOp ignored -> {}
        case ServerMessageCodec.Download ignored -> {}
        case ServerMessageCodec.Command command -> {
          if (command.sequence() <= commandSequence) continue;
          commands.put(command.sequence(), command.text());
          commandSequence = command.sequence();
          commands.headMap(commandSequence - 63, false).clear();
        }
        case ServerMessageCodec.GameState state -> {
          if (state.clientNumber() < 0 || state.clientNumber() >= 64 || state.commandSequence() < 0)
            throw new IOException("Invalid demo gamestate metadata");
          game = state;
          gameMessageSequence = record.sequence();
          commandSequence = state.commandSequence();
          latest = null;
          history.clear();
          generation++;
          listener.gamestate();
        }
        case ServerMessageCodec.Frame frame -> {
          if (game == null) throw new IOException("Demo snapshot precedes gamestate");
          latest = frame.current();
          history.put(latest.sequence(), new SnapshotState(latest, commandSequence));
          history.headMap(latest.sequence() - 32, true).clear();
          while (history.size() > 32) history.pollFirstEntry();
          listener.snapshot(latest.time(), latest.flags());
        }
      }
    }
  }

  public int generation() {
    return generation;
  }

  public int gameMessageSequence() {
    return gameMessageSequence;
  }

  public int commandSequence() {
    return commandSequence;
  }

  public long recordsRead() {
    return reader.recordsRead();
  }

  public boolean ended() {
    return ended;
  }

  public DemoReader.End endReason() {
    return reader.end();
  }

  public Optional<ServerMessageCodec.GameState> gameState() {
    return Optional.ofNullable(game);
  }

  public Optional<Snapshot> snapshot() {
    return Optional.ofNullable(latest);
  }

  public Optional<SnapshotState> snapshot(int sequence) {
    return Optional.ofNullable(history.get(sequence));
  }

  /** Missing retained command slots are empty strings in the native demo host. */
  public String command(int sequence) {
    return commands.getOrDefault(sequence, "");
  }

  public Map<Integer, String> initialConfigStrings() {
    if (game == null) throw new IllegalStateException("Demo has no gamestate");
    return game.configstrings();
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    reader.close();
  }
}
