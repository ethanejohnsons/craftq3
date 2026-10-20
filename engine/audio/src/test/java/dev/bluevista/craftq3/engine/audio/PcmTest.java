package dev.bluevista.craftq3.engine.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.bluevista.craftq3.engine.formats.wav.PcmSound;
import dev.bluevista.craftq3.engine.formats.wav.WavReader;
import org.junit.jupiter.api.Test;

class PcmTest {
  @Test
  void unsignedEightBitWavWidensToSixteen() {
    PcmSound sound = WavReader.read(TestSounds.wav8(11025, 1, (byte) 0, (byte) 128, (byte) 255));
    assertArrayEquals(new short[] {-32768, 0, 32512}, Pcm.interleaved16(sound));
  }

  @Test
  void sixteenBitWavIsReadLittleEndian() {
    PcmSound sound = WavReader.read(TestSounds.wav16(22050, 1, (short) 1, (short) -2, (short) 300));
    assertArrayEquals(new short[] {1, -2, 300}, Pcm.interleaved16(sound));
  }

  @Test
  void stereoDownmixesToMonoByAveraging() {
    PcmSound sound =
        WavReader.read(TestSounds.wav16(44100, 2, (short) 100, (short) 300, (short) -5, (short) 1));
    assertArrayEquals(new short[] {100, 300, -5, 1}, Pcm.interleaved16(sound));
    assertArrayEquals(new short[] {200, -2}, Pcm.mono16(sound));
    PcmSound eight = WavReader.read(TestSounds.wav8(8000, 2, (byte) 0, (byte) 255));
    assertArrayEquals(new short[] {(short) ((-32768 + 32512) >> 1)}, Pcm.mono16(eight));
  }

  @Test
  void rawSamplesAreSigned() {
    short[] out = new short[3];
    Pcm.raw16(new byte[] {127, -128, 0}, 3, 1, 1, out);
    assertArrayEquals(new short[] {32512, -32768, 0}, out);
    Pcm.raw16(new byte[] {(byte) 0xff, 0x7f, 0x00, (byte) 0x80}, 1, 2, 2, out);
    assertArrayEquals(new short[] {32767, -32768, 0}, out);
  }

  @Test
  void rawConversionChecksItsBuffers() {
    assertThrows(
        IllegalArgumentException.class, () -> Pcm.raw16(new byte[2], 2, 2, 1, new short[2]));
    assertThrows(
        IllegalArgumentException.class, () -> Pcm.raw16(new byte[4], 2, 2, 1, new short[1]));
    assertThrows(
        IllegalArgumentException.class, () -> Pcm.raw16(new byte[4], 1, 4, 1, new short[1]));
  }
}
