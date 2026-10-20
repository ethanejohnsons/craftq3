package dev.bluevista.craftq3.engine.audio;

import java.util.Objects;

/**
 * Signed 16-bit interleaved PCM at its native rate, the format the {@link SoundSystem} hands to an
 * {@link AudioDevice}. The sample array is shared, not copied: neither side modifies it once the
 * buffer is constructed.
 */
public final class PcmBuffer {
  private final int sampleRate;
  private final int channels;
  private final short[] samples;

  public PcmBuffer(int sampleRate, int channels, short[] samples) {
    Objects.requireNonNull(samples, "samples");
    if (sampleRate < 1 || sampleRate > Pcm.MAX_RATE)
      throw new IllegalArgumentException("Unsupported PCM rate " + sampleRate);
    if (channels < 1 || channels > 2)
      throw new IllegalArgumentException("Unsupported PCM channel count " + channels);
    if (samples.length % channels != 0)
      throw new IllegalArgumentException("PCM data ends in a partial frame");
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.samples = samples;
  }

  public int sampleRate() {
    return sampleRate;
  }

  public int channels() {
    return channels;
  }

  public int frames() {
    return samples.length / channels;
  }

  /** The interleaved samples; callers must not modify them. */
  public short[] samples() {
    return samples;
  }

  /** Playing time in milliseconds, rounded down. */
  public long durationMillis() {
    return frames() * 1000L / sampleRate;
  }
}
