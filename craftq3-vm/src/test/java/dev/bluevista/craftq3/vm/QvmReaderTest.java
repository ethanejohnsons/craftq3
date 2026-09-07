package dev.bluevista.craftq3.vm;

import static dev.bluevista.craftq3.vm.Opcode.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class QvmReaderTest {
  private static QvmTestModule constant() {
    return new QvmTestModule().op(ENTER, 8).op(CONST, 42).op(LEAVE, 8);
  }

  @Test
  void readsHeaderDataLiteralAndZeroedBssWithDefensiveSnapshots() throws Exception {
    byte[] file = constant().data(0x12345678, -1).literal((byte) 'x', (byte) 0).bss(1024).bytes();
    var module = QvmReader.read("fixture", file);
    assertEquals(1, module.header().version());
    assertEquals(32, module.header().codeOffset());
    assertEquals(8, module.header().dataLength());
    assertEquals(2, module.header().literalLength());
    assertEquals(2048, module.memorySize());
    assertEquals(3, module.instructions().size());
    assertEquals(CONST, module.instructions().get(1).opcode());
    Arrays.fill(file, (byte) 0);
    byte[] snapshot = module.initialData();
    assertEquals(0x78, snapshot[0]);
    snapshot[0] = 0;
    assertEquals(0x78, module.initialData()[0]);
    assertThrows(UnsupportedOperationException.class, () -> module.instructions().clear());
    var vm = new QvmInterpreter(module, (memory, syscall, args) -> 0);
    assertEquals(0x12345678, vm.memory().readInt(0));
    assertEquals("x", vm.memory().readCString(8, 2));
    assertEquals(0, vm.memory().readInt(12));
    assertEquals(42, vm.invoke(0));
  }

  @Test
  void readsV2ByteLengthJumpTableAfterLiteralSection() throws Exception {
    var module = constant().literal((byte) 'a').v2(1, 2).module();
    assertEquals(2, module.header().version());
    assertEquals(8, module.header().jumpTargetBytes());
    assertEquals(2, module.jumpTargetCount());
    assertTrue(module.jumpTarget(1));
    assertFalse(module.jumpTarget(0));
  }

  @Test
  void rejectsUntrustedHeaderRangesOverflowAlignmentAndOverlap() {
    byte[] good = constant().bytes();
    for (int[] field :
        new int[][] {
          {0, 0},
          {4, -1},
          {4, Integer.MAX_VALUE},
          {8, 0},
          {8, 33},
          {12, Integer.MAX_VALUE},
          {16, 32},
          {16, Integer.MAX_VALUE},
          {20, -1},
          {20, 3},
          {24, Integer.MAX_VALUE},
          {28, Integer.MAX_VALUE},
          {28, -1}
        }) {
      byte[] altered = good.clone();
      ByteBuffer.wrap(altered).order(ByteOrder.LITTLE_ENDIAN).putInt(field[0], field[1]);
      assertThrows(
          QvmFormatException.class,
          () -> QvmReader.read("bad", altered),
          "header field " + field[0] + "=" + field[1]);
    }
    assertThrows(QvmFormatException.class, () -> QvmReader.read("bad", new byte[31]));
    assertThrows(QvmFormatException.class, () -> QvmReader.read("", good));
    assertThrows(
        QvmFormatException.class,
        () -> QvmReader.read("bad", Arrays.copyOf(good, good.length - 1)));
  }

  @Test
  void rejectsInstructionAndProcedureBoundaryCorruption() {
    byte[] unknown = constant().bytes();
    unknown[32] = (byte) 255;
    assertThrows(QvmFormatException.class, () -> QvmReader.read("bad", unknown));
    assertThrows(QvmFormatException.class, () -> new QvmTestModule().op(CONST, 1).module());
    assertThrows(QvmFormatException.class, () -> new QvmTestModule().op(ENTER, 6).module());
    assertThrows(
        QvmFormatException.class, () -> new QvmTestModule().op(ENTER, 8).op(ARG, 9).module());
    assertThrows(
        QvmFormatException.class, () -> new QvmTestModule().op(ENTER, 8).op(LOCAL, -4).module());
    assertThrows(
        QvmFormatException.class, () -> new QvmTestModule().op(ENTER, 8).op(EQ, 99).module());
    assertThrows(
        QvmFormatException.class,
        () -> new QvmTestModule().op(ENTER, 8).op(EQ, 2).op(ENTER, 8).module());
    assertThrows(QvmFormatException.class, () -> constant().v2(99).module());
    byte[] truncatedImmediate = constant().bytes();
    ByteBuffer.wrap(truncatedImmediate).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 13);
    assertThrows(QvmFormatException.class, () -> QvmReader.read("bad", truncatedImmediate));
  }

  @Test
  void permitsOnlyUpToThreeZeroCodeAlignmentBytes() throws Exception {
    byte[] good = constant().bytes();
    assertEquals(42, constant().vm().invoke(0));
    good[47] = 1; // Three five-byte instructions occupy offsets 32..46, then one alignment byte.
    assertThrows(QvmFormatException.class, () -> QvmReader.read("bad", good));
    byte[] excessCount = constant().bytes();
    ByteBuffer.wrap(excessCount).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2);
    assertThrows(QvmFormatException.class, () -> QvmReader.read("bad", excessCount));
  }
}
