package dev.bluevista.craftq3.engine.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class QvmMemoryTest {
  @Test
  void masksTheStartingAddressButNeverWrapsARange() {
    var memory = new QvmMemory(128);
    assertEquals(127, memory.resolve(-1, 1));
    assertEquals(0, memory.resolve(128, 4));
    assertEquals(0, memory.resolve(128, 128));
    for (int[] range :
        new int[][] {
          {-1, 2}, {127, 2}, {0, -1}, {0, 129}, {Integer.MAX_VALUE, Integer.MAX_VALUE}
        }) {
      assertThrows(QvmException.class, () -> memory.resolve(range[0], range[1]));
    }
    assertThrows(QvmException.class, () -> memory.writeInt(126, 123));
    assertEquals(0, memory.readUnsignedByte(126));
    assertThrows(QvmException.class, () -> memory.copy(0, 127, 2));
    assertThrows(QvmException.class, () -> memory.readInt(-1));
    assertThrows(IllegalArgumentException.class, () -> new QvmMemory(96));
  }

  @Test
  void byteShortFloatAndSnapshotsPreserveLittleEndianBitsWithoutAliases() {
    var memory = new QvmMemory(128);
    memory.writeByte(0, 511);
    memory.writeShort(1, 0x8765);
    memory.writeFloat(3, -1.5f);
    assertEquals(255, memory.readUnsignedByte(0));
    assertEquals(0x8765, memory.readUnsignedShort(1));
    assertEquals(-1.5f, memory.readFloat(3));
    assertArrayEquals(
        new byte[] {(byte) 255, 0x65, (byte) 0x87, 0, 0, (byte) 0xc0, (byte) 0xbf},
        memory.readBytes(0, 7));
    byte[] bytes = {1, 2, 3};
    memory.writeBytes(8, bytes);
    bytes[0] = 99;
    byte[] copied = memory.readBytes(8, 3);
    copied[0] = 99;
    assertEquals(1, memory.readUnsignedByte(8));
    assertEquals(1, memory.readUnsignedByte(128 + 8));
  }

  @Test
  void slicesWriteThroughAndCopiesHaveMemmoveSemantics() {
    var memory = new QvmMemory(64);
    var slice = memory.slice(16, 8);
    assertEquals(ByteOrder.LITTLE_ENDIAN, slice.order());
    assertEquals(8, slice.capacity());
    slice.putInt(4, 0x01020304);
    assertEquals(0x01020304, memory.readInt(20));
    assertThrows(QvmException.class, () -> memory.slice(60, 8));
    memory.writeBytes(0, new byte[] {1, 2, 3, 4, 5});
    memory.copy(1, 0, 4);
    assertArrayEquals(new byte[] {1, 1, 2, 3, 4}, memory.readBytes(0, 5));
    memory.fill(0, 64, 7);
    assertEquals(0x07070707, memory.readInt(60));
    assertThrows(QvmException.class, () -> memory.fill(1, 64, 0));
  }

  @Test
  void stringsRequireABoundedTerminatorAndWritesTruncateWithNul() {
    var memory = new QvmMemory(16);
    assertEquals(3, memory.writeString(0, "abcdef", 4));
    assertEquals("abc", memory.readString(0, 4));
    assertThrows(QvmException.class, () -> memory.readString(0, 3));
    memory.fill(0, 16, 'x');
    assertThrows(QvmException.class, () -> memory.readString(0, Integer.MAX_VALUE));
    assertThrows(QvmException.class, () -> memory.readString(0, 0));
    memory.writeString(14, "Q", 2);
    assertEquals("Q", memory.readString(14, 10));
    assertThrows(QvmException.class, () -> memory.writeString(14, "Q", 3));
    assertEquals(0, memory.writeString(16, "", 0));
    assertEquals(1, memory.writeString(0, "é", 4));
    assertEquals(0xe9, memory.readUnsignedByte(0));
    assertEquals("é", memory.readString(0, 4));
  }
}
