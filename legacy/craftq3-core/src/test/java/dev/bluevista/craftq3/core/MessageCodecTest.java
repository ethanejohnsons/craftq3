package dev.bluevista.craftq3.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MessageCodecTest {
  @Test
  void independentNativeWireExamples() {
    var writer = new MessageWriter();
    for (int value : new int[] {0, 1, 2, 255}) writer.byteValue(value);
    assertEquals(20, writer.bitPosition());
    assertEquals("6e2409", HexFormat.of().formatHex(writer.bytes()));
    writer = new MessageWriter();
    writer.intValue(0x12345678);
    assertEquals(35, writer.bitPosition());
    assertEquals("4be34dc107", HexFormat.of().formatHex(writer.bytes()));
    assertEquals(0x12345678, new MessageReader(writer.bytes()).intValue());
  }

  @Test
  void everyByteAtEveryUnalignedBitOffset() {
    for (int offset = 0; offset < 8; offset++) {
      var writer = new MessageWriter();
      if (offset > 0) writer.bits(1, offset);
      for (int value = 0; value < 256; value++) writer.byteValue(value);
      var reader = new MessageReader(writer.bytes(), writer.bitPosition());
      if (offset > 0) assertEquals(1, reader.bits(offset));
      for (int value = 0; value < 256; value++) assertEquals(value, reader.byteValue());
      assertEquals(0, reader.remainingBits());
    }
  }

  @Test
  void allWidthsAndSignedFields() {
    var random = new Random(0x68);
    for (int width = 1; width <= 32; width++) {
      var writer = new MessageWriter();
      int[] values = random.ints(256).toArray();
      for (int value : values) writer.bits(value, width);
      var reader = new MessageReader(writer.bytes(), writer.bitPosition());
      for (int value : values)
        assertEquals(value << (32 - width) >> (32 - width), reader.signedBits(width));
      assertEquals(writer.bitPosition(), reader.bitPosition());
    }
  }

  @Test
  void nativeSignedFieldConventionUsesWholeBytePortion() {
    var writer = new MessageWriter();
    writer.field(64, -7);
    writer.field(128, -9);
    writer.field(256, -9);
    writer.field(32768, -17);
    var reader = new MessageReader(writer.bytes());
    assertEquals(64, reader.field(-7));
    assertEquals(-128, reader.field(-9));
    assertEquals(256, reader.field(-9));
    assertEquals(-32768, reader.field(-17));
    assertThrows(IllegalArgumentException.class, () -> reader.field(-32));
    assertThrows(IllegalArgumentException.class, () -> writer.field(0, -32));
  }

  @Test
  void floatPayloadBitsRemainUnchanged() {
    var writer = new MessageWriter();
    int[] bits = {0, 0x80000000, 0x7f800000, 0xff800000, 0x7fa12345, 0x3f800001};
    for (int value : bits) writer.floatValue(Float.intBitsToFloat(value));
    var reader = new MessageReader(writer.bytes());
    for (int value : bits) assertEquals(value, Float.floatToRawIntBits(reader.floatValue()));
  }

  @Test
  void alignedFieldsRetainNativeTrailingByteAndEmptyMessageStaysEmpty() {
    var writer = new MessageWriter();
    assertEquals(0, writer.bytes().length);
    writer.bits(1, 7);
    writer.bits(1, 1);
    assertEquals(8, writer.bitPosition());
    assertArrayEquals(new byte[] {(byte) 0x81, 0}, writer.bytes());
  }

  @Test
  void truncationAndCapacityFailureDoNotAdvanceState() {
    var writer = new MessageWriter(2);
    writer.bits(7, 7);
    byte[] before = writer.bytes();
    assertThrows(IllegalArgumentException.class, () -> writer.intValue(-1));
    assertArrayEquals(before, writer.bytes());
    assertEquals(7, writer.bitPosition());
    var field = new MessageWriter();
    field.intValue(0x12345678);
    for (int limit = 0; limit < field.bitPosition(); limit++) {
      var reader = new MessageReader(field.bytes(), limit);
      assertThrows(IllegalArgumentException.class, reader::intValue);
      assertEquals(0, reader.bitPosition());
    }
    assertThrows(IllegalArgumentException.class, () -> new MessageWriter(0));
    assertThrows(IllegalArgumentException.class, () -> new MessageWriter(16385));
    assertThrows(IllegalArgumentException.class, () -> new MessageReader(new byte[16385]));
    assertThrows(IllegalArgumentException.class, () -> new MessageReader(new byte[1], 9));
    assertThrows(IllegalArgumentException.class, () -> writer.bits(1, 0));
    assertThrows(IllegalArgumentException.class, () -> writer.bits(1, 33));
  }

  @Test
  void callerBuffersCannotChangeReaderOrWriterState() {
    var writer = new MessageWriter();
    writer.byteValue(1);
    byte[] bytes = writer.bytes();
    var reader = new MessageReader(bytes, writer.bitPosition());
    bytes[0] = 0;
    assertEquals(1, reader.byteValue());
    assertEquals(1, new MessageReader(writer.bytes()).byteValue());
  }
}
