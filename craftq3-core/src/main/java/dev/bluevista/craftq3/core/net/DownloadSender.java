package dev.bluevista.craftq3.core.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Bounded protocol-68 download window over an owned stream; no paths or sockets. */
public final class DownloadSender implements AutoCloseable {
  public static final int BLOCK_BYTES = 1024, WINDOW = 48;
  public static final int MAX_FILE_BYTES = 512 * 1024 * 1024;

  private record Block(int number, byte[] bytes) {}

  private final InputStream input;
  private final int size;
  private final ArrayDeque<Block> window = new ArrayDeque<>();
  private int read, nextBlock, transmit, highestSent;
  private long lastSend;
  private boolean eof, closed, complete;

  public DownloadSender(InputStream input, int size) {
    if (size < 0 || size > MAX_FILE_BYTES)
      throw new IllegalArgumentException("Download size exceeds budget");
    this.input = Objects.requireNonNull(input);
    this.size = size;
  }

  public Optional<ServerMessageCodec.Download> next(long now) throws IOException {
    if (closed) return Optional.empty();
    while (window.size() < WINDOW && !eof) {
      if (read == size) {
        if (input.read() != -1) throw new IOException("Download source grew during transfer");
        window.addLast(new Block(nextBlock++, new byte[0]));
        eof = true;
      } else {
        int count = Math.min(BLOCK_BYTES, size - read);
        byte[] data = input.readNBytes(count);
        if (data.length != count) throw new IOException("Download source ended early");
        window.addLast(new Block(nextBlock++, data));
        read += count;
      }
    }
    Block block = null;
    for (var candidate : window)
      if (candidate.number() >= transmit) {
        block = candidate;
        break;
      }
    if (block == null) {
      if (window.isEmpty() || now - lastSend <= 1000) return Optional.empty();
      block = window.getFirst();
    }
    transmit = block.number() + 1;
    highestSent = Math.max(highestSent, transmit);
    lastSend = now;
    return Optional.of(
        new ServerMessageCodec.Download(
            block.number() & 65535,
            block.number() == 0 ? Integer.valueOf(size) : null,
            block.bytes(),
            null));
  }

  public void acknowledge(int block, long now) throws IOException {
    if (closed) return;
    if (window.isEmpty() || block != window.getFirst().number() || block >= highestSent)
      throw new IOException("Broken download acknowledgement");
    var accepted = window.removeFirst();
    lastSend = now;
    if (accepted.bytes().length == 0) {
      complete = true;
      close();
    }
  }

  public boolean complete() {
    return complete;
  }

  public int size() {
    return size;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    window.clear();
    input.close();
  }
}
