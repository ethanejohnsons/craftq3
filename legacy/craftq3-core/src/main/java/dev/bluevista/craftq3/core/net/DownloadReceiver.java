package dev.bluevista.craftq3.core.net;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * A requested download writes only to its owner's staging sink; verification/commit is external.
 */
public final class DownloadReceiver implements AutoCloseable {
  public record Progress(
      boolean accepted, int acknowledge, boolean complete, int received, int size) {}

  private final OutputStream output;
  private int expected, received, size = -1;
  private boolean complete, closed;

  public DownloadReceiver(OutputStream output) {
    this.output = Objects.requireNonNull(output);
  }

  public Progress accept(ServerMessageCodec.Download packet) throws IOException {
    if (closed || complete) throw new IOException("Download is no longer receiving");
    if (packet.block() != (expected & 65535)) return progress(false, -1);
    if (packet.error() != null) throw new IOException("Server download: " + packet.error());
    if (expected == 0) {
      if (packet.fileSize() == null
          || packet.fileSize() < 0
          || packet.fileSize() > DownloadSender.MAX_FILE_BYTES)
        throw new IOException("Download size is missing or exceeds budget");
      size = packet.fileSize();
    } else if (packet.fileSize() != null) throw new IOException("Unexpected download size header");
    byte[] data = packet.data();
    if (data.length > size - received) throw new IOException("Download exceeds advertised size");
    if (data.length == 0) {
      if (received != size) throw new IOException("Download ended before advertised size");
      output.flush();
      complete = true;
    } else {
      output.write(data);
      received += data.length;
    }
    return progress(true, expected++);
  }

  private Progress progress(boolean accepted, int acknowledge) {
    return new Progress(accepted, acknowledge, complete, received, size);
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    output.close();
  }
}
