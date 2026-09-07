package dev.bluevista.craftq3.vm;

import static dev.bluevista.craftq3.vm.Opcode.*;
import static dev.bluevista.craftq3.vm.QvmException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class QvmInterpreterTest {
  private static QvmInterpreter.Limits limits(
      long work, long syscalls, int operands, int calls, int stack, int reentries) {
    return new QvmInterpreter.Limits(work, syscalls, 0, operands, calls, stack, reentries);
  }

  private static QvmTestModule returnValue(int value) {
    return new QvmTestModule().op(ENTER, 8).op(CONST, value).op(LEAVE, 8);
  }

  private static int binary(Opcode opcode, int left, int right) throws Exception {
    return new QvmTestModule()
        .op(ENTER, 8)
        .op(CONST, left)
        .op(CONST, right)
        .op(opcode)
        .op(LEAVE, 8)
        .vm()
        .invoke(0);
  }

  private static int unary(Opcode opcode, int operand) throws Exception {
    return new QvmTestModule()
        .op(ENTER, 8)
        .op(CONST, operand)
        .op(opcode)
        .op(LEAVE, 8)
        .vm()
        .invoke(0);
  }

  private static void fault(QvmException.Reason reason, QvmTestModule code) throws Exception {
    var vm = code.vm();
    var failure = assertThrows(QvmException.class, () -> vm.invoke(0));
    assertEquals(reason, failure.reason());
    assertEquals("synthetic", failure.module());
    assertTrue(failure.instruction() >= 0);
    assertTrue(vm.stats().faulted());
    assertEquals(FAULTED, assertThrows(QvmException.class, () -> vm.invoke(0)).reason());
  }

  @Test
  void vmMainArgumentsAndProcedureArgumentsUseDistinctCheckedFrames() throws Exception {
    // Root's two arguments are at frame + 12 / +16; command is at frame +8.
    var code =
        new QvmTestModule()
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
    assertEquals(35, vm.invoke(2, 3, 7));
    assertEquals(2, vm.stats().maxCallDepth());
    assertEquals(vm.memory().size(), vm.stats().programStack());
    assertEquals(20, vm.stats().instructions());
    assertEquals(1L, vm.stats().opcodeCounts().get(CALL));
  }

  @Test
  void preservesCallerOperandsAcrossAProcedureCall() throws Exception {
    var vm =
        new QvmTestModule()
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
    assertEquals(42, vm.invoke(0));
  }

  @Test
  void syscallReceivesNumberArgumentsAndOwnedMemoryAndReturnsAnOperand() throws Exception {
    var module =
        new QvmTestModule()
            .op(ENTER, 20)
            .op(CONST, 32)
            .op(ARG, 8)
            .op(CONST, 0x12345678)
            .op(ARG, 12)
            .op(CONST, -4)
            .op(CALL)
            .op(LEAVE, 20)
            .module();
    var vm =
        new QvmInterpreter(
            module,
            (memory, syscall, args) -> {
              assertEquals(3, syscall);
              assertEquals(15, args.length);
              assertEquals(32, args[0]);
              assertEquals(0x12345678, args[1]);
              memory.writeInt(args[0], args[1]);
              args[0] = 0; // Host owns the snapshot, not the guest frame.
              return 17;
            });
    assertEquals(17, vm.invoke(0));
    assertEquals(0x12345678, vm.memory().readInt(32));
    assertEquals(1, vm.stats().syscalls());
  }

  @Test
  void globalsPersistBetweenEntriesAndResetRestoresDataBssAndFaultState() throws Exception {
    var vm =
        new QvmTestModule()
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
    assertEquals(21, vm.invoke(0));
    assertEquals(22, vm.invoke(0));
    vm.memory().writeByte(100, 255);
    vm.reset();
    assertEquals(0, vm.memory().readUnsignedByte(100));
    assertEquals(0, vm.stats().instructions());
    assertEquals(21, vm.invoke(0));
    var broken = new QvmTestModule().op(ENTER, 8).op(POP).vm();
    assertThrows(QvmException.class, () -> broken.invoke(0));
    broken.reset();
    assertFalse(broken.stats().faulted());
    assertEquals(STACK, assertThrows(QvmException.class, () -> broken.invoke(0)).reason());
  }

  @Test
  void executesIntegerArithmeticWithExplicitSignedUnsignedAndShiftSemantics() throws Exception {
    assertEquals(Integer.MIN_VALUE, binary(ADD, Integer.MAX_VALUE, 1));
    assertEquals(7, binary(SUB, 10, 3));
    assertEquals(-6, binary(MULI, -2, 3));
    assertEquals(-2, binary(DIVI, -7, 3));
    assertEquals(-1, binary(MODI, -7, 3));
    assertEquals(Integer.MIN_VALUE, binary(DIVI, Integer.MIN_VALUE, -1));
    assertEquals(0x7fffffff, binary(DIVU, -1, 2));
    assertEquals(1, binary(MODU, -1, 2));
    assertEquals(-2, binary(MULU, -1, 2));
    assertEquals(0x24, binary(BAND, 0x34, 0x27));
    assertEquals(0x37, binary(BOR, 0x34, 0x27));
    assertEquals(0x13, binary(BXOR, 0x34, 0x27));
    assertEquals(2, binary(LSH, 1, 33));
    assertEquals(-4, binary(RSHI, -8, 1));
    assertEquals(0x7ffffffc, binary(RSHU, -8, 1));
    assertEquals(-128, unary(SEX8, 0x80));
    assertEquals(-32768, unary(SEX16, 0x8000));
    assertEquals(-42, unary(NEGI, 42));
    assertEquals(0xfffffffe, unary(BCOM, 1));
    fault(ARITHMETIC, new QvmTestModule().op(ENTER, 8).op(CONST, 1).op(CONST, 0).op(DIVI));
  }

  @Test
  void floatOpsAndConversionsUseIeeeSinglePrecisionBitPatterns() throws Exception {
    int two = Float.floatToIntBits(2f), three = Float.floatToIntBits(3f);
    assertEquals(5f, Float.intBitsToFloat(binary(ADDF, two, three)));
    assertEquals(-1f, Float.intBitsToFloat(binary(SUBF, two, three)));
    assertEquals(6f, Float.intBitsToFloat(binary(MULF, two, three)));
    assertEquals(1.5f, Float.intBitsToFloat(binary(DIVF, three, two)));
    assertEquals(Float.POSITIVE_INFINITY, Float.intBitsToFloat(binary(DIVF, two, 0)));
    assertEquals(0x80000000, unary(NEGF, 0));
    assertEquals(-3f, Float.intBitsToFloat(unary(CVIF, -3)));
    assertEquals(-3, unary(CVFI, Float.floatToIntBits(-3.9f)));
    assertEquals(Integer.MIN_VALUE, unary(CVFI, Float.floatToIntBits(Float.NaN)));
    assertEquals(Integer.MIN_VALUE, unary(CVFI, Float.floatToIntBits(Float.POSITIVE_INFINITY)));
  }

  private static int comparison(Opcode opcode, int left, int right) throws Exception {
    return new QvmTestModule()
        .op(ENTER, 8)
        .op(CONST, left)
        .op(CONST, right)
        .op(opcode, 6)
        .op(CONST, 0)
        .op(LEAVE, 8)
        .op(CONST, 1)
        .op(LEAVE, 8)
        .vm()
        .invoke(0);
  }

  @Test
  void branchesCompareSignedUnsignedAndNanWithoutHostTypeConfusion() throws Exception {
    assertEquals(1, comparison(EQ, 7, 7));
    assertEquals(1, comparison(NE, 7, 8));
    assertEquals(1, comparison(LTI, -1, 0));
    assertEquals(0, comparison(LTU, -1, 0));
    assertEquals(1, comparison(LEI, 5, 5));
    assertEquals(1, comparison(LEU, 5, 5));
    assertEquals(1, comparison(GTI, 6, 5));
    assertEquals(1, comparison(GTU, -1, 5));
    assertEquals(1, comparison(GEI, 5, 5));
    assertEquals(1, comparison(GEU, 5, 5));
    int nan = Float.floatToIntBits(Float.NaN), two = Float.floatToIntBits(2);
    assertEquals(0, comparison(EQF, nan, nan));
    assertEquals(1, comparison(NEF, nan, nan));
    assertEquals(0, comparison(LTF, nan, two));
    assertEquals(1, comparison(LEF, two, two));
    assertEquals(1, comparison(GTF, two, 0));
    assertEquals(1, comparison(GEF, two, two));
  }

  @Test
  void loadStoreWidthsAndWrappedStartingAddressesStayInOwnedBytes() throws Exception {
    var vm =
        new QvmTestModule()
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
    assertEquals(0x128a, vm.invoke(0));
    assertArrayEquals(new byte[] {0x78, 0x56, 0x34, 0x12}, vm.memory().readBytes(1, 4));
    var wrapped =
        new QvmTestModule().data(77).op(ENTER, 8).op(CONST, 131072).op(LOAD4).op(LEAVE, 8).vm();
    assertEquals(77, wrapped.invoke(0));
    fault(MEMORY_BOUNDS, new QvmTestModule().op(ENTER, 8).op(CONST, -1).op(LOAD4));
  }

  @Test
  void blockCopyHasAnImmediateAndDefinedOverlapAndChargesByteWork() throws Exception {
    var code =
        new QvmTestModule()
            .data(0x04030201, 0x08070605, 0)
            .op(ENTER, 8)
            .op(CONST, 4)
            .op(CONST, 0)
            .op(BLOCK_COPY, 8)
            .op(CONST, 8)
            .op(LOAD4)
            .op(LEAVE, 8);
    assertEquals(0x08070605, code.vm().invoke(0));
    var vm = new QvmInterpreter(code.module(), (m, s, a) -> 0, limits(4, 1, 16, 4, 65536, 1));
    assertEquals(INSTRUCTION_BUDGET, assertThrows(QvmException.class, () -> vm.invoke(0)).reason());
    fault(
        MEMORY_BOUNDS,
        new QvmTestModule().op(ENTER, 8).op(CONST, 0).op(CONST, -4).op(BLOCK_COPY, 8));
  }

  @Test
  void protectsOperandFramesReturnAddressesAndProcedureBoundaries() throws Exception {
    fault(STACK, new QvmTestModule().op(ENTER, 8).op(POP));
    fault(STACK, new QvmTestModule().op(ENTER, 8).op(CONST, 1).op(CONST, 2).op(LEAVE, 8));
    fault(STACK, new QvmTestModule().op(ENTER, 8).op(CONST, 1).op(LEAVE, 12));
    fault(STACK, new QvmTestModule().op(ENTER, 8).op(CONST, 1).op(ARG, 8));
    fault(CONTROL_FLOW, new QvmTestModule().op(ENTER, 8).op(CONST, 1).op(CALL));
    fault(CONTROL_FLOW, new QvmTestModule().op(ENTER, 8).op(CONST, Integer.MAX_VALUE).op(CALL));
    fault(CONTROL_FLOW, new QvmTestModule().op(ENTER, 8).op(CONST, 3).op(JUMP).op(ENTER, 8));
    fault(
        CONTROL_FLOW,
        new QvmTestModule()
            .op(ENTER, 8)
            .op(LOCAL, 8)
            .op(CONST, 7)
            .op(STORE4)
            .op(CONST, 0)
            .op(LEAVE, 8));
    fault(UNSUPPORTED_OPCODE, new QvmTestModule().op(ENTER, 8).op(BREAK));
    fault(UNSUPPORTED_OPCODE, new QvmTestModule().op(ENTER, 8).op(UNDEF));
  }

  @Test
  void v2IndirectJumpsRequireDeclaredTargetsWhileDirectJumpsWork() throws Exception {
    var code =
        new QvmTestModule()
            .data(5)
            .op(ENTER, 8)
            .op(CONST, 0)
            .op(LOAD4)
            .op(JUMP)
            .op(IGNORE)
            .op(CONST, 42)
            .op(LEAVE, 8);
    assertEquals(42, code.v2(5).vm().invoke(0));
    fault(CONTROL_FLOW, code.v2());
    assertEquals(
        42,
        new QvmTestModule()
            .v2()
            .op(ENTER, 8)
            .op(CONST, 3)
            .op(JUMP)
            .op(CONST, 42)
            .op(LEAVE, 8)
            .vm()
            .invoke(0));
  }

  @Test
  void v2CannotDisguiseIndirectJumpByBranchingPastAConstant() throws Exception {
    fault(
        CONTROL_FLOW,
        new QvmTestModule()
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
  void containsInfiniteLoopsRecursionOperandGrowthAndSyscallSpam() throws Exception {
    var loop = new QvmTestModule().op(ENTER, 8).op(CONST, 1).op(JUMP).module();
    var vm = new QvmInterpreter(loop, (m, s, a) -> 0, limits(100, 10, 16, 10, 65536, 1));
    vm.setTracing(true);
    assertEquals(INSTRUCTION_BUDGET, assertThrows(QvmException.class, () -> vm.invoke(0)).reason());
    assertEquals(100, vm.stats().instructions());
    assertEquals(100, vm.recentTrace().size());
    var recursion = new QvmTestModule().op(ENTER, 8).op(CONST, 0).op(CALL).op(LEAVE, 8).module();
    var recursiveVm =
        new QvmInterpreter(recursion, (m, s, a) -> 0, limits(1000, 10, 16, 4, 65536, 1));
    assertEquals(STACK, assertThrows(QvmException.class, () -> recursiveVm.invoke(0)).reason());
    var growth = new QvmTestModule().op(ENTER, 8).op(PUSH).op(CONST, 1).op(JUMP).module();
    var growingVm = new QvmInterpreter(growth, (m, s, a) -> 0, limits(1000, 10, 16, 4, 65536, 1));
    assertEquals(STACK, assertThrows(QvmException.class, () -> growingVm.invoke(0)).reason());
    var spam =
        new QvmTestModule()
            .op(ENTER, 8)
            .op(CONST, -1)
            .op(CALL)
            .op(POP)
            .op(CONST, 1)
            .op(JUMP)
            .module();
    var spamVm = new QvmInterpreter(spam, (m, s, a) -> 0, limits(1000, 3, 16, 4, 65536, 1));
    assertEquals(SYSCALL_BUDGET, assertThrows(QvmException.class, () -> spamVm.invoke(0)).reason());
    assertEquals(3, spamVm.stats().syscalls());
  }

  @Test
  void reentrantSyscallsRestoreFramesAndShareTheOuterExecutionBudget() throws Exception {
    // Command zero invokes trap0; command nonzero returns the command. Nested entry must preserve
    // root.
    var module =
        new QvmTestModule()
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
            .op(LEAVE, 8)
            .module();
    var reference = new AtomicReference<QvmInterpreter>();
    var vm = new QvmInterpreter(module, (m, s, a) -> reference.get().invoke(42));
    reference.set(vm);
    assertEquals(42, vm.invoke(0));
    assertEquals(2, vm.stats().invocations());
    assertEquals(2, vm.stats().maxCallDepth());
    assertEquals(vm.memory().size(), vm.stats().programStack());
    var limited =
        new QvmInterpreter(
            module, (m, s, a) -> reference.get().invoke(42), limits(10, 10, 16, 10, 65536, 4));
    reference.set(limited);
    assertEquals(
        INSTRUCTION_BUDGET, assertThrows(QvmException.class, () -> limited.invoke(0)).reason());
  }

  @Test
  void hostFailuresAndCancellationAreContainedAndTracesAreBounded() throws Exception {
    var module = new QvmTestModule().op(ENTER, 8).op(CONST, -1).op(CALL).op(LEAVE, 8).module();
    var vm =
        new QvmInterpreter(
            module,
            (m, s, a) -> {
              throw new IllegalArgumentException("synthetic failure");
            });
    var failure = assertThrows(QvmException.class, () -> vm.invoke(0));
    assertEquals(HOST_FAILURE, failure.reason());
    assertTrue(failure.getMessage().contains("Syscall 0"));
    Thread.currentThread().interrupt();
    try {
      var interrupted = returnValue(0).vm();
      assertEquals(
          INTERRUPTED, assertThrows(QvmException.class, () -> interrupted.invoke(0)).reason());
    } finally {
      Thread.interrupted();
    }
    var loop = new QvmTestModule().op(ENTER, 8).op(CONST, 1).op(JUMP).module();
    var tracing = new QvmInterpreter(loop, (m, s, a) -> 0, limits(300, 1, 16, 4, 65536, 1));
    tracing.setTracing(true);
    assertThrows(QvmException.class, () -> tracing.invoke(0));
    assertEquals(128, tracing.recentTrace().size());
    assertThrows(UnsupportedOperationException.class, () -> tracing.recentTrace().clear());
    tracing.setTracing(false);
    assertTrue(tracing.recentTrace().isEmpty());
  }
}
