package dev.bluevista.craftq3.engine.audio;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic WAV files and helpers shared by the audio tests. */
final class TestSounds {
  static final Vec3f FORWARD = new Vec3f(1, 0, 0);
  static final Vec3f LEFT = new Vec3f(0, 1, 0);
  static final Vec3f UP = new Vec3f(0, 0, 1);

  private TestSounds() {}

  /** A 16-bit PCM WAV file. */
  static byte[] wav16(int rate, int channels, short... samples) {
    ByteBuffer data = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (short sample : samples) data.putShort(sample);
    return wav(rate, channels, 16, data.array());
  }

  /** An 8-bit (unsigned) PCM WAV file. */
  static byte[] wav8(int rate, int channels, byte... samples) {
    return wav(rate, channels, 8, samples);
  }

  /** A mono 16-bit WAV of {@code frames} frames all equal to {@code value}. */
  static byte[] constant(int rate, int frames, int value) {
    short[] samples = new short[frames];
    Arrays.fill(samples, (short) value);
    return wav16(rate, 1, samples);
  }

  static byte[] wav(int rate, int channels, int bits, byte[] pcm) {
    ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0x46464952).putInt(36 + pcm.length).putInt(0x45564157);
    out.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) channels);
    out.putInt(rate).putInt(rate * channels * bits / 8);
    out.putShort((short) (channels * bits / 8)).putShort((short) bits);
    out.putInt(0x61746164).putInt(pcm.length).put(pcm);
    return out.array();
  }

  /** Collects warnings while {@link #close} has not been called. */
  static final class Warnings implements AutoCloseable {
    final List<String> messages = new ArrayList<>();
    private final Log.Sink previous;

    Warnings() {
      Log.resetOnce();
      previous =
          Log.install(
              (name, level, message, error) -> {
                if (level == Log.Level.WARN) {
                  synchronized (messages) {
                    messages.add(message);
                  }
                }
              });
    }

    long count(String fragment) {
      synchronized (messages) {
        return messages.stream().filter(message -> message.contains(fragment)).count();
      }
    }

    @Override
    public void close() {
      Log.install(previous);
      Log.resetOnce();
    }
  }
}
