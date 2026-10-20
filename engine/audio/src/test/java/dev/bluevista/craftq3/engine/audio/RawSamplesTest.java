package dev.bluevista.craftq3.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import dev.bluevista.craftq3.engine.formats.wav.PcmSound;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawSamplesTest {
  private static byte[] le16(int... samples) {
    ByteBuffer out = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int sample : samples) out.putShort((short) sample);
    return out.array();
  }

  @Test
  void sixteenBitSamplesPlayInOrderAtTheGivenVolume() {
    var mixer = new SoftwareMixer(22050, 4);
    var sound = new SoundSystem(new MemoryAssets(), mixer, SoundSystem.Limits.DEFAULT);
    sound.rawSamples(4, 22050, 2, 1, le16(100, -200, 300, -400), 1f);
    sound.rawSamples(2, 22050, 2, 1, le16(1000, 2000), 0.5f);
    assertEquals(6, sound.rawSamplesQueued());
    short[] out = new short[8 * 2];
    mixer.mix(out, 8);
    // The gain applies to the whole stream at mix time; the last call set it to one half.
    short[] expected = {50, -100, 150, -200, 500, 1000, 0, 0};
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], out[i * 2], "left " + i);
      assertEquals(expected[i], out[i * 2 + 1], "right " + i);
    }
    assertEquals(0, sound.rawSamplesQueued());
  }

  @Test
  void stereoAndEightBitSamplesConvert() {
    var mixer = new SoftwareMixer(22050, 4);
    var sound = new SoundSystem(new MemoryAssets(), mixer, SoundSystem.Limits.DEFAULT);
    sound.rawSamples(2, 22050, 1, 2, new byte[] {64, -64, 1, 0}, 1f);
    short[] out = new short[2 * 2];
    mixer.mix(out, 2);
    assertEquals(16384, out[0]);
    assertEquals(-16384, out[1]);
    assertEquals(256, out[2]);
    assertEquals(0, out[3]);
  }

  @Test
  void aFormatChangeRestartsTheStream() {
    var device = new NullAudioDevice(4, true);
    var sound = new SoundSystem(new MemoryAssets(), device, SoundSystem.Limits.DEFAULT);
    sound.rawSamples(10, 22050, 2, 1, new byte[20], 1f);
    sound.rawSamples(10, 22050, 2, 1, new byte[20], 1f);
    sound.rawSamples(10, 44100, 2, 2, new byte[40], 1f);
    assertEquals(
        List.of("openStream 1 rate=22050 channels=1", "openStream 2 rate=44100 channels=2"),
        device.calls("openStream"));
    assertEquals(List.of("closeStream 1"), device.calls("closeStream"));
    assertEquals(10, sound.rawSamplesQueued());
  }

  @Test
  void blocksFarAheadOfPlaybackAreDropped() {
    var device = new NullAudioDevice(4, false);
    var sound = new SoundSystem(new MemoryAssets(), device, SoundSystem.Limits.DEFAULT);
    byte[] second = new byte[22050 * 2];
    try (var warnings = new TestSounds.Warnings()) {
      for (int i = 0; i < 5; i++) sound.rawSamples(22050, 22050, 2, 1, second, 1f);
      assertEquals(22050 * 4, sound.rawSamplesQueued());
      assertEquals(1, warnings.count("far ahead"));
      device.advance(1000);
      sound.rawSamples(22050, 22050, 2, 1, second, 1f);
      assertEquals(22050 * 4, sound.rawSamplesQueued());
    }
  }

  @Test
  void decodedBlocksQueueDirectly() {
    var device = new NullAudioDevice(4, true);
    var sound = new SoundSystem(new MemoryAssets(), device, SoundSystem.Limits.DEFAULT);
    sound.rawSamples(new PcmSound(22050, 2, 16, new byte[4 * 100]), 1f);
    assertEquals(100, sound.rawSamplesQueued());
    sound.stopRawSamples();
    assertEquals(0, sound.rawSamplesQueued());
    assertEquals(0, device.streamCount());
  }

  @Test
  void malformedBlocksAreIgnored() {
    var device = new NullAudioDevice(4, true);
    var sound = new SoundSystem(new MemoryAssets(), device, SoundSystem.Limits.DEFAULT);
    sound.rawSamples(10, 22050, 2, 1, new byte[5], 1f);
    sound.rawSamples(10, 22050, 3, 1, new byte[30], 1f);
    sound.rawSamples(10, 0, 2, 1, new byte[20], 1f);
    sound.rawSamples(0, 22050, 2, 1, new byte[0], 1f);
    assertTrue(device.calls("openStream").isEmpty());
  }
}
