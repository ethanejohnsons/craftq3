package dev.bluevista.craftq3.platform.audio;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;

/** Bounded producer/consumer PCM queue, with exact sample continuity and explicit backpressure. */
public final class PcmQueue implements PcmStream {
  private final Format format;
  private final int capacity;
  private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
  private int queued, offset;
  private long submitted, consumed;
  private boolean finished, closed, started;
  private long startNanos;

  public PcmQueue(Format format, int capacityFrames) {
    this.format = Objects.requireNonNull(format);
    long bytes = (long) capacityFrames * format.frameBytes();
    if (capacityFrames < format.sampleRate() / 4 || bytes > 16 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid PCM queue capacity");
    capacity = (int) bytes;
  }

  @Override
  public Format format() {
    return format;
  }

  public synchronized long submittedFrames() {
    return submitted;
  }

  public synchronized long consumedFrames() {
    return consumed;
  }

  public synchronized int queuedFrames() {
    return queued / format.frameBytes();
  }

  public synchronized boolean closed() {
    return closed;
  }

  @Override
  public synchronized void started(long nanos) {
    checkOpen();
    if (started) throw new IllegalStateException("PCM stream already started");
    startNanos = nanos;
    started = true;
  }

  public synchronized java.util.OptionalLong startedAtNanos() {
    return started ? java.util.OptionalLong.of(startNanos) : java.util.OptionalLong.empty();
  }

  public synchronized boolean producerFinished() {
    return finished;
  }

  /** False leaves both sample position and data unchanged so the producer can retry later. */
  public synchronized boolean offer(long firstSample, PcmSound sound) {
    checkOpen();
    if (finished) throw new IllegalStateException("PCM stream is finished");
    if (firstSample != submitted
        || sound.sampleRate() != format.sampleRate()
        || sound.channels() != format.channels()
        || sound.bits() != format.bits())
      throw new IllegalArgumentException("Discontinuous PCM samples or format change");
    long bytes = (long) sound.frames() * format.frameBytes();
    if (bytes > capacity - queued) return false;
    chunks.addLast(sound.pcm());
    queued += (int) bytes;
    submitted += sound.frames();
    return true;
  }

  /** Worker-only backpressure; native audio reads never wait for the producer. */
  public synchronized void put(long firstSample, PcmSound sound) throws InterruptedException {
    if ((long) sound.frames() * format.frameBytes() > capacity)
      throw new IllegalArgumentException("PCM block exceeds queue capacity");
    while (!offer(firstSample, sound)) wait();
  }

  public synchronized void finish() {
    checkOpen();
    finished = true;
    notifyAll();
  }

  @Override
  public synchronized boolean ready() {
    return !closed && queued > 0 && (finished || queuedFrames() >= format.sampleRate() / 4);
  }

  @Override
  public synchronized boolean exhausted() {
    return finished && queued == 0;
  }

  @Override
  public synchronized byte[] read(int maximumBytes) throws IOException {
    if (closed) throw new IOException("PCM stream is closed");
    int frame = format.frameBytes();
    if (maximumBytes < frame)
      throw new IllegalArgumentException("PCM read must fit one sample frame");
    if (queued == 0 && !finished) throw new IOException("PCM stream underrun");
    // Four short native buffers give bounded output latency without blocking during a refill.
    int size =
        Math.min(
            queued, Math.min(maximumBytes / frame, Math.max(1, format.sampleRate() / 20)) * frame);
    byte[] result = new byte[size];
    int copied = 0;
    while (copied < size) {
      var head = chunks.getFirst();
      int count = Math.min(size - copied, head.length - offset);
      System.arraycopy(head, offset, result, copied, count);
      copied += count;
      offset += count;
      if (offset == head.length) {
        chunks.removeFirst();
        offset = 0;
      }
    }
    queued -= size;
    consumed += size / frame;
    notifyAll();
    return result;
  }

  @Override
  public synchronized void close() {
    closed = true;
    chunks.clear();
    queued = offset = 0;
    notifyAll();
  }

  private void checkOpen() {
    if (closed) throw new IllegalStateException("PCM queue is closed");
  }
}
