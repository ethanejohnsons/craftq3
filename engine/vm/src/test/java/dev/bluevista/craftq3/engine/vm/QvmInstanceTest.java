package dev.bluevista.craftq3.engine.vm;

import static dev.bluevista.craftq3.engine.vm.Opcode.*;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class QvmInstanceTest {
  private static final Syscalls ZERO = (vm, number, args) -> 0;

  private static QvmInstance.Limits limits(
      long work, long syscalls, int operands, int calls, int reentries) {
    return new QvmInstance.Limits(work, syscalls, 0, operands, calls, reentries);
  }

  private static QvmAssembler returnValue(int value) {
    return new QvmAssembler().op(ENTER, 8).op(CONST, value).op(LEAVE, 8);
  }

  static void fault(QvmException.Reason reason, QvmAssembler code) {
    var vm = code.vm();
    var failure = assertThrows(QvmException.class, () -> vm.vmMain(0));
    assertEquals(reason, failure.reason(), failure::getMessage);
    assertEquals("synthetic", failure.module());
    assertTrue(failure.pc() >= 0);
    assertNotNull(failure.opcode());
    assertTrue(vm.faulted());
    assertTrue(vm.stats().faulted());
    assertEquals(FAULTED, assertThrows(QvmException.class, () -> vm.vmMain(0)).reason());
  }

  @Test
  void vmMainArgumentsAndProcedureArgumentsUseDistinctCheckedFrames() {
    // Root's arguments are at frame + 12 / +16 past its 20-byte frame; the command is at +8.
    var code =
        new QvmAssembler()
            .op(ENTER, 20)
            .op(LOCAL, 28)
            .op(LOAD4)
            .op(LOCAL, 32)
            .op(LOAD4)
            .op(ADD)
            .op(ARG, 8)
            .op(LOCAL, 36)
            .op(LOAD4)
            .op(ARG, 12)
            .op(CONST, 13)
            .op(CALL)
            .op(LEAVE, 20)
            .op(ENTER, 8)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(LOCAL, 20)
            .op(LOAD4)
            .op(MULI)
            .op(LEAVE, 8);
    var vm = code.vm();
    var trace = new TraceRecorder(64);
    vm.setTracer(trace);
    assertEquals(35, vm.vmMain(2, 3, 7));
    assertEquals(2, vm.stats().maxCallDepth());
    assertEquals(vm.memory().size(), vm.stats().programStack());
    assertEquals(20, vm.stats().instructions());
    assertEquals(20, trace.recorded());
    assertEquals(1L, trace.histogram().get(CALL));
    assertEquals(2L, trace.histogram().get(ENTER));
    assertEquals(35, vm.vmMain(2, new int[] {3, 7}));
    assertEquals(35, vm.vmMain(2, 3, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> vm.vmMain(0, new int[13]));
  }

  @Test
  void preservesCallerOperandsAcrossAProcedureCall() {
    var vm =
        new QvmAssembler()
            .op(ENTER, 8)
            .op(CONST, 40)
            .op(CONST, 6)
            .op(CALL)
            .op(ADD)
            .op(LEAVE, 8)
            .op(ENTER, 8)
            .op(CONST, 2)
            .op(LEAVE, 8)
            .vm();
    assertEquals(42, vm.vmMain(0));
  }

  @Test
  void syscallReceivesNumberArgumentsAndOwnedMemoryAndReturnsAnOperand() {
    var code =
        new QvmAssembler()
            .op(ENTER, 20)
            .op(CONST, 32)
            .op(ARG, 8)
            .op(CONST, 0x12345678)
            .op(ARG, 12)
            .op(CONST, -4)
            .op(CALL)
            .op(LEAVE, 20);
    var buffers = new AtomicReference<int[]>();
    var vm =
        code.vm(
            (instance, syscall, args) -> {
              assertEquals(3, syscall);
              assertEquals(Syscalls.MAX_ARGS, args.length);
              assertEquals(32, args[0]);
              assertEquals(0x12345678, args[1]);
              assertEquals(syscall, instance.memory().readInt(instance.programStack() + 4));
              instance.memory().writeInt(args[0], args[1]);
              args[0] = 0; // The host owns the snapshot, not the guest frame.
              if (buffers.get() != null) assertSame(buffers.get(), args, "buffer is reused");
              buffers.set(args);
              return 17;
            });
    assertEquals(17, vm.vmMain(0));
    assertEquals(17, vm.vmMain(0));
    assertEquals(0x12345678, vm.memory().readInt(32));
    assertEquals(2, vm.stats().syscalls());
  }

  @Test
  void globalsPersistBetweenEntriesAndResetRestoresDataBssAndFaultState() {
    var vm =
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
            .op(LEAVE, 8)
            .vm();
    assertEquals(21, vm.vmMain(0));
    assertEquals(22, vm.vmMain(0));
    vm.memory().writeByte(100, 255);
    vm.reset();
    assertEquals(0, vm.memory().readUnsignedByte(100));
    assertEquals(0, vm.stats().instructions());
    assertEquals(21, vm.vmMain(0));
    var broken = new QvmAssembler().op(ENTER, 8).op(POP).vm();
    assertThrows(QvmException.class, () -> broken.vmMain(0));
    broken.reset();
    assertFalse(broken.stats().faulted());
    assertEquals(STACK, assertThrows(QvmException.class, () -> broken.vmMain(0)).reason());
  }

  @Test
  void loadStoreWidthsAndWrappedStartingAddressesStayInOwnedBytes() {
    var vm =
        new QvmAssembler()
            .op(ENTER, 8)
            .op(CONST, 1)
            .op(CONST, 0x12345678)
            .op(STORE4)
            .op(CONST, 2)
            .op(LOAD1)
            .op(CONST, 3)
            .op(LOAD2)
            .op(ADD)
            .op(LEAVE, 8)
            .vm();
    assertEquals(0x128a, vm.vmMain(0));
    assertArrayEquals(new byte[] {0x78, 0x56, 0x34, 0x12}, vm.memory().readBytes(1, 4));
    var wrapped =
        new QvmAssembler().data(77).op(ENTER, 8).op(CONST, 131072).op(LOAD4).op(LEAVE, 8).vm();
    assertEquals(77, wrapped.vmMain(0));
    fault(MEMORY_BOUNDS, new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(LOAD4));
  }

  @Test
  void blockCopyHasAnImmediateAndDefinedOverlapAndChargesByteWork() {
    var code =
        new QvmAssembler()
            .data(0x04030201, 0x08070605, 0)
            .op(ENTER, 8)
            .op(CONST, 4)
            .op(CONST, 0)
            .op(BLOCK_COPY, 8)
            .op(CONST, 8)
            .op(LOAD4)
            .op(LEAVE, 8);
    assertEquals(0x08070605, code.vm().vmMain(0));
    var vm = code.vm(ZERO, limits(4, 1, 16, 4, 1));
    assertEquals(INSTRUCTION_BUDGET, assertThrows(QvmException.class, () -> vm.vmMain(0)).reason());
    fault(
        MEMORY_BOUNDS,
        new QvmAssembler().op(ENTER, 8).op(CONST, 0).op(CONST, -4).op(BLOCK_COPY, 8));
  }

  @Test
  void protectsOperandFramesReturnAddressesAndProcedureBoundaries() {
    fault(STACK, new QvmAssembler().op(ENTER, 8).op(POP));
    fault(STACK, new QvmAssembler().op(ENTER, 8).op(ADD));
    fault(STACK, new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(CONST, 2).op(LEAVE, 8));
    fault(STACK, new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(LEAVE, 12));
    fault(STACK, new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(ARG, 8));
    fault(CONTROL_FLOW, new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(CALL));
    fault(CONTROL_FLOW, new QvmAssembler().op(ENTER, 8).op(CONST, Integer.MAX_VALUE).op(CALL));
    fault(CONTROL_FLOW, new QvmAssembler().op(ENTER, 8).op(CONST, 3).op(JUMP).op(ENTER, 8));
    fault(CONTROL_FLOW, new QvmAssembler().op(ENTER, 8).op(CONST, 0).op(JUMP));
    fault(CONTROL_FLOW, new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(ENTER, 8));
    fault(CONTROL_FLOW, new QvmAssembler().op(ENTER, 8).op(CONST, 1));
    fault(
        CONTROL_FLOW,
        new QvmAssembler()
            .op(ENTER, 8)
            .op(LOCAL, 8)
            .op(CONST, 7)
            .op(STORE4)
            .op(CONST, 0)
            .op(LEAVE, 8));
    fault(UNSUPPORTED_OPCODE, new QvmAssembler().op(ENTER, 8).op(BREAK));
    fault(UNSUPPORTED_OPCODE, new QvmAssembler().op(ENTER, 8).op(UNDEF));
  }

  @Test
  void v2IndirectJumpsRequireDeclaredTargetsWhileDirectJumpsWork() {
    var code =
        new QvmAssembler()
            .data(5)
            .op(ENTER, 8)
            .op(CONST, 0)
            .op(LOAD4)
            .op(JUMP)
            .op(IGNORE)
            .op(CONST, 42)
            .op(LEAVE, 8);
    assertEquals(42, code.v2(5).vm().vmMain(0));
    assertEquals(42, code.v2(1, 5, 6).vm().vmMain(0));
  }

  @Test
  void v2IndirectJumpOutsideTheTableFaults() {
    var code =
        new QvmAssembler()
            .data(5)
            .op(ENTER, 8)
            .op(CONST, 0)
            .op(LOAD4)
            .op(JUMP)
            .op(IGNORE)
            .op(CONST, 42)
            .op(LEAVE, 8);
    fault(CONTROL_FLOW, code.v2());
    var v1 =
        new QvmAssembler()
            .data(5)
            .op(ENTER, 8)
            .op(CONST, 0)
            .op(LOAD4)
            .op(JUMP)
            .op(IGNORE)
            .op(CONST, 42)
            .op(LEAVE, 8);
    assertEquals(42, v1.vm().vmMain(0), "v1 has no jump table to consult");
    assertEquals(
        42,
        new QvmAssembler()
            .v2()
            .op(ENTER, 8)
            .op(CONST, 3)
            .op(JUMP)
            .op(CONST, 42)
            .op(LEAVE, 8)
            .vm()
            .vmMain(0));
  }

  @Test
  void v2CannotDisguiseIndirectJumpByBranchingPastAConstant() {
    fault(
        CONTROL_FLOW,
        new QvmAssembler()
            .v2()
            .op(ENTER, 8)
            .op(CONST, 8)
            .op(CONST, 0)
            .op(CONST, 0)
            .op(EQ, 7)
            .op(IGNORE)
            .op(CONST, 8)
            .op(JUMP)
            .op(CONST, 42)
            .op(LEAVE, 8));
  }

  @Test
  void containsInfiniteLoopsRecursionOperandGrowthAndSyscallSpam() {
    var loop = new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(JUMP);
    var vm = loop.vm(ZERO, limits(100, 10, 16, 10, 1));
    var trace = new TraceRecorder(128);
    vm.setTracer(trace);
    assertEquals(INSTRUCTION_BUDGET, assertThrows(QvmException.class, () -> vm.vmMain(0)).reason());
    // Budgets are charged per straight-line run, so the fault lands within one run of the limit.
    long executed = vm.stats().instructions();
    assertTrue(executed > 100 && executed <= 102, "executed " + executed);
    assertEquals(executed, trace.recorded());
    assertEquals(executed, trace.recent().size());
    var recursion = new QvmAssembler().op(ENTER, 8).op(CONST, 0).op(CALL).op(LEAVE, 8);
    var recursiveVm = recursion.vm(ZERO, limits(1000, 10, 16, 4, 1));
    assertEquals(STACK, assertThrows(QvmException.class, () -> recursiveVm.vmMain(0)).reason());
    assertEquals(4, recursiveVm.stats().maxCallDepth());
    var growth = new QvmAssembler().op(ENTER, 8).op(PUSH).op(CONST, 1).op(JUMP);
    var growingVm = growth.vm(ZERO, limits(1000, 10, 16, 4, 1));
    var overflow = assertThrows(QvmException.class, () -> growingVm.vmMain(0));
    assertEquals(STACK, overflow.reason());
    assertTrue(overflow.getMessage().contains("overflow"), overflow::getMessage);
    var spam = new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(CALL).op(POP).op(CONST, 1).op(JUMP);
    var spamVm = spam.vm(ZERO, limits(1000, 3, 16, 4, 1));
    assertEquals(SYSCALL_BUDGET, assertThrows(QvmException.class, () -> spamVm.vmMain(0)).reason());
    assertEquals(3, spamVm.stats().syscalls());
  }

  @Test
  void reentrantSyscallsRestoreFramesAndShareTheOuterExecutionBudget() {
    // Command zero invokes trap 0, which re-enters with command 42; nonzero returns the command.
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
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(LEAVE, 8);
    var vm = module.vm((self, number, args) -> self.vmMain(42));
    assertEquals(42, vm.vmMain(0));
    assertEquals(2, vm.stats().invocations());
    assertEquals(2, vm.stats().maxCallDepth());
    assertEquals(vm.memory().size(), vm.stats().programStack());
    assertEquals(42, vm.vmMain(0), "state is clean for the next frame");
    var limited = module.vm((self, number, args) -> self.vmMain(42), limits(10, 10, 16, 10, 4));
    assertEquals(
        INSTRUCTION_BUDGET, assertThrows(QvmException.class, () -> limited.vmMain(0)).reason());
  }

  @Test
  void nestedInvocationsKeepTheirOwnArgumentBuffers() {
    // trap 0 with arg 7: the handler re-enters (trap 0 again with arg 9) and then reads its args.
    var module =
        new QvmAssembler()
            .op(ENTER, 16)
            .op(LOCAL, 24)
            .op(LOAD4)
            .op(ARG, 8)
            .op(CONST, -1)
            .op(CALL)
            .op(LEAVE, 16);
    var vm =
        module.vm(
            (self, number, args) -> {
              int mine = args[0];
              if (mine == 7) assertEquals(9 * 2, self.vmMain(9));
              assertEquals(mine, args[0]);
              return mine * 2;
            });
    assertEquals(14, vm.vmMain(7));
  }

  @Test
  void hostFailuresAndCancellationAreContainedAndTracesAreBounded() {
    var module = new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(CALL).op(LEAVE, 8);
    var vm =
        module.vm(
            (self, number, args) -> {
              throw new IllegalArgumentException("synthetic failure");
            });
    var failure = assertThrows(QvmException.class, () -> vm.vmMain(0));
    assertEquals(HOST_FAILURE, failure.reason());
    assertEquals(0, failure.syscall());
    assertEquals(CALL, failure.opcode());
    assertEquals(2, failure.pc());
    assertTrue(failure.getMessage().contains("Syscall 0"), failure::getMessage);
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    Thread.currentThread().interrupt();
    try {
      var interrupted = returnValue(0).vm();
      assertEquals(
          INTERRUPTED, assertThrows(QvmException.class, () -> interrupted.vmMain(0)).reason());
    } finally {
      Thread.interrupted();
    }
    var loop = new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(JUMP);
    var tracing = loop.vm(ZERO, limits(300, 1, 16, 4, 1));
    var trace = new TraceRecorder(128);
    tracing.setTracer(trace);
    assertThrows(QvmException.class, () -> tracing.vmMain(0));
    assertEquals(128, trace.recent().size());
    assertEquals(JUMP, trace.recent().getLast().opcode());
    assertThrows(UnsupportedOperationException.class, () -> trace.recent().clear());
    trace.clear();
    assertTrue(trace.recent().isEmpty());
    tracing.setTracer(null);
    tracing.reset();
    assertThrows(QvmException.class, () -> tracing.vmMain(0));
    assertTrue(trace.recent().isEmpty(), "a removed tracer sees nothing");
  }

  @Test
  void ownerThreadIsEnforcedWhenClaimed() throws Exception {
    var vm = returnValue(5).vm();
    vm.owner().claim();
    var error = new AtomicReference<Throwable>();
    var other = new Thread(() -> error.set(catching(() -> vm.vmMain(0))));
    other.start();
    other.join();
    assertInstanceOf(IllegalStateException.class, error.get());
    assertEquals(5, vm.vmMain(0));
    vm.owner().release();
  }

  private static Throwable catching(Runnable action) {
    try {
      action.run();
      return null;
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  @Test
  void defaultLimitsAndClockAreUsable() {
    var vm =
        new QvmInstance(
            returnValue(3).image(), ZERO, QvmInstance.Limits.defaults(), Clock.system());
    assertEquals(3, vm.vmMain(0));
    assertThrows(IllegalArgumentException.class, () -> new QvmInstance.Limits(0, 1, 0, 16, 1, 1));
  }
}
