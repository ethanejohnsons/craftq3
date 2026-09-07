package dev.bluevista.craftq3.assets.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class WavReaderTest {
  @Test
  void acceptsOnlyTheMissingFinalPadFoundInOriginalEightBitSounds() throws Exception {
    assertEquals(3, WavReader.read(wave(1, 8, new byte[] {1, 2, 3})).frames());
    byte[] truncated = wave(1, 8, new byte[] {1, 2, 3});
    ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 4);
    assertThrows(WavFormatException.class, () -> WavReader.read(truncated));
  }

  @Test
  void unsignedEightBitAndStereoSignedSamplesDecodeExactly() throws Exception {
    var mono = WavReader.read(wave(1, 8, new byte[] {0, (byte) 128, (byte) 255, 64}));
    assertEquals(-32768, mono.sample16(0, 0));
    assertEquals(0, mono.sample16(1, 0));
    assertEquals(32512, mono.sample16(2, 0));
    assertEquals(4, mono.frames());
    assertEquals(4.0 / 22050, mono.durationSeconds());
    var stereo = WavReader.read(wave(2, 16, new byte[] {0, (byte) 128, (byte) 255, 127}));
    assertEquals(-32768, stereo.sample16(0, 0));
    assertEquals(32767, stereo.sample16(0, 1));
    assertArrayEquals(new byte[2], stereo.mono16le());
  }

  @Test
  void retainsOwnedBytesAndSkipsWordPaddedMetadata() throws Exception {
    byte[] file = wave(1, 8, new byte[] {1, 2});
    byte[] extended = new byte[file.length + 10];
    System.arraycopy(file, 0, extended, 0, 12);
    ByteBuffer b = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(4, extended.length - 8);
    b.putInt(12, 0x4b4e554a);
    b.putInt(16, 1);
    b.put(20, (byte) 42);
    System.arraycopy(file, 12, extended, 22, file.length - 12);
    var sound = WavReader.read(extended);
    byte[] pcm = sound.pcm();
    pcm[0] = 0;
    extended[extended.length - 2] = 0;
    assertEquals((1 - 128) * 256, sound.sample16(0, 0));
  }

  @Test
  void rejectsCompressedFormatsTruncationAndUnsignedLengthOverflow() {
    byte[] invalid = wave(1, 16, new byte[] {0, 0});
    ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short) 2);
    assertThrows(WavFormatException.class, () -> WavReader.read(invalid));
    byte[] overflow = wave(1, 8, new byte[] {0, 0});
    ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1);
    assertThrows(WavFormatException.class, () -> WavReader.read(overflow));
    byte[] partial = wave(2, 16, new byte[] {0, 0});
    assertThrows(WavFormatException.class, () -> WavReader.read(partial));
  }

  private static byte[] wave(int channels, int bits, byte[] data) {
    ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x46464952).putInt(b.capacity() - 8).putInt(0x45564157).putInt(0x20746d66).putInt(16);
    b.putShort((short) 1)
        .putShort((short) channels)
        .putInt(22050)
        .putInt(22050 * channels * bits / 8);
    b.putShort((short) (channels * bits / 8))
        .putShort((short) bits)
        .putInt(0x61746164)
        .putInt(data.length)
        .put(data);
    return b.array();
  }
}
