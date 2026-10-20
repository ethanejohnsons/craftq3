package dev.bluevista.craftq3.vm;

import static dev.bluevista.craftq3.vm.Opcode.*;
import static dev.bluevista.craftq3.vm.QvmException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class QvmSecurityTest {
  @Test
  void boundedMalformedCorpusCanOnlyDecodeRunOrProduceAContainedFault() throws Exception {
    var random = new Random(0x514d564d);
    byte[] original =
        new QvmTestModule()
            .bss(1024)
            .op(ENTER, 16)
            .op(CONST, 0)
            .op(LOAD4)
            .op(CONST, 17)
            .op(ADD)
            .op(LEAVE, 16)
            .bytes();
    var limits = new QvmInterpreter.Limits(500, 20, 0, 32, 16, 1024, 2);
    for (int i = 0; i < 512; i++) {
      byte[] bytes = original.clone();
      for (int j = 0; j < 1 + i % 4; j++)
        bytes[random.nextInt(bytes.length)] = (byte) random.nextInt();
      // Keep memory allocations small: this corpus targets code/section handling, not heap size.
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1024);
      try {
        var module = QvmReader.read("mutated synthetic " + i, bytes);
        if (module.memorySize() > 16384) continue;
        var vm = new QvmInterpreter(module, (m, s, a) -> 0, limits);
        try {
          vm.invoke(i);
        } catch (QvmException contained) {
          assertTrue(vm.stats().faulted());
          assertFalse(contained.module().isBlank());
          assertNotNull(contained.reason());
        }
      } catch (QvmFormatException rejected) {
        assertFalse(rejected.getMessage().isBlank());
      }
    }
  }

  @Test
  void programStackAndHostReentryHaveIndependentLimits() throws Exception {
    var recursive = new QvmTestModule().op(ENTER, 64).op(CONST, 0).op(CALL).op(LEAVE, 64).module();
    var vm =
        new QvmInterpreter(
            recursive, (m, s, a) -> 0, new QvmInterpreter.Limits(500, 20, 0, 32, 100, 128, 4));
    assertEquals(STACK, assertThrows(QvmException.class, () -> vm.invoke(0)).reason());
    var callback = new QvmTestModule().op(ENTER, 8).op(CONST, -1).op(CALL).op(LEAVE, 8).module();
    var reference = new AtomicReference<QvmInterpreter>();
    var reentrant =
        new QvmInterpreter(
            callback,
            (m, s, a) -> reference.get().invoke(0),
            new QvmInterpreter.Limits(500, 20, 0, 32, 100, 65536, 2));
    reference.set(reentrant);
    assertEquals(STACK, assertThrows(QvmException.class, () -> reentrant.invoke(0)).reason());
    assertTrue(reentrant.stats().faulted());
    assertEquals(reentrant.memory().size(), reentrant.stats().programStack());
  }

  @Test
  void checksCancellationOnReturningFromAHostCall() throws Exception {
    var module = new QvmTestModule().op(ENTER, 8).op(CONST, -1).op(CALL).op(LEAVE, 8).module();
    var vm =
        new QvmInterpreter(
            module,
            (m, s, a) -> {
              Thread.currentThread().interrupt();
              return 0;
            });
    try {
      assertEquals(INTERRUPTED, assertThrows(QvmException.class, () -> vm.invoke(0)).reason());
      assertTrue(vm.stats().faulted());
    } finally {
      Thread.interrupted();
    }
    var expired =
        new QvmInterpreter(
            module, (m, s, a) -> 0, new QvmInterpreter.Limits(500, 20, 1, 32, 100, 65536, 2));
    assertEquals(DEADLINE, assertThrows(QvmException.class, () -> expired.invoke(0)).reason());
  }
}
