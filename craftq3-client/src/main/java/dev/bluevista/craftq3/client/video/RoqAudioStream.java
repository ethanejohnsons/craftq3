package dev.bluevista.craftq3.client.video;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.platform.audio.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bounded soundtrack producer independent of video presentation and native audio refills. */
public final class RoqAudioStream implements PcmStream {
  private final PcmQueue queue;
  private final InputStream input;
  private final Thread worker;
  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private volatile boolean closed;
  private volatile IOException failure;

  public RoqAudioStream(RoqPlayback.Source source, Format format) throws IOException {
    queue = new PcmQueue(format, format.sampleRate() * 4);
    input = source.open();
    worker = Thread.ofVirtual().name("CraftQ3-RoQ-audio").unstarted(this::produce);
    try {
      worker.start();
    } catch (RuntimeException error) {
      input.close();
      throw error;
    }
  }

  private void produce() {
    try (var decoder = new RoqDecoder(input)) {
      while (!closed) {
        var next = decoder.next();
        if (next.isEmpty()) {
          queue.finish();
          return;
        }
        if (!(next.get() instanceof RoqDecoder.Audio audio)) continue;
        var sound = audio.sound();
        var format = format();
        if (sound.sampleRate() != format.sampleRate()
            || sound.channels() != format.channels()
            || sound.bits() != format.bits()) throw new IOException("RoQ audio format changed");
        byte[] pcm = sound.pcm();
        int frameBytes = format.frameBytes();
        int blockFrames = Math.max(1, format.sampleRate() / 10);
        for (int offset = 0; offset < sound.frames() && !closed; offset += blockFrames) {
          int count = Math.min(blockFrames, sound.frames() - offset);
          queue.put(
              audio.firstSample() + offset,
              new PcmSound(
                  format.sampleRate(),
                  format.channels(),
                  format.bits(),
                  Arrays.copyOfRange(pcm, offset * frameBytes, (offset + count) * frameBytes)));
        }
      }
    } catch (IOException | RuntimeException | InterruptedException error) {
      if (!closed) failure = new IOException("RoQ soundtrack decoding failed", error);
    } finally {
      completion.complete(null);
    }
  }

  public Optional<IOException> failure() {
    return Optional.ofNullable(failure);
  }

  public CompletableFuture<Void> completion() {
    return completion;
  }

  public long submittedFrames() {
    return queue.submittedFrames();
  }

  public long consumedFrames() {
    return queue.consumedFrames();
  }

  public boolean closed() {
    return closed;
  }

  public boolean producerFinished() {
    return queue.producerFinished();
  }

  public OptionalLong startedAtNanos() {
    return queue.startedAtNanos();
  }

  @Override
  public Format format() {
    return queue.format();
  }

  @Override
  public void started(long nanos) {
    queue.started(nanos);
  }

  @Override
  public boolean ready() {
    return !closed && (failure != null || queue.ready());
  }

  @Override
  public boolean exhausted() {
    return failure == null && queue.exhausted();
  }

  @Override
  public byte[] read(int maximumBytes) throws IOException {
    if (failure != null) throw failure;
    return queue.read(maximumBytes);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    queue.close();
    worker.interrupt();
    try {
      input.close();
    } catch (IOException error) {
      failure = error;
    }
  }
}
