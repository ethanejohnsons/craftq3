package dev.bluevista.craftq3.engine.audio;

import dev.bluevista.craftq3.engine.formats.wav.PcmSound;

/**
 * Conversions from the formats sounds arrive in (WAV: unsigned 8-bit or signed 16-bit; raw
 * cinematic samples: signed 8-bit or signed 16-bit) to signed 16-bit interleaved samples. Rates are
 * never changed here: devices accept native rates, and only the {@link SoftwareMixer} resamples,
 * while it mixes.
 */
public final class Pcm {
  /** The highest sample rate a buffer or stream may use. */
  public static final int MAX_RATE = PcmSound.MAX_RATE;

  private Pcm() {}

  /** All channels of {@code sound}, interleaved, widened to signed 16-bit. */
  public static short[] interleaved16(PcmSound sound) {
    byte[] pcm = sound.pcm();
    int count = sound.frames() * sound.channels();
    short[] out = new short[count];
    if (sound.bits() == 8) {
      for (int i = 0; i < count; i++) out[i] = unsigned8(pcm[i]);
    } else {
      for (int i = 0; i < count; i++) out[i] = little16(pcm, i * 2);
    }
    return out;
  }

  /**
   * {@code sound} as mono signed 16-bit, averaging stereo frames. Spatialised voices are mono: a 3D
   * device can only position single-channel sources.
   */
  public static short[] mono16(PcmSound sound) {
    if (sound.channels() == 1) return interleaved16(sound);
    short[] stereo = interleaved16(sound);
    short[] out = new short[stereo.length / 2];
    for (int i = 0; i < out.length; i++)
      out[i] = (short) ((stereo[i * 2] + stereo[i * 2 + 1]) >> 1);
    return out;
  }

  /**
   * Converts {@code frames} raw frames of {@code width} bytes per sample and {@code channels}
   * channels from {@code data} into {@code out}, which must hold {@code frames * channels} samples.
   * One-byte samples are signed, as the raw-sample interface defines them; two-byte samples are
   * signed little-endian.
   */
  public static void raw16(byte[] data, int frames, int width, int channels, short[] out) {
    if (width != 1 && width != 2) throw new IllegalArgumentException("Sample width " + width);
    if (channels != 1 && channels != 2) throw new IllegalArgumentException("Channels " + channels);
    int count = frames * channels;
    if (data.length < count * width || out.length < count)
      throw new IllegalArgumentException("Raw sample buffer too small");
    if (width == 1) {
      for (int i = 0; i < count; i++) out[i] = (short) (data[i] << 8);
    } else {
      for (int i = 0; i < count; i++) out[i] = little16(data, i * 2);
    }
  }

  static short unsigned8(byte value) {
    return (short) (((value & 0xff) - 128) << 8);
  }

  static short little16(byte[] data, int offset) {
    return (short) ((data[offset] & 0xff) | (data[offset + 1] << 8));
  }
}
