package dev.bluevista.craftq3.engine.net.demo;

import dev.bluevista.craftq3.engine.net.Protocol68;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Incremental, bounded {@code .dm_NN} record framing, identical for every protocol: a little-endian
 * message sequence, a little-endian length, then that many message bytes; a length of -1 ends the
 * demo. Protocol-specific message decoding is {@link DemoReader}.
 */
public final class DemoRecordReader implements AutoCloseable {
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
  private int sequence;
  private final byte[] header = new byte[HEADER_BYTES];
  private byte[] scratch;

  /** Sequence and length, both little-endian ints. */
  private static final int HEADER_BYTES = 8;

  /** The reader owns and closes this stream. */
  public DemoRecordReader(InputStream input) {
    this(input, Policy.STRICT);
  }

  /**
   * Playback preserves native completion at short input, exposing its precise reason through {@link
   * #end()}. Both policies reject unsafe lengths and propagate real stream I/O failures.
   */
  public DemoRecordReader(InputStream input, Policy policy) {
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
    if (scratch == null) scratch = new byte[Protocol68.MAX_MSGLEN];
    int length = readInto(scratch);
    if (length < 0) return Optional.empty();
    return Optional.of(new DemoRecord(sequence, Arrays.copyOf(scratch, length)));
  }

  /**
   * Reads the next record's payload into {@code buffer} (at least {@link Protocol68#MAX_MSGLEN}
   * bytes) without allocating. Returns its length, or -1 once the demo has ended; {@link
   * #sequence()} then names the record's message sequence.
   */
  public int readInto(byte[] buffer) throws IOException {
    if (closed || failed) throw new IOException("Demo reader is closed or failed");
    if (buffer.length < Protocol68.MAX_MSGLEN)
      throw new IllegalArgumentException("Demo buffer must hold 16384 bytes");
    if (end != End.NONE) return -1;
    try {
      int headerBytes = input.readNBytes(header, 0, HEADER_BYTES);
      if (headerBytes == 0) return finish(End.PHYSICAL_EOF);
      if (headerBytes != HEADER_BYTES) {
        if (policy == Policy.PLAYBACK) return finish(End.TRUNCATED_HEADER);
        throw new EOFException("Truncated demo record header");
      }
      int recordSequence = littleEndian(0), length = littleEndian(4);
      if (length == -1 && (recordSequence == -1 || policy == Policy.PLAYBACK))
        return finish(End.MARKER);
      if (length < 0 || length > Protocol68.MAX_MSGLEN)
        throw new IOException("Invalid demo record length " + length);
      if (input.readNBytes(buffer, 0, length) != length) {
        if (policy == Policy.PLAYBACK) return finish(End.TRUNCATED_PAYLOAD);
        throw new EOFException("Truncated demo record payload");
      }
      records++;
      sequence = recordSequence;
      return length;
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  /** Message sequence of the record most recently returned. */
  public int sequence() {
    return sequence;
  }

  private int finish(End reason) {
    end = reason;
    return -1;
  }

  private int littleEndian(int offset) {
    return (header[offset] & 255)
        | (header[offset + 1] & 255) << 8
        | (header[offset + 2] & 255) << 16
        | header[offset + 3] << 24;
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      input.close();
    }
  }
}
