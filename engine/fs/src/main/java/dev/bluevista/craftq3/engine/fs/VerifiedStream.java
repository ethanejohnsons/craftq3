package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Wraps an entry stream so no read or skip can pass its declared size, and checks the length (and
 * the CRC, when one is known) at end of stream. Closing early is allowed. Owned by one thread.
 */
final class VerifiedStream extends InputStream {
  static final long NO_CRC = -1;

  private final InputStream input;
  private final long size;
  private final long expectedCrc;
  private final String name;
  private final CRC32 crc = new CRC32();
  private long count;
  private boolean ended, closed;

  VerifiedStream(InputStream input, long size, int crc, String name) {
    this(input, size, Integer.toUnsignedLong(crc), name);
  }

  VerifiedStream(InputStream input, long size, long expectedCrc, String name) {
    this.input = input;
    this.size = size;
    this.expectedCrc = expectedCrc;
    this.name = name;
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (closed) throw new IOException("Stream is closed: " + name);
    if (length == 0) return 0;
    if (ended) return -1;
    int read = input.read(bytes, offset, (int) Math.min(length, size - count + 1));
    if (read < 0) {
      ended = true;
      if (count != size) throw reject("Size mismatch (" + count + " of " + size + "): " + name);
      if (expectedCrc != NO_CRC && crc.getValue() != expectedCrc)
        throw reject("CRC mismatch: " + name);
      return -1;
    }
    count += read;
    if (count > size) throw reject("Grew beyond its declared size: " + name);
    crc.update(bytes, offset, read);
    return read;
  }

  private IOException reject(String message) {
    var failure = new IOException(message);
    try {
      close();
    } catch (IOException cleanup) {
      failure.addSuppressed(cleanup);
    }
    return failure;
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      input.close();
    }
  }
}
