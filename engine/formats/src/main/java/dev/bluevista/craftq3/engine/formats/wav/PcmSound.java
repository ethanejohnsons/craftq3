package dev.bluevista.craftq3.engine.formats.wav;

import java.util.Objects;

/**
 * Immutable interleaved PCM: unsigned 8-bit or signed little-endian 16-bit samples, mono or stereo,
 * at any positive rate. An empty sound is valid (the original mixer plays nothing).
 */
public final class PcmSound {
  public static final int MAX_BYTES = 64 * 1024 * 1024;
  public static final int MAX_RATE = 1_000_000;

  private final int sampleRate;
  private final int channels;
  private final int bits;
  private final byte[] pcm;

  public PcmSound(int sampleRate, int channels, int bits, byte[] pcm) {
    Objects.requireNonNull(pcm, "pcm");
    if (sampleRate < 1 || sampleRate > MAX_RATE)
      throw new IllegalArgumentException("Unsupported PCM rate " + sampleRate);
    if (channels < 1 || channels > 2)
      throw new IllegalArgumentException("Unsupported PCM channel count " + channels);
    if (bits != 8 && bits != 16)
      throw new IllegalArgumentException("Unsupported PCM depth " + bits);
    if (pcm.length > MAX_BYTES || pcm.length % (channels * bits / 8) != 0)
      throw new IllegalArgumentException("Incomplete or oversized PCM data");
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.bits = bits;
    this.pcm = pcm.clone();
  }

  public int sampleRate() {
    return sampleRate;
  }

  public int channels() {
    return channels;
  }

  public int bits() {
    return bits;
  }

  public int bytesPerFrame() {
    return channels * bits / 8;
  }

  public int frames() {
    return pcm.length / bytesPerFrame();
  }

  public double durationSeconds() {
    return frames() / (double) sampleRate;
  }

  public byte[] pcm() {
    return pcm.clone();
  }

  /** One sample widened to signed 16-bit range. */
  public int sample16(int frame, int channel) {
    Objects.checkIndex(frame, frames());
    Objects.checkIndex(channel, channels);
    int offset = (frame * channels + channel) * (bits / 8);
    return bits == 8
        ? ((pcm[offset] & 255) - 128) * 256
        : (short) ((pcm[offset] & 255) | (pcm[offset + 1] << 8));
  }

  /** Mono signed 16-bit little-endian samples, averaging stereo channels, without resampling. */
  public byte[] mono16le() {
    byte[] result = new byte[frames() * 2];
    for (int frame = 0; frame < frames(); frame++) {
      int sample = sample16(frame, 0);
      if (channels == 2) sample = (sample + sample16(frame, 1)) / 2;
      result[frame * 2] = (byte) sample;
      result[frame * 2 + 1] = (byte) (sample >> 8);
    }
    return result;
  }
}
