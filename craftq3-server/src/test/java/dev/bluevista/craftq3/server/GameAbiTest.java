package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import dev.bluevista.craftq3.vm.QvmReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class GameAbiTest {
  @Test
  void retailRecognitionRequiresExactKnownModuleHash() {
    assertEquals(GameAbi.RETAIL_1999, GameAbi.fromHash(GameAbi.RETAIL_QAGAME_SHA256));
    assertEquals(GameAbi.Q3_132, GameAbi.fromHash("0".repeat(64)));
    assertEquals(
        GameAbi.Q3_132,
        GameAbi.detect("Nov 21 1999".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  }

  @Test
  void bothSharedLayoutsHaveExplicitOffsetsAndRetailHasNoSingleClient() {
    var retail = GameAbi.RETAIL_1999;
    assertEquals(204, retail.entityStateBytes());
    assertEquals(444, retail.playerStateBytes());
    assertEquals(408, retail.linked());
    assertEquals(416, retail.svFlags());
    assertEquals(-1, retail.singleClient());
    assertEquals(420, retail.bmodel());
    assertEquals(424, retail.mins());
    assertEquals(436, retail.maxs());
    assertEquals(448, retail.contents());
    assertEquals(452, retail.absmin());
    assertEquals(464, retail.absmax());
    assertEquals(476, retail.origin());
    assertEquals(488, retail.angles());
    assertEquals(500, retail.owner());
    assertEquals(504, retail.sharedEntityBytes());
    var modern = GameAbi.Q3_132;
    assertEquals(208, modern.entityStateBytes());
    assertEquals(468, modern.playerStateBytes());
    assertEquals(428, modern.singleClient());
    assertEquals(436, modern.mins());
    assertEquals(460, modern.contents());
    assertEquals(488, modern.origin());
    assertEquals(516, modern.sharedEntityBytes());
  }

  @Test
  void retailCommandsPlaceButtonsBeforeAlignedAnglesAndPreserveSignedMotion() throws Exception {
    var memory = memory();
    memory.fill(100, 24, 255);
    new UserCommand(1000, 0x1234, 0xabcd, 0x5678, 17, 2, 127, -127, -1)
        .write(memory, 100, GameAbi.RETAIL_1999);
    assertEquals(1000, memory.readInt(100));
    assertEquals(17, memory.readUnsignedByte(104));
    assertEquals(2, memory.readUnsignedByte(105));
    assertEquals(0, memory.readUnsignedShort(106));
    assertEquals(0x1234, memory.readInt(108));
    assertEquals(0xabcd, memory.readInt(112));
    assertEquals(0x5678, memory.readInt(116));
    assertEquals(127, memory.readUnsignedByte(120));
    assertEquals(129, memory.readUnsignedByte(121));
    assertEquals(255, memory.readUnsignedByte(122));
    assertEquals(0, memory.readUnsignedByte(123));
  }

  @Test
  void userCommandMotionAcceptsSignedByteMinimumAndRejectsValuesOutsideItsStorage() {
    assertDoesNotThrow(() -> new UserCommand(0, 0, 0, 0, 0, 255, -128, -128, -128));
    for (int invalid : new int[] {-129, 128}) {
      assertThrows(
          IllegalArgumentException.class, () -> new UserCommand(0, 0, 0, 0, 0, 0, invalid, 0, 0));
      assertThrows(
          IllegalArgumentException.class, () -> new UserCommand(0, 0, 0, 0, 0, 0, 0, invalid, 0));
      assertThrows(
          IllegalArgumentException.class, () -> new UserCommand(0, 0, 0, 0, 0, 0, 0, 0, invalid));
    }
  }

  @Test
  void canonicalSnapshotsInsertOnlyAbsentFieldsWithoutLeakingGamePrivateBytes() throws Exception {
    var memory = memory();
    for (int offset = 0; offset < 512; offset += 4) memory.writeInt(offset, offset + 1000);
    var state =
        ByteBuffer.wrap(GameAbi.RETAIL_1999.entityState(memory, 0)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(208, state.capacity());
    assertEquals(1200, state.getInt(200));
    assertEquals(0, state.getInt(204));
    var player =
        ByteBuffer.wrap(GameAbi.RETAIL_1999.playerState(memory, 0)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(468, player.capacity());
    assertEquals(1184, player.getInt(184));
    assertEquals(1436, player.getInt(436));
    assertEquals(1440, player.getInt(452));
    for (int offset : new int[] {440, 444, 448, 456, 460, 464})
      assertEquals(0, player.getInt(offset));
    assertEquals(
        1464,
        ByteBuffer.wrap(GameAbi.Q3_132.playerState(memory, 0))
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt(464));
    state.putInt(0, -1);
    assertEquals(1000, memory.readInt(0));
  }

  static QvmMemory memory() throws Exception {
    var file = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
    file.putInt(QvmReader.MAGIC)
        .putInt(3)
        .putInt(32)
        .putInt(15)
        .putInt(48)
        .putInt(0)
        .putInt(0)
        .putInt(65536);
    file.put((byte) Opcode.ENTER.ordinal())
        .putInt(8)
        .put((byte) Opcode.CONST.ordinal())
        .putInt(0)
        .put((byte) Opcode.LEAVE.ordinal())
        .putInt(8);
    return new QvmInterpreter(QvmReader.read("ABI fixture", file.array()), (m, s, a) -> 0).memory();
  }
}
