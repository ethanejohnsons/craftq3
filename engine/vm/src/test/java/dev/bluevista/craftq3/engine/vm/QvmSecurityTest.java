package dev.bluevista.craftq3.engine.vm;

import static dev.bluevista.craftq3.engine.vm.Opcode.*;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.time.ManualClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class QvmSecurityTest {
  @Test
  void boundedMalformedCorpusCanOnlyDecodeRunOrProduceAContainedFault() {
    var random = new Random(0x514d564d);
    byte[] original =
        new QvmAssembler()
            .bss(1024)
            .op(ENTER, 16)
            .op(CONST, 0)
            .op(LOAD4)
            .op(CONST, 17)
            .op(ADD)
            .op(LEAVE, 16)
            .bytes();
    var limits = new QvmInstance.Limits(500, 20, 0, 32, 16, 2);
    int ran = 0, rejected = 0;
    for (int i = 0; i < 2048; i++) {
      byte[] bytes = original.clone();
      for (int j = 0; j < 1 + i % 4; j++)
        bytes[random.nextInt(bytes.length)] = (byte) random.nextInt();
      // Keep memory allocations small: this corpus targets code/section handling, not heap size.
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1024);
      QvmImage image;
      try {
        image = QvmImage.parse("mutated synthetic " + i, bytes, 512);
      } catch (QvmFormatException failure) {
        assertFalse(failure.getMessage().isBlank());
        rejected++;
        continue;
      }
      if (image.memorySize() > 16384) continue;
      var vm = new QvmInstance(image, (v, s, a) -> 0, limits, () -> 0);
      ran++;
      try {
        vm.vmMain(i);
      } catch (QvmException contained) {
        assertTrue(vm.stats().faulted());
        assertFalse(contained.module().isBlank());
        assertNotNull(contained.reason());
        assertNotEquals(HOST_FAILURE, contained.reason(), contained::getMessage);
      }
    }
    assertTrue(ran > 0 && rejected > 0, "ran " + ran + ", rejected " + rejected);
  }

  @Test
  void programStackAndHostReentryHaveIndependentLimits() {
    var recursive = new QvmAssembler().stack(128).op(ENTER, 64).op(CONST, 0).op(CALL).op(LEAVE, 64);
    var vm = recursive.vm((m, s, a) -> 0, new QvmInstance.Limits(500, 20, 0, 32, 100, 4));
    var exhausted = assertThrows(QvmException.class, () -> vm.vmMain(0));
    assertEquals(STACK, exhausted.reason());
    assertTrue(exhausted.getMessage().contains("Program stack exhausted"), exhausted::getMessage);
    var callback = new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(CALL).op(LEAVE, 8);
    var reentrant =
        callback.vm((self, s, a) -> self.vmMain(0), new QvmInstance.Limits(500, 20, 0, 32, 100, 2));
    assertEquals(STACK, assertThrows(QvmException.class, () -> reentrant.vmMain(0)).reason());
    assertTrue(reentrant.stats().faulted());
    assertEquals(reentrant.memory().size(), reentrant.stats().programStack());
  }

  @Test
  void checksCancellationOnReturningFromAHostCall() {
    var module = new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(CALL).op(LEAVE, 8);
    var vm =
        module.vm(
            (m, s, a) -> {
              Thread.currentThread().interrupt();
              return 0;
            });
    try {
      var interrupted = assertThrows(QvmException.class, () -> vm.vmMain(0));
      assertEquals(INTERRUPTED, interrupted.reason());
      assertEquals(0, interrupted.syscall());
      assertTrue(vm.stats().faulted());
    } finally {
      Thread.interrupted();
    }
    var clock = new ManualClock(1000);
    var expired =
        new QvmInstance(
            module.image(),
            (m, s, a) -> {
              clock.advance(5);
              return 0;
            },
            new QvmInstance.Limits(500, 20, 1, 32, 100, 2),
            clock);
    assertEquals(DEADLINE, assertThrows(QvmException.class, () -> expired.vmMain(0)).reason());
  }

  @Test
  void swallowedNestedFaultsStillFaultTheOuterInvocation() {
    var module =
        new QvmAssembler()
            .op(ENTER, 8)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 0)
            .op(NE, 8)
            .op(CONST, -1)
            .op(CALL)
            .op(LEAVE, 8)
            .op(BREAK);
    var vm =
        module.vm(
            (self, s, a) -> {
              assertThrows(QvmException.class, () -> self.vmMain(1));
              return 0;
            });
    var failure = assertThrows(QvmException.class, () -> vm.vmMain(0));
    assertEquals(FAULTED, failure.reason());
    assertEquals(0, failure.syscall());
  }
}
