package dev.bluevista.craftq3.engine.vm;

import static dev.bluevista.craftq3.engine.vm.Opcode.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class QvmImageTest {
  private static QvmAssembler constant() {
    return new QvmAssembler().op(ENTER, 8).op(CONST, 42).op(LEAVE, 8);
  }

  @Test
  void dispatchConstantsMatchTheFileOpcodeNumbers() throws Exception {
    int checked = 0;
    for (Field field : Op.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || field.getName().equals("END")) continue;
      assertEquals(Opcode.valueOf(field.getName()).ordinal(), field.getInt(null), field.getName());
      checked++;
    }
    assertEquals(Opcode.count(), checked);
    assertEquals(Opcode.count(), Op.END);
  }

  @Test
  void readsHeaderDataLiteralAndZeroedBssWithDefensiveSnapshots() {
    byte[] file = constant().data(0x12345678, -1).literal((byte) 'x', (byte) 0).bss(1024).bytes();
    var image = QvmImage.parse("fixture", file, 512);
    assertEquals(1, image.version());
    assertEquals(8, image.dataLength());
    assertEquals(2, image.literalLength());
    assertEquals(1024, image.bssLength());
    assertEquals(2048, image.memorySize());
    assertEquals(2048 - 512, image.stackBottom());
    assertEquals(3, image.instructionCount());
    assertEquals(CONST, image.opcode(1));
    assertEquals(42, image.operand(1));
    assertEquals(0, image.procedureAt(2));
    assertThrows(IndexOutOfBoundsException.class, () -> image.opcode(3));
    Arrays.fill(file, (byte) 0);
    byte[] snapshot = image.initialData();
    assertEquals(0x78, snapshot[0]);
    snapshot[0] = 0;
    assertEquals(0x78, image.initialData()[0]);
    var vm = new QvmInstance(image, (v, number, args) -> 0);
    assertEquals(0x12345678, vm.memory().readInt(0));
    assertEquals("x", vm.memory().readString(8, 2));
    assertEquals(0, vm.memory().readInt(12));
    assertEquals(42, vm.vmMain(0));
  }

  @Test
  void readsV2ByteLengthJumpTableAfterLiteralSection() {
    var image = constant().literal((byte) 'a').v2(1, 2, 2).image();
    assertEquals(2, image.version());
    assertEquals(2, image.jumpTableTargets());
    assertTrue(image.jumpTableTarget(1));
    assertFalse(image.jumpTableTarget(0));
  }

  @Test
  void programStackSitsAtTheTopOfThePowerOfTwoSegment() {
    // Like q3asm output: the 64 KiB stack reservation is the tail of BSS, so the image is
    // accepted even though rounding up to a power of two leaves less than 64 KiB of padding.
    var tight = constant().bss(QvmImage.PROGRAM_STACK_SIZE * 2 - 64 - 4).image();
    assertEquals(QvmImage.PROGRAM_STACK_SIZE * 2, tight.memorySize());
    assertEquals(QvmImage.PROGRAM_STACK_SIZE, tight.stackBottom());
    var vm = new QvmInstance(tight, QvmAssembler.NO_SYSCALLS);
    assertEquals(tight.memorySize(), vm.programStack());
    assertEquals(42, vm.vmMain(0));

    // BSS smaller than the stack cannot contain the reservation: all of it must sit below.
    var below = new QvmAssembler().data(new int[64]).bss(4).stack(128);
    below.op(ENTER, 8).op(CONST, 1).op(LEAVE, 8);
    assertEquals(512 - 128, below.image().stackBottom());
    var overlapping = new QvmAssembler().data(new int[48]).bss(0).stack(128);
    overlapping.op(ENTER, 8).op(CONST, 1).op(LEAVE, 8);
    var failure = assertThrows(QvmFormatException.class, overlapping::image);
    assertTrue(
        failure.getMessage().contains("overlap the 128-byte program stack"), failure::getMessage);
    assertEquals("synthetic", failure.module());

    // The default 64 KiB stack never fits a tiny segment.
    assertThrows(
        QvmFormatException.class, () -> QvmImage.parse("tiny", constant().bss(64).bytes()));
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("odd", constant().bytes(), 130));
  }

  @Test
  void oneImageBacksIndependentInstances() {
    var code =
        new QvmAssembler()
            .data(20)
            .op(ENTER, 8)
            .op(CONST, 0)
            .op(CONST, 0)
            .op(LOAD4)
            .op(CONST, 1)
            .op(ADD)
            .op(STORE4)
            .op(CONST, 0)
            .op(LOAD4)
            .op(LEAVE, 8);
    var image = code.image();
    var first = new QvmInstance(image, QvmAssembler.NO_SYSCALLS);
    var second = new QvmInstance(image, QvmAssembler.NO_SYSCALLS);
    assertEquals(21, first.vmMain(0));
    assertEquals(22, first.vmMain(0));
    assertEquals(21, second.vmMain(0));
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
          () -> QvmImage.parse("bad", altered),
          "header field " + field[0] + "=" + field[1]);
    }
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("bad", new byte[31]));
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("bad", null));
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("", good));
    assertThrows(
        QvmFormatException.class,
        () -> QvmImage.parse("bad", Arrays.copyOf(good, good.length - 1)));
  }

  @Test
  void rejectsInstructionAndProcedureBoundaryCorruption() {
    byte[] unknown = constant().bytes();
    unknown[32] = (byte) 255;
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("bad", unknown));
    unknown[32] = (byte) Opcode.count();
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("bad", unknown));
    assertThrows(QvmFormatException.class, () -> new QvmAssembler().op(CONST, 1).image());
    assertThrows(QvmFormatException.class, () -> new QvmAssembler().op(ENTER, 6).image());
    assertThrows(
        QvmFormatException.class, () -> new QvmAssembler().op(ENTER, 8).op(LEAVE, 1 << 21).image());
    assertThrows(
        QvmFormatException.class, () -> new QvmAssembler().op(ENTER, 8).op(ARG, 9).image());
    assertThrows(
        QvmFormatException.class,
        () -> new QvmAssembler().op(ENTER, 8).op(LOCAL, -(1 << 27)).image());
    assertThrows(
        QvmFormatException.class, () -> new QvmAssembler().op(ENTER, 8).op(BLOCK_COPY, -4).image());
    assertThrows(
        QvmFormatException.class, () -> new QvmAssembler().op(ENTER, 8).op(EQ, 99).image());
    assertThrows(
        QvmFormatException.class,
        () -> new QvmAssembler().op(ENTER, 8).op(EQ, 2).op(ENTER, 8).image());
    assertThrows(QvmFormatException.class, () -> new QvmAssembler().op(ENTER, 8).op(EQ, 0).image());
    assertThrows(QvmFormatException.class, () -> constant().v2(99).image());
    byte[] truncatedImmediate = constant().bytes();
    ByteBuffer.wrap(truncatedImmediate).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 13);
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("bad", truncatedImmediate));
  }

  @Test
  void permitsOnlyUpToThreeZeroCodeAlignmentBytes() {
    byte[] good = constant().bytes();
    assertEquals(42, constant().vm().vmMain(0));
    good[47] = 1; // Three five-byte instructions occupy offsets 32..46, then one alignment byte.
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("bad", good));
    byte[] excessCount = constant().bytes();
    ByteBuffer.wrap(excessCount).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2);
    assertThrows(QvmFormatException.class, () -> QvmImage.parse("bad", excessCount));
  }
}
