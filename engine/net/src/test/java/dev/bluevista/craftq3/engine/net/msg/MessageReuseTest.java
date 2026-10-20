package dev.bluevista.craftq3.engine.net.msg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The reuse, checkpoint and raw-coding additions of the rewrite's reader and writer. */
final class MessageReuseTest {
  @Test
  void resetWriterProducesTheSameNativeBytesAsAFreshOne() {
    var writer = new MessageWriter();
    for (int round = 0; round < 3; round++) {
      writer.reset();
      for (int value : new int[] {0, 1, 2, 255}) writer.byteValue(value);
      assertEquals("6e2409", HexFormat.of().formatHex(writer.bytes()));
      writer.reset();
      writer.intValue(0x12345678);
      assertEquals("4be34dc107", HexFormat.of().formatHex(writer.bytes()));
      byte[] target = new byte[8];
      assertEquals(5, writer.copyTo(target, 2));
      assertEquals("00004be34dc10700", HexFormat.of().formatHex(target));
    }
  }

  @Test
  void rollbackClearsOnlyBitsAfterTheCheckpoint() {
    var writer = new MessageWriter();
    writer.bits(5, 3);
    int mark = writer.mark();
    writer.intValue(-1);
    writer.rollback(mark);
    assertEquals(3, writer.bitPosition());
    writer.byteValue('a');
    var expected = new MessageWriter();
    expected.bits(5, 3);
    expected.byteValue('a');
    assertArrayEquals(expected.bytes(), writer.bytes());
    assertThrows(IllegalArgumentException.class, () -> writer.rollback(writer.bitPosition() + 1));
  }

  @Test
  void resetReaderCopiesAndIgnoresStaleTrailingBytes() {
    var random = new Random(68);
    var reader = new MessageReader();
    var big = new MessageWriter();
    for (int i = 0; i < 1000; i++) big.bits(random.nextInt(), 32);
    byte[] large = big.bytes();
    reader.reset(large, 0, large.length);
    assertEquals(new MessageReader(large).intValue(), reader.intValue());
    var small = new MessageWriter();
    small.bits(3, 2);
    small.byteValue(200);
    byte[] bytes = small.bytes();
    byte[] framed = new byte[bytes.length + 4];
    System.arraycopy(bytes, 0, framed, 2, bytes.length);
    reader.reset(framed, 2, bytes.length);
    framed[2] = 0;
    assertEquals(3, reader.bits(2));
    assertEquals(200, reader.byteValue());
    assertTrue(reader.remainingBits() < 8);
    assertThrows(IllegalArgumentException.class, reader::byteValue);
    assertThrows(IllegalArgumentException.class, () -> reader.reset(framed, 3, framed.length));
  }

  @Test
  void rawCodingReadsEveryBitLeastSignificantFirst() {
    byte[] data = HexFormat.of().parseHex("0e00000002750000004d");
    var reader = new MessageReader(data).coding(MessageReader.Coding.RAW);
    assertEquals(14, reader.intValue());
    assertEquals(2, reader.byteValue());
    assertEquals(117, reader.intValue());
    assertEquals(1, reader.bits(1));
    assertEquals(0b0110, reader.bits(4));
    assertEquals(0b010, reader.bits(3));
    assertEquals(0, reader.remainingBits());
    assertThrows(IllegalArgumentException.class, () -> reader.bits(1));
    var signed =
        new MessageReader(HexFormat.of().parseHex("ff7f")).coding(MessageReader.Coding.RAW);
    assertEquals(0x7fff, signed.shortValue());
  }
}
