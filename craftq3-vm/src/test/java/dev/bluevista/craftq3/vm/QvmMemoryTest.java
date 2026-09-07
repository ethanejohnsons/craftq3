package dev.bluevista.craftq3.vm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class QvmMemoryTest {
  @Test
  void checksWholeRangesInWideArithmeticAndNeverWrapsHostAddresses() {
    var memory = new QvmMemory(128);
    for (int[] range :
        new int[][] {
          {-1, 1}, {128, 1}, {127, 2}, {0, -1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}
        }) {
      assertThrows(QvmException.class, () -> memory.checkRange(range[0], range[1]));
    }
    memory.checkRange(128, 0);
    assertThrows(QvmException.class, () -> memory.writeInt(126, 123));
    assertEquals(0, memory.readUnsignedByte(126));
    assertThrows(QvmException.class, () -> memory.copy(0, 127, 2));
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
  }

  @Test
  void stringsRequireABoundedTerminatorAndWritesTruncateWithNul() {
    var memory = new QvmMemory(16);
    assertEquals(3, memory.writeCString(0, "abcdef", 4));
    assertEquals("abc", memory.readCString(0, 4));
    assertThrows(QvmException.class, () -> memory.readCString(0, 3));
    memory.fill(0, 16, 'x');
    assertThrows(QvmException.class, () -> memory.readCString(0, Integer.MAX_VALUE));
    assertThrows(QvmException.class, () -> memory.readCString(0, 0));
    memory.writeCString(14, "Q", 2);
    assertEquals("Q", memory.readCString(14, 10));
    assertThrows(QvmException.class, () -> memory.writeCString(14, "Q", 3));
    assertEquals(0, memory.writeCString(16, "", 0));
  }
}
