package dev.bluevista.craftq3.engine.formats.wav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WavReaderTest {
  @Test
  void acceptsAMissingFinalPadAndTruncatesAnOverlongDataChunk() {
    assertEquals(3, WavReader.read(wave(1, 8, 22050, new byte[] {1, 2, 3})).frames());
    byte[] overlong = wave(1, 8, 22050, new byte[] {1, 2, 3});
    view(overlong).putInt(40, 4);
    assertEquals(3, WavReader.read(overlong).frames());
    byte[] partial = wave(2, 16, 22050, new byte[] {0, 0, 1});
    assertEquals(0, WavReader.read(partial).frames());
  }

  @Test
  void unsignedEightBitAndStereoSignedSamplesDecodeExactly() {
    var mono = WavReader.read(wave(1, 8, 22050, new byte[] {0, (byte) 128, (byte) 255, 64}));
    assertEquals(-32768, mono.sample16(0, 0));
    assertEquals(0, mono.sample16(1, 0));
    assertEquals(32512, mono.sample16(2, 0));
    assertEquals(4, mono.frames());
    assertEquals(4.0 / 22050, mono.durationSeconds());
    var stereo = WavReader.read(wave(2, 16, 44100, new byte[] {0, (byte) 128, (byte) 255, 127}));
    assertEquals(44100, stereo.sampleRate());
    assertEquals(-32768, stereo.sample16(0, 0));
    assertEquals(32767, stereo.sample16(0, 1));
    assertArrayEquals(new byte[2], stereo.mono16le());
  }

  @Test
  void acceptsAnyRateIgnoresByteRateAndAlignAndTheRiffLength() {
    byte[] odd = wave(1, 16, 11025, new byte[] {1, 0});
    view(odd).putInt(28, 7).putShort(32, (short) 9).putInt(4, 999_999);
    var sound = WavReader.read(odd);
    assertEquals(11025, sound.sampleRate());
    assertEquals(1, sound.sample16(0, 0));
    assertEquals(0, WavReader.read(wave(1, 16, 8000, new byte[0])).frames());
  }

  @Test
  void skipsWordPaddedMetadataChunksAndOwnsItsBytes() {
    byte[] file = wave(1, 8, 22050, new byte[] {1, 2});
    byte[] extended = new byte[file.length + 10];
    System.arraycopy(file, 0, extended, 0, 12);
    ByteBuffer b = view(extended);
    b.putInt(12, 0x4b4e554a); // "JUNK", one byte plus its pad
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
  void findsTheDataChunkBeforeTheFormatChunk() {
    byte[] file = wave(1, 8, 22050, new byte[] {7, 8});
    byte[] reordered = Arrays.copyOf(file, file.length);
    // Move "data" (10 bytes) in front of "fmt " (24 bytes).
    System.arraycopy(file, 36, reordered, 12, 10);
    System.arraycopy(file, 12, reordered, 22, 24);
    assertEquals((7 - 128) * 256, WavReader.read(reordered).sample16(0, 0));
  }

  @Test
  void rejectsCompressedFormatsWithAClearMessageAndMissingChunks() {
    byte[] adpcm = wave(1, 16, 22050, new byte[] {0, 0});
    view(adpcm).putShort(20, (short) 2);
    var failure = assertThrows(WavFormatException.class, () -> WavReader.read(adpcm));
    assertTrue(failure.getMessage().contains("uncompressed PCM"), failure.getMessage());
    byte[] negative = wave(1, 8, 22050, new byte[] {0, 0});
    view(negative).putInt(40, -1);
    assertThrows(WavFormatException.class, () -> WavReader.read(negative));
    byte[] notRiff = wave(1, 8, 22050, new byte[] {0});
    notRiff[0] = 'X';
    assertThrows(WavFormatException.class, () -> WavReader.read(notRiff));
    byte[] depth = wave(1, 24, 22050, new byte[] {0, 0, 0});
    assertThrows(WavFormatException.class, () -> WavReader.read(depth));
    assertThrows(
        WavFormatException.class,
        () -> WavReader.read(Arrays.copyOf(wave(1, 8, 22050, new byte[0]), 30)));
  }

  private static ByteBuffer view(byte[] bytes) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static byte[] wave(int channels, int bits, int rate, byte[] data) {
    ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x46464952).putInt(b.capacity() - 8).putInt(0x45564157).putInt(0x20746d66).putInt(16);
    b.putShort((short) 1)
        .putShort((short) channels)
        .putInt(rate)
        .putInt(rate * channels * bits / 8);
    b.putShort((short) (channels * bits / 8)).putShort((short) bits);
    b.putInt(0x61746164).putInt(data.length).put(data);
    return b.array();
  }
}
