package dev.bluevista.craftq3.platform.audio;

import java.io.IOException;

/** Nonblocking PCM source. Ownership transfers to the audio backend on stream submission. */
public interface PcmStream extends AutoCloseable {
  record Format(int sampleRate, int channels, int bits) {
    public Format {
      if (sampleRate < 1000
          || sampleRate > 192000
          || channels < 1
          || channels > 2
          || bits != 8 && bits != 16)
        throw new IllegalArgumentException("Invalid streaming PCM format");
    }

    public int frameBytes() {
      return channels * bits / 8;
    }
  }

  Format format();

  /** Native play started, in the host System.nanoTime domain. Called once on the sound executor. */
  default void started(long nanos) {}

  /** Enough prefetched samples to start without blocking the sound executor. */
  boolean ready();

  /** True only when all submitted samples have been read and no more will arrive. */
  boolean exhausted();

  /**
   * Owned, frame-aligned bytes, up to maximumBytes; empty only at EOF. Must never block for data.
   */
  byte[] read(int maximumBytes) throws IOException;

  @Override
  void close();
}
