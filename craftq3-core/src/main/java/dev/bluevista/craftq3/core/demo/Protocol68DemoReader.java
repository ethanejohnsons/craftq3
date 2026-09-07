package dev.bluevista.craftq3.core.demo;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/** Incremental protocol-68 demo decoding; commands are returned as data, never executed. */
public final class Protocol68DemoReader implements AutoCloseable {
  public record DecodedRecord(int sequence, ServerMessageCodec.Message message) {
    public DecodedRecord {
      Objects.requireNonNull(message);
    }
  }

  private final DemoReader records;
  private final boolean playback;
  private final LinkedHashMap<Integer, Snapshot> history = new LinkedHashMap<>();
  private Baselines baselines = Baselines.EMPTY;
  private GameState gameState;
  private Snapshot latest;
  private boolean failed, closed;

  /** Owns the stream. The caller must identify protocol 68; this reader does not guess versions. */
  public Protocol68DemoReader(InputStream input) {
    this(input, DemoReader.Policy.STRICT);
  }

  public Protocol68DemoReader(InputStream input, DemoReader.Policy policy) {
    records = new DemoReader(input, policy);
    playback = policy == DemoReader.Policy.PLAYBACK;
  }

  public long recordsRead() {
    return records.recordsRead();
  }

  public DemoReader.End end() {
    return records.end();
  }

  /** Last transmitted gamestate; subsequent reliable configstring commands remain unapplied. */
  public Optional<GameState> gameState() {
    return Optional.ofNullable(gameState);
  }

  public Optional<Snapshot> latestSnapshot() {
    return Optional.ofNullable(latest);
  }

  /**
   * A malformed record fails the reader without partially publishing its level or snapshot state.
   */
  public Optional<DecodedRecord> next() throws IOException {
    if (failed || closed) throw new IOException("Protocol-68 demo reader is closed or failed");
    try {
      var record = records.next();
      if (record.isEmpty()) return Optional.empty();
      var raw = record.get();
      var decoded =
          playback
              ? ServerMessageCodec.readForPlayback(
                  new MessageReader(raw.payload()), raw.sequence(), baselines, history::get)
              : ServerMessageCodec.read(
                  new MessageReader(raw.payload()), raw.sequence(), baselines, history::get);
      for (var operation : decoded.operations()) {
        if (operation instanceof GameState level) {
          gameState = level;
          baselines = level.baselines();
          latest = null;
          history.clear();
        } else if (operation instanceof Frame frame) {
          latest = frame.current();
          int oldest = latest.sequence() - 32;
          history.entrySet().removeIf(entry -> entry.getKey() <= oldest);
          history.put(latest.sequence(), latest);
          while (history.size() > 32) history.remove(history.keySet().iterator().next());
        }
      }
      return Optional.of(new DecodedRecord(raw.sequence(), decoded));
    } catch (IOException failure) {
      failed = true;
      throw failure;
    } catch (IllegalArgumentException failure) {
      failed = true;
      throw new IOException(
          "Invalid protocol-68 demo message at record " + records.recordsRead(), failure);
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      records.close();
    }
  }
}
