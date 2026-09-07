package dev.bluevista.craftq3.core.net.delta;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class UserCommandDeltaCodecTest {
  @Test
  void capturedNativeWireIncludesTimeKeyAndFieldOrder() {
    byte[] from = state(1000), to = from.clone();
    var writer = new MessageWriter();
    UserCommandDeltaCodec.write(writer, 0, from, to);
    assertEquals("05", HexFormat.of().formatHex(writer.bytes()));
    assertEquals(4, writer.bitPosition());
    word(to, 4, 1);
    writer = new MessageWriter();
    UserCommandDeltaCodec.write(writer, 0, from, to);
    assertEquals("3d463600", HexFormat.of().formatHex(writer.bytes()));
    assertEquals(29, writer.bitPosition());
    assertArrayEquals(to, UserCommandDeltaCodec.read(new MessageReader(writer.bytes()), 0, from));
  }

  @Test
  void shortTimesIncludeTheObservedBackwardAndOverflowCases() {
    for (int delta : new int[] {0, 1, 255, 256, -1, -256}) {
      var writer = new MessageWriter();
      UserCommandDeltaCodec.write(writer, 73, state(1000), state(1000 + delta));
      var header = new MessageReader(writer.bytes());
      assertEquals(delta < 256 ? 1 : 0, header.bits(1));
      byte[] result =
          UserCommandDeltaCodec.read(new MessageReader(writer.bytes()), 73, state(1000));
      assertEquals(1000 + (delta < 256 ? delta & 255 : delta), word(result, 0));
    }
    var writer = new MessageWriter();
    UserCommandDeltaCodec.write(writer, -1, state(Integer.MAX_VALUE), state(Integer.MIN_VALUE));
    assertEquals(
        Integer.MIN_VALUE,
        word(
            UserCommandDeltaCodec.read(
                new MessageReader(writer.bytes()), -1, state(Integer.MAX_VALUE)),
            0));
  }

  @Test
  void unchangedWideFieldsRemainIntactWhileChangedFieldsUseTheirWireWidths() {
    byte[] from = state(100), to = from.clone();
    word(from, 4, -1);
    word(to, 4, -1);
    word(to, 8, -2);
    word(to, 16, 0x12345678);
    to[20] = (byte) 255;
    var writer = new MessageWriter();
    UserCommandDeltaCodec.write(writer, 0x81234567, from, to);
    byte[] result = UserCommandDeltaCodec.read(new MessageReader(writer.bytes()), 0x81234567, from);
    assertEquals(-1, word(result, 4));
    assertEquals(65534, word(result, 8));
    assertEquals(0x5678, word(result, 16));
    assertEquals(255, Byte.toUnsignedInt(result[20]));
  }

  @Test
  void movementMinus128ClampsOnlyWhenTheFieldGroupIsPresent() {
    byte[] from = state(100), to = from.clone();
    for (int i = 21; i < 24; i++) from[i] = to[i] = Byte.MIN_VALUE;
    var writer = new MessageWriter();
    UserCommandDeltaCodec.write(writer, 0, from, to);
    assertArrayEquals(from, UserCommandDeltaCodec.read(new MessageReader(writer.bytes()), 0, from));
    word(to, 16, 1);
    writer = new MessageWriter();
    UserCommandDeltaCodec.write(writer, 0, from, to);
    byte[] result = UserCommandDeltaCodec.read(new MessageReader(writer.bytes()), 0, from);
    for (int i = 21; i < 24; i++) assertEquals(-127, result[i]);
    for (int i = 21; i < 24; i++) assertEquals(Byte.MIN_VALUE, from[i]);
  }

  @Test
  void boundedFailureRollsBackWithoutMutatingTheBaseline() {
    byte[] to = state(1000);
    word(to, 4, 72);
    var shortWriter = new MessageWriter(2);
    shortWriter.bits(5, 3);
    byte[] before = shortWriter.bytes();
    assertThrows(
        IllegalArgumentException.class,
        () -> UserCommandDeltaCodec.write(shortWriter, 9, null, to));
    assertArrayEquals(before, shortWriter.bytes());
    assertEquals(3, shortWriter.bitPosition());
    assertThrows(
        IllegalArgumentException.class,
        () -> UserCommandDeltaCodec.write(shortWriter, 9, new byte[23], to));

    var writer = new MessageWriter();
    writer.bits(5, 3);
    UserCommandDeltaCodec.write(writer, 9, null, to);
    var reader = new MessageReader(writer.bytes(), writer.bitPosition() - 1);
    reader.bits(3);
    assertThrows(IllegalArgumentException.class, () -> UserCommandDeltaCodec.read(reader, 9, null));
    assertEquals(3, reader.bitPosition());
  }

  private static byte[] state(int time) {
    byte[] bytes = new byte[UserCommandDeltaCodec.STATE_BYTES];
    word(bytes, 0, time);
    return bytes;
  }

  private static int word(byte[] bytes, int offset) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
  }

  private static void word(byte[] bytes, int offset, int value) {
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
  }
}
