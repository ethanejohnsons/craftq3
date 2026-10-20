package dev.bluevista.craftq3.core.demo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Streaming demo record framing. Does not synthesize a game state or choose message protocols. */
public final class DemoWriter implements AutoCloseable {
  private final OutputStream output;
  private boolean finished, failed, closed;

  /** The writer owns and closes this stream. */
  public DemoWriter(OutputStream output) {
    this.output = Objects.requireNonNull(output);
  }

  public void append(DemoRecord record) throws IOException {
    Objects.requireNonNull(record);
    writable();
    byte[] payload = record.payload();
    try {
      output.write(header(record.sequence(), payload.length));
      output.write(payload);
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  /** Writes one canonical end marker and flushes; repeated calls are harmless. */
  public void finish() throws IOException {
    if (finished) return;
    writable();
    try {
      output.write(header(-1, -1));
      output.flush();
      finished = true;
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    try {
      if (!failed) finish();
    } finally {
      closed = true;
      output.close();
    }
  }

  private void writable() throws IOException {
    if (finished || failed || closed)
      throw new IOException("Demo writer is finished, failed or closed");
  }

  private static byte[] header(int sequence, int length) {
    return ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(sequence)
        .putInt(length)
        .array();
  }
}
