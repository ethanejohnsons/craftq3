package dev.bluevista.craftq3.assets.audio;

/** Immutable interleaved PCM: unsigned 8-bit or signed little-endian 16-bit samples. */
public final class PcmSound {
  private final int sampleRate, channels, bits;
  private final byte[] pcm;

  public PcmSound(int sampleRate, int channels, int bits, byte[] pcm) {
    if (sampleRate < 1000
        || sampleRate > 192000
        || channels < 1
        || channels > 2
        || (bits != 8 && bits != 16)
        || pcm.length == 0
        || pcm.length > 64 * 1024 * 1024
        || pcm.length % (channels * bits / 8) != 0)
      throw new IllegalArgumentException("Unsupported or incomplete PCM format");
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

  public int frames() {
    return pcm.length / (channels * bits / 8);
  }

  public double durationSeconds() {
    return frames() / (double) sampleRate;
  }

  public byte[] pcm() {
    return pcm.clone();
  }

  public int sample16(int frame, int channel) {
    if (frame < 0 || frame >= frames() || channel < 0 || channel >= channels)
      throw new IndexOutOfBoundsException("PCM sample outside sound");
    int offset = (frame * channels + channel) * (bits / 8);
    return bits == 8
        ? ((pcm[offset] & 255) - 128) * 256
        : (short) ((pcm[offset] & 255) | (pcm[offset + 1] << 8));
  }

  /** Positional source conversion, without resampling or host sound-event/resource identifiers. */
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
