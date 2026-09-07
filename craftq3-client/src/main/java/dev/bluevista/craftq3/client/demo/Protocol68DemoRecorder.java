package dev.bluevista.craftq3.client.demo;

import dev.bluevista.craftq3.core.demo.DemoRecord;
import dev.bluevista.craftq3.core.demo.DemoWriter;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Message;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * Native-observed protocol-68 recording lifecycle. The host owns eligibility, filenames, transport
 * decryption and requesting a full snapshot; this recorder owns only its bounded output stream.
 */
public final class Protocol68DemoRecorder implements AutoCloseable {
  public enum State {
    NEW,
    WAITING,
    RECORDING,
    FINISHED,
    FAILED,
    CLOSED
  }

  private final OutputStream output;
  private final long maxBytes;
  private DemoWriter writer;
  private State state = State.NEW;
  private long recordsWritten, bytesWritten;

  /**
   * Owns the stream. The explicit byte budget includes record headers and the final eight bytes.
   */
  public Protocol68DemoRecorder(OutputStream output, long maxBytes) {
    this.output = Objects.requireNonNull(output);
    if (maxBytes < 8)
      throw new IllegalArgumentException("Demo byte budget must include its end marker");
    this.maxBytes = maxBytes;
  }

  public State state() {
    return state;
  }

  /** Number of completely written records, including the synthesized initial gamestate. */
  public long recordsWritten() {
    return recordsWritten;
  }

  /** Completely written framing/payload bytes; a failed stream may also contain a partial write. */
  public long bytesWritten() {
    return bytesWritten;
  }

  /**
   * Writes current gamestate at server sequence minus one, then waits for a full snapshot. Native
   * recording omits baseline number zero even if it has other nonzero fields. The reliable value is
   * the client's current reliable sequence, not its last server acknowledgement.
   */
  public void start(GameState game, int currentServerSequence, int clientReliableSequence)
      throws IOException {
    if (state != State.NEW)
      throw new IllegalStateException("Demo recorder has already started or closed");
    Objects.requireNonNull(game);
    var baselines =
        new Baselines(
            game.baselines().entities().stream()
                .filter(
                    entity -> ByteBuffer.wrap(entity).order(ByteOrder.LITTLE_ENDIAN).getInt() != 0)
                .toList());
    var initial =
        new GameState(
            game.commandSequence(),
            game.configstrings(),
            baselines,
            game.clientNumber(),
            game.checksumFeed());
    var encoded = new MessageWriter();
    ServerMessageCodec.write(
        encoded, new Message(clientReliableSequence, List.of(initial)), Baselines.EMPTY);
    var record = new DemoRecord(currentServerSequence - 1, encoded.bytes());
    checkBudget(record);
    writer = new DemoWriter(output);
    append(record);
    state = State.WAITING;
  }

  /**
   * Called after successful wire parsing with the matching decrypted payload, without netchan
   * headers. While waiting, command-only and delta-only packets are discarded. The entire packet
   * containing the first full snapshot is recorded, including preceding commands and untouched
   * padding/trailing bytes. Later gamestates do not re-arm the wait.
   */
  public boolean accept(DemoRecord record, Message decoded) throws IOException {
    if (state == State.FAILED) throw new IOException("Demo recorder stream has failed");
    if (state != State.WAITING && state != State.RECORDING)
      throw new IllegalStateException("Demo recorder is not active");
    Objects.requireNonNull(record);
    Objects.requireNonNull(decoded);
    boolean full = false;
    for (var operation : decoded.operations()) {
      if (operation instanceof Frame frame) {
        if (frame.current().sequence() != record.sequence())
          throw new IllegalArgumentException(
              "Decoded snapshot belongs to another message sequence");
        full |= frame.previous() == null;
      }
    }
    if (state == State.WAITING && !full) return false;
    checkBudget(record);
    append(record);
    state = State.RECORDING;
    return true;
  }

  /**
   * Writes and flushes one canonical -1/-1 end marker. Repeated calls, including before start, are
   * harmless. Pre-write byte-budget rejection leaves enough room to finish the valid prefix.
   */
  public void finish() throws IOException {
    if (state == State.NEW || state == State.FINISHED || state == State.CLOSED) return;
    if (state == State.FAILED) throw new IOException("Demo recorder stream has failed");
    try {
      writer.finish();
      bytesWritten += 8;
      state = State.FINISHED;
    } catch (IOException failure) {
      state = State.FAILED;
      throw failure;
    }
  }

  @Override
  public void close() throws IOException {
    if (state == State.CLOSED) return;
    try {
      if (writer == null) output.close();
      else {
        try {
          if (state != State.FAILED) finish();
        } finally {
          writer.close();
        }
      }
    } finally {
      state = State.CLOSED;
    }
  }

  private void checkBudget(DemoRecord record) throws IOException {
    long bytes = 8L + record.payload().length;
    if (bytes > maxBytes - bytesWritten - 8)
      throw new IOException("Demo recording byte budget exceeded");
  }

  private void append(DemoRecord record) throws IOException {
    try {
      writer.append(record);
      bytesWritten += 8L + record.payload().length;
      recordsWritten++;
    } catch (IOException failure) {
      state = State.FAILED;
      throw failure;
    }
  }
}
