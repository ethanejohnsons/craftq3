package dev.bluevista.craftq3.core.demo;

import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

/** Incremental, bounded demo record framing; protocol-specific message decoding is separate. */
public final class DemoReader implements AutoCloseable {
  public enum Policy {
    STRICT,
    PLAYBACK
  }

  public enum End {
    NONE,
    MARKER,
    PHYSICAL_EOF,
    TRUNCATED_HEADER,
    TRUNCATED_PAYLOAD
  }

  private final InputStream input;
  private final Policy policy;
  private End end = End.NONE;
  private boolean failed, closed;
  private long records;

  /** The reader owns and closes this stream. */
  public DemoReader(InputStream input) {
    this(input, Policy.STRICT);
  }

  /**
   * Playback preserves native completion at short input, exposing its precise reason through {@link
   * #end()}. Both policies reject unsafe lengths and propagate real stream I/O failures.
   */
  public DemoReader(InputStream input, Policy policy) {
    this.input = Objects.requireNonNull(input);
    this.policy = Objects.requireNonNull(policy);
  }

  public End end() {
    return end;
  }

  public long recordsRead() {
    return records;
  }

  /** Physical EOF, a marker, and tolerated playback truncation have distinct completion reasons. */
  public Optional<DemoRecord> next() throws IOException {
    if (closed || failed) throw new IOException("Demo reader is closed or failed");
    if (end != End.NONE) return Optional.empty();
    try {
      byte[] header = input.readNBytes(8);
      if (header.length == 0) {
        end = End.PHYSICAL_EOF;
        return Optional.empty();
      }
      if (header.length != 8) {
        if (policy == Policy.PLAYBACK) {
          end = End.TRUNCATED_HEADER;
          return Optional.empty();
        }
        throw new EOFException("Truncated demo record header");
      }
      ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
      int sequence = fields.getInt(), length = fields.getInt();
      if (length == -1 && (sequence == -1 || policy == Policy.PLAYBACK)) {
        end = End.MARKER;
        return Optional.empty();
      }
      if (length < 0 || length > Protocol68Channel.MAX_MESSAGE)
        throw new IOException("Invalid demo record length " + length);
      byte[] payload = input.readNBytes(length);
      if (payload.length != length) {
        if (policy == Policy.PLAYBACK) {
          end = End.TRUNCATED_PAYLOAD;
          return Optional.empty();
        }
        throw new EOFException("Truncated demo record payload");
      }
      records++;
      return Optional.of(new DemoRecord(sequence, payload));
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      input.close();
    }
  }
}
