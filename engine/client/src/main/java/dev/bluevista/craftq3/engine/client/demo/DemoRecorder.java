package dev.bluevista.craftq3.engine.client.demo;

import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.net.demo.DemoRecord;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Records the message stream of a {@link SnapshotPipeline} as a {@code .dm_68} demo ({@code
 * record}): a synthesized gamestate of the current level first (its configstrings with every
 * received update applied, the baselines, the newest reliable command sequence), then every
 * accepted message's clear payload from the first full (non-delta) snapshot on. While it waits for
 * that snapshot it asks the connection for full snapshots.
 *
 * <p>Only sources with clear payloads (live connections) can be recorded. Owned by the render
 * thread.
 */
public final class DemoRecorder implements SnapshotPipeline.Listener, AutoCloseable {
  /** Bytes one recording may reach. */
  public static final long MAX_BYTES = 1L << 30;

  private final SnapshotPipeline pipeline;
  private final Protocol68DemoRecorder recorder;
  private final Consumer<Boolean> fullSnapshots;
  private IOException failure;

  private DemoRecorder(
      SnapshotPipeline pipeline, Protocol68DemoRecorder recorder, Consumer<Boolean> fullSnapshots) {
    this.pipeline = pipeline;
    this.recorder = recorder;
    this.fullSnapshots = fullSnapshots;
  }

  /**
   * Starts recording the pipeline's current level into {@code output} (owned by the recorder).
   *
   * @param clientReliableSequence the connection's reliable command sequence
   * @param fullSnapshots asks the connection for full snapshots while true
   * @throws IllegalStateException when there is no level to record
   */
  public static DemoRecorder start(
      SnapshotPipeline pipeline,
      OutputStream output,
      int clientReliableSequence,
      Consumer<Boolean> fullSnapshots)
      throws IOException {
    Objects.requireNonNull(pipeline);
    Objects.requireNonNull(fullSnapshots);
    ServerMessageCodec.GameState level =
        pipeline
            .gameState()
            .orElseThrow(() -> new IllegalStateException("Not connected to a level"));
    var current =
        new ServerMessageCodec.GameState(
            pipeline.serverCommandSequence(),
            pipeline.configStrings(),
            level.baselines(),
            level.clientNumber(),
            level.checksumFeed());
    Protocol68DemoRecorder recorder = new Protocol68DemoRecorder(output, MAX_BYTES);
    recorder.start(current, pipeline.lastMessage(), clientReliableSequence);
    DemoRecorder demo = new DemoRecorder(pipeline, recorder, fullSnapshots);
    fullSnapshots.accept(true);
    pipeline.addListener(demo);
    return demo;
  }

  public Protocol68DemoRecorder.State state() {
    return recorder.state();
  }

  public long recordsWritten() {
    return recorder.recordsWritten();
  }

  /** The write failure that stopped the recording, if any. */
  public java.util.Optional<IOException> failure() {
    return java.util.Optional.ofNullable(failure);
  }

  @Override
  public void message(int sequence, ServerMessageCodec.Message message, byte[] payload) {
    if (payload == null || failure != null) return;
    try {
      boolean waiting = recorder.state() == Protocol68DemoRecorder.State.WAITING;
      recorder.accept(new DemoRecord(sequence, payload), message);
      if (waiting && recorder.state() == Protocol68DemoRecorder.State.RECORDING)
        fullSnapshots.accept(false);
    } catch (IOException e) {
      failure = e;
      pipeline.removeListener(this);
    }
  }

  /** {@code stoprecord}: writes the end marker and closes the file. */
  @Override
  public void close() throws IOException {
    pipeline.removeListener(this);
    fullSnapshots.accept(false);
    recorder.close();
  }
}
