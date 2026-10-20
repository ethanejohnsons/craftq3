package dev.bluevista.craftq3.engine.client.demo;

import dev.bluevista.craftq3.engine.client.pipeline.DemoServerClock;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.net.demo.DemoProtocol;
import dev.bluevista.craftq3.engine.net.demo.DemoReader;
import dev.bluevista.craftq3.engine.net.demo.DemoRecordReader;
import dev.bluevista.craftq3.engine.net.demo.UnsupportedDemoProtocolException;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Plays a {@code .dm_48}/{@code .dm_66}/{@code .dm_67}/{@code .dm_68} demo into a {@link
 * SnapshotPipeline}: each record is decoded by {@link DemoReader} (playback policy, so a snapshot
 * whose delta base is lost is skipped rather than ending the demo) and fed to the pipeline, and the
 * {@link DemoServerClock} decides the cgame time and how far to read ahead each frame ({@code
 * CL_ReadDemoMessage}).
 *
 * <p>Protocol-48 demos (Quake III 1.25 and 1.27) decode into the same snapshot structures; {@link
 * #open} reports an unknown protocol with a user-facing {@link DemoException}. Owned by the render
 * thread.
 */
public final class DemoPlayback implements AutoCloseable {
  /** A demo that cannot be played; the message is meant for the user. */
  public static final class DemoException extends IOException {
    private static final long serialVersionUID = 1L;

    public DemoException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final String name;
  private final DemoReader reader;
  private final SnapshotPipeline pipeline;
  private final DemoServerClock clock = new DemoServerClock();
  private int realtime;
  private double remainder;
  private boolean ended;
  private boolean closed;

  private DemoPlayback(String name, DemoReader reader, SnapshotPipeline pipeline) {
    this.name = name;
    this.reader = reader;
    this.pipeline = pipeline;
  }

  /**
   * Opens a demo by its file name's protocol. The playback owns the stream.
   *
   * @throws DemoException for an unknown or unsupported protocol
   */
  public static DemoPlayback open(String fileName, InputStream input, SnapshotPipeline pipeline)
      throws IOException {
    Objects.requireNonNull(pipeline);
    try {
      DemoReader reader = DemoReader.open(fileName, input, DemoRecordReader.Policy.PLAYBACK);
      pipeline.reset();
      return new DemoPlayback(fileName, reader, pipeline);
    } catch (UnsupportedDemoProtocolException unsupported) {
      throw new DemoException(
          "Cannot play " + fileName + ": " + unsupported.getMessage(), unsupported);
    }
  }

  public String name() {
    return name;
  }

  public DemoProtocol protocol() {
    return reader.protocol();
  }

  public DemoServerClock clock() {
    return clock;
  }

  public SnapshotPipeline pipeline() {
    return pipeline;
  }

  public boolean ended() {
    return ended;
  }

  /** How the demo stream ended (a marker, or a truncated file). */
  public DemoRecordReader.End end() {
    return reader.end();
  }

  public long recordsRead() {
    return reader.recordsRead();
  }

  /** Reads records until the first gamestate, before the cgame is loaded. */
  public void prime() throws IOException {
    for (int i = 0; i < DemoServerClock.MAX_READS_PER_FRAME; i++) {
      read();
      if (pipeline.gameState().isPresent()) return;
      if (ended)
        throw new DemoException("Demo " + name + " ended before its first gamestate", null);
    }
    throw new DemoException("Demo " + name + " has no gamestate in its first records", null);
  }

  /**
   * Advances the demo by one client frame and returns the cgame time, or empty while the demo is
   * still priming, at a gamestate change or at its end.
   *
   * @param elapsedMillis real milliseconds since the previous frame (scaled by {@code timescale})
   * @param timedemo play every frame 50 ms apart regardless of real time ({@code timedemo 1})
   * @param wallMillis the wall clock, only for timedemo statistics
   */
  public OptionalInt frame(
      int elapsedMillis,
      int timeNudge,
      float timescale,
      boolean freeze,
      boolean timedemo,
      int wallMillis)
      throws IOException {
    if (closed) throw new IOException("Demo playback is closed");
    float scale = Float.isFinite(timescale) ? Math.clamp(timescale, 0, 1000) : 1;
    double scaled = Math.clamp(elapsedMillis, 0, 200) * (double) scale + remainder;
    int elapsed = (int) scaled;
    remainder = scaled - elapsed;
    realtime = Math.addExact(realtime, elapsed);
    return clock.frame(realtime, timeNudge, scale, freeze, timedemo, wallMillis, this::read);
  }

  /** Reads one record into the pipeline. */
  private void read() throws IOException {
    if (ended) return;
    Optional<DemoReader.DecodedRecord> next = reader.next();
    if (next.isEmpty()) {
      ended = true;
      clock.end();
      return;
    }
    DemoReader.DecodedRecord record = next.get();
    pipeline.accept(record.sequence(), record.message(), null, ignored -> 0);
    for (ServerMessageCodec.Operation operation : record.message().operations()) {
      if (operation instanceof ServerMessageCodec.GameState) clock.gamestate();
      else if (operation instanceof ServerMessageCodec.Frame frame)
        clock.snapshot(frame.current().time(), frame.current().flags());
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    reader.close();
  }
}
