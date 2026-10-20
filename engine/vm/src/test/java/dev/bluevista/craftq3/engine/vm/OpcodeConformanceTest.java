package dev.bluevista.craftq3.engine.vm;

import static dev.bluevista.craftq3.engine.vm.Opcode.*;
import static dev.bluevista.craftq3.engine.vm.QvmException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Executes every opcode at least once, with the edge cases where C, x86 and Java can disagree.
 * Expected values follow the reference interpreter on x86: shifts use the low five bits of the
 * count, {@code CVFI} truncates like {@code cvttss2si}, and integer division by zero is a fault (it
 * traps natively).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpcodeConformanceTest extends ModeSuite {
  private static final int NAN = Float.floatToIntBits(Float.NaN);
  private static final int MIN = Integer.MIN_VALUE;

  private final EnumSet<Opcode> executed = EnumSet.noneOf(Opcode.class);

  @AfterAll
  void everyOpcodeWasExecuted() {
    var missing = EnumSet.complementOf(executed);
    assertTrue(missing.isEmpty(), "never executed: " + missing);
  }

  /**
   * An instance that records coverage: executed opcodes in the interpreter; in compile mode (where
   * a tracer would force the interpreter) the opcodes of images whose every procedure compiled.
   */
  private QvmInstance traced(QvmAssembler code, Syscalls syscalls) {
    var vm = code.vm(syscalls);
    if (!compiled()) {
      vm.setTracer((pc, opcode, operand, depth, stack) -> executed.add(opcode));
      return vm;
    }
    assertEquals(QvmInstance.ExecutionMode.COMPILE, vm.executionMode());
    var report = vm.compileReport().orElseThrow();
    if (report.interpreted() == 0)
      for (int pc = 0; pc < vm.image().instructionCount(); pc++)
        executed.add(vm.image().opcode(pc));
    return vm;
  }

  private int run(QvmAssembler code) {
    return traced(code, QvmAssembler.NO_SYSCALLS).vmMain(0);
  }

  private QvmException.Reason faultOf(QvmAssembler code) {
    var vm = traced(code, QvmAssembler.NO_SYSCALLS);
    return assertThrows(QvmException.class, () -> vm.vmMain(0)).reason();
  }

  private int binary(Opcode opcode, int left, int right) {
    return run(
        new QvmAssembler().op(ENTER, 8).op(CONST, left).op(CONST, right).op(opcode).op(LEAVE, 8));
  }

  private int unary(Opcode opcode, int operand) {
    return run(new QvmAssembler().op(ENTER, 8).op(CONST, operand).op(opcode).op(LEAVE, 8));
  }

  private float binaryF(Opcode opcode, float left, float right) {
    return Float.intBitsToFloat(
        binary(opcode, Float.floatToRawIntBits(left), Float.floatToRawIntBits(right)));
  }

  /** 1 if the branch is taken, else 0. */
  private int branch(Opcode opcode, int left, int right) {
    return run(
        new QvmAssembler()
            .op(ENTER, 8)
            .op(CONST, left)
            .op(CONST, right)
            .op(opcode, 6)
            .op(CONST, 0)
            .op(LEAVE, 8)
            .op(CONST, 1)
            .op(LEAVE, 8));
  }

  private int branchF(Opcode opcode, float left, float right) {
    return branch(opcode, Float.floatToRawIntBits(left), Float.floatToRawIntBits(right));
  }

  @Test
  void undefAndBreakFaultWhileIgnoreDoesNothing() {
    assertEquals(UNSUPPORTED_OPCODE, faultOf(new QvmAssembler().op(ENTER, 8).op(UNDEF)));
    assertEquals(UNSUPPORTED_OPCODE, faultOf(new QvmAssembler().op(ENTER, 8).op(BREAK)));
    assertEquals(
        5, run(new QvmAssembler().op(ENTER, 8).op(IGNORE).op(CONST, 5).op(IGNORE).op(LEAVE, 8)));
  }

  @Test
  void enterLeaveLocalAndArgAddressTheFrames() {
    // vmMain(command) returns f(command, 10), where f(a, b) copies a into a local and returns a -
    // b.
    var code =
        new QvmAssembler()
            .op(ENTER, 16) // 0: 16-byte frame; outgoing args at +8 and +12
            .op(LOCAL, 24) // command: own frame (16) + 8
            .op(LOAD4)
            .op(ARG, 8)
            .op(CONST, 10)
            .op(ARG, 12)
            .op(CONST, 9)
            .op(CALL)
            .op(LEAVE, 16)
            .op(ENTER, 20) // 9: f, with a local at +16
            .op(LOCAL, 16)
            .op(LOCAL, 28) // a: own frame (20) + 8
            .op(LOAD4)
            .op(STORE4)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(LOCAL, 32) // b: own frame (20) + 12
            .op(LOAD4)
            .op(SUB)
            .op(LEAVE, 20);
    var vm = traced(code, QvmAssembler.NO_SYSCALLS);
    assertEquals(32, vm.vmMain(42));
    assertEquals(-10, vm.vmMain(0));
    // LOCAL yields absolute addresses below the 60-byte vmMain frame at the top of memory.
    var where = new QvmAssembler().op(ENTER, 8).op(LOCAL, 4).op(LEAVE, 8);
    var whereVm = traced(where, QvmAssembler.NO_SYSCALLS);
    assertEquals(whereVm.memory().size() - 60 - 8 + 4, whereVm.vmMain(0));
    // Negative offsets reach below the frame; shipped code (jbpow cgame) contains them.
    var below = new QvmAssembler().op(ENTER, 8).op(LOCAL, -104).op(LEAVE, 8);
    var belowVm = traced(below, QvmAssembler.NO_SYSCALLS);
    assertEquals(belowVm.memory().size() - 60 - 8 - 104, belowVm.vmMain(0));
  }

  @Test
  void callOfNegativeAddressIsASyscall() {
    List<Integer> numbers = new ArrayList<>();
    Syscalls record =
        (vm, number, args) -> {
          numbers.add(number);
          return args[0] + 1;
        };
    for (int address : new int[] {-1, -2, -101, MIN}) {
      var code =
          new QvmAssembler()
              .op(ENTER, 16)
              .op(CONST, 41)
              .op(ARG, 8)
              .op(CONST, address)
              .op(CALL)
              .op(LEAVE, 16);
      assertEquals(42, traced(code, record).vmMain(0));
    }
    assertEquals(List.of(0, 1, 100, Integer.MAX_VALUE), numbers);
  }

  @Test
  void pushPopAndConst() {
    assertEquals(0, run(new QvmAssembler().op(ENTER, 8).op(PUSH).op(LEAVE, 8)));
    assertEquals(
        -7, run(new QvmAssembler().op(ENTER, 8).op(CONST, -7).op(PUSH).op(POP).op(LEAVE, 8)));
    assertEquals(STACK, faultOf(new QvmAssembler().op(ENTER, 8).op(POP)));
  }

  @Test
  void jumpStaysInsideTheProcedure() {
    assertEquals(
        3,
        run(
            new QvmAssembler()
                .op(ENTER, 8)
                .op(CONST, 4)
                .op(JUMP)
                .op(BREAK)
                .op(CONST, 3)
                .op(LEAVE, 8)));
    assertEquals(CONTROL_FLOW, faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(JUMP)));
    assertEquals(CONTROL_FLOW, faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, 99).op(JUMP)));
  }

  @Test
  void integerComparisonsAreSignedOrUnsigned() {
    assertEquals(1, branch(EQ, 7, 7));
    assertEquals(0, branch(EQ, 7, 8));
    assertEquals(1, branch(NE, 7, 8));
    assertEquals(0, branch(NE, -1, -1));
    assertEquals(1, branch(LTI, -1, 0));
    assertEquals(0, branch(LTI, 0, 0));
    assertEquals(1, branch(LEI, 5, 5));
    assertEquals(1, branch(LEI, MIN, Integer.MAX_VALUE));
    assertEquals(1, branch(GTI, 6, 5));
    assertEquals(0, branch(GTI, -1, 0));
    assertEquals(1, branch(GEI, 5, 5));
    assertEquals(0, branch(GEI, MIN, 0));
    assertEquals(0, branch(LTU, -1, 0));
    assertEquals(1, branch(LTU, 0, -1));
    assertEquals(1, branch(LEU, 5, 5));
    assertEquals(0, branch(LEU, MIN, 1));
    assertEquals(1, branch(GTU, -1, 5));
    assertEquals(0, branch(GTU, 5, 5));
    assertEquals(1, branch(GEU, 5, 5));
    assertEquals(1, branch(GEU, MIN, Integer.MAX_VALUE));
  }

  @Test
  void floatComparisonsAreIeeeIncludingNanAndSignedZero() {
    assertEquals(1, branchF(EQF, 2f, 2f));
    assertEquals(1, branchF(EQF, 0f, -0f));
    assertEquals(0, branch(EQF, NAN, NAN));
    assertEquals(1, branch(NEF, NAN, NAN));
    assertEquals(0, branchF(NEF, -0f, 0f));
    assertEquals(1, branchF(LTF, -1f, 0f));
    assertEquals(0, branch(LTF, NAN, Float.floatToIntBits(2f)));
    assertEquals(1, branchF(LEF, 2f, 2f));
    assertEquals(0, branch(LEF, Float.floatToIntBits(2f), NAN));
    assertEquals(1, branchF(GTF, 2f, 0f));
    assertEquals(0, branch(GTF, NAN, 0));
    assertEquals(1, branchF(GEF, 2f, 2f));
    assertEquals(0, branch(GEF, NAN, NAN));
    assertEquals(1, branchF(GTF, Float.POSITIVE_INFINITY, Float.MAX_VALUE));
    // Integer compares see the raw bits: -0f and 0f differ.
    assertEquals(0, branchF(EQ, 0f, -0f));
  }

  @Test
  void loadsZeroExtendStoresTruncateAndAddressesWrapAtTheStart() {
    var code =
        new QvmAssembler()
            .data(0x80f0ff7f)
            .op(ENTER, 8)
            .op(CONST, 0)
            .op(LOAD1) // 0x7f
            .op(CONST, 1)
            .op(LOAD1) // 0xff (not sign-extended)
            .op(ADD)
            .op(CONST, 2)
            .op(LOAD2) // 0x80f0
            .op(ADD)
            .op(LEAVE, 8);
    assertEquals(0x7f + 0xff + 0x80f0, run(code));
    var stores =
        new QvmAssembler()
            .op(ENTER, 8)
            .op(CONST, 16)
            .op(CONST, 0x11223344)
            .op(STORE1)
            .op(CONST, 18)
            .op(CONST, 0x55667788)
            .op(STORE2)
            .op(CONST, 20)
            .op(CONST, -2)
            .op(STORE4)
            .op(CONST, 16)
            .op(LOAD4)
            .op(LEAVE, 8);
    var vm = traced(stores, QvmAssembler.NO_SYSCALLS);
    assertEquals(0x77880044, vm.vmMain(0));
    assertEquals(-2, vm.memory().readInt(20));
    int size = vm.memory().size();
    // The start address is masked into the segment (here: size + 16 -> 16) ...
    assertEquals(
        0x44,
        run(
            new QvmAssembler()
                .op(ENTER, 8)
                .op(CONST, 16)
                .op(CONST, 0x44)
                .op(STORE1)
                .op(CONST, size + 16)
                .op(LOAD1)
                .op(LEAVE, 8)));
    // ... and a byte access anywhere is in range, but wider accesses must not straddle the end.
    assertEquals(0, run(new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(LOAD1).op(LEAVE, 8)));
    assertEquals(MEMORY_BOUNDS, faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(LOAD2)));
    assertEquals(MEMORY_BOUNDS, faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, -2).op(LOAD4)));
    assertEquals(
        MEMORY_BOUNDS,
        faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, -1).op(CONST, 0).op(STORE2)));
    assertEquals(
        MEMORY_BOUNDS,
        faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, -3).op(CONST, 0).op(STORE4)));
    // Unaligned word access is allowed, as on x86.
    assertEquals(
        0x0080f0ff,
        run(new QvmAssembler().data(0x80f0ff7f).op(ENTER, 8).op(CONST, 1).op(LOAD4).op(LEAVE, 8)));
  }

  @Test
  void blockCopyIsBoundsCheckedAndOverlapSafe() {
    var overlap =
        new QvmAssembler()
            .data(0x04030201, 0x08070605, 0)
            .op(ENTER, 8)
            .op(CONST, 1) // destination
            .op(CONST, 0) // source
            .op(BLOCK_COPY, 7)
            .op(CONST, 4)
            .op(LOAD4)
            .op(LEAVE, 8);
    assertEquals(0x07060504, run(overlap));
    var empty =
        new QvmAssembler()
            .op(ENTER, 8)
            .op(CONST, -1)
            .op(CONST, -1)
            .op(BLOCK_COPY, 0)
            .op(CONST, 9)
            .op(LEAVE, 8);
    assertEquals(9, run(empty));
    assertEquals(
        MEMORY_BOUNDS,
        faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, -4).op(CONST, 0).op(BLOCK_COPY, 8)));
    assertEquals(
        MEMORY_BOUNDS,
        faultOf(new QvmAssembler().op(ENTER, 8).op(CONST, 0).op(CONST, -4).op(BLOCK_COPY, 8)));
  }

  @Test
  void signExtensionUsesTheLowBitsOnly() {
    assertEquals(127, unary(SEX8, 0x7f));
    assertEquals(-1, unary(SEX8, 0xff));
    assertEquals(-128, unary(SEX8, 0x180));
    assertEquals(0x78, unary(SEX8, 0x12345678));
    assertEquals(0x5678, unary(SEX16, 0x12345678));
    assertEquals(-32768, unary(SEX16, 0x8000));
    assertEquals(-1, unary(SEX16, 0xffff));
  }

  @Test
  void integerArithmeticWrapsLikeTwosComplement() {
    assertEquals(-42, unary(NEGI, 42));
    assertEquals(MIN, unary(NEGI, MIN));
    assertEquals(MIN, binary(ADD, Integer.MAX_VALUE, 1));
    assertEquals(Integer.MAX_VALUE, binary(SUB, MIN, 1));
    assertEquals(-6, binary(MULI, -2, 3));
    assertEquals(-2, binary(MULU, -1, 2));
    assertEquals(0, binary(MULI, 0x10000, 0x10000));
    assertEquals(-3, binary(DIVI, -7, 2));
    assertEquals(-1, binary(MODI, -7, 2));
    assertEquals(1, binary(MODI, 7, -2));
    assertEquals(MIN, binary(DIVI, MIN, -1));
    assertEquals(0, binary(MODI, MIN, -1));
    assertEquals(0x7fffffff, binary(DIVU, -1, 2));
    assertEquals(1, binary(MODU, -1, 2));
    assertEquals(2, binary(DIVU, MIN, 0x40000000));
  }

  @Test
  void integerDivisionByZeroFaultsForEverySignedness() {
    for (Opcode opcode : new Opcode[] {DIVI, DIVU, MODI, MODU}) {
      var code = new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(CONST, 0).op(opcode);
      var vm = traced(code, QvmAssembler.NO_SYSCALLS);
      var failure = assertThrows(QvmException.class, () -> vm.vmMain(0));
      assertEquals(ARITHMETIC, failure.reason());
      assertEquals(opcode, failure.opcode());
      assertEquals(3, failure.pc());
    }
  }

  @Test
  void bitwiseOperators() {
    assertEquals(0x24, binary(BAND, 0x34, 0x27));
    assertEquals(0x37, binary(BOR, 0x34, 0x27));
    assertEquals(0x13, binary(BXOR, 0x34, 0x27));
    assertEquals(0xfffffffe, unary(BCOM, 1));
    assertEquals(-1, unary(BCOM, 0));
  }

  @Test
  void shiftsUseTheLowFiveBitsOfTheCount() {
    assertEquals(1, binary(LSH, 1, 0));
    assertEquals(MIN, binary(LSH, 1, 31));
    assertEquals(1, binary(LSH, 1, 32));
    assertEquals(2, binary(LSH, 1, 33));
    assertEquals(MIN, binary(LSH, 1, -1));
    assertEquals(-4, binary(RSHI, -8, 1));
    assertEquals(-1, binary(RSHI, MIN, 31));
    assertEquals(-8, binary(RSHI, -8, 32));
    assertEquals(0x7ffffffc, binary(RSHU, -8, 1));
    assertEquals(1, binary(RSHU, MIN, 31));
    assertEquals(-8, binary(RSHU, -8, 32));
    assertEquals(0x3ffffffe, binary(RSHU, -8, 34));
  }

  @Test
  void floatArithmeticIsSinglePrecisionIeee() {
    assertEquals(5f, binaryF(ADDF, 2f, 3f));
    assertEquals(-1f, binaryF(SUBF, 2f, 3f));
    assertEquals(6f, binaryF(MULF, 2f, 3f));
    assertEquals(1.5f, binaryF(DIVF, 3f, 2f));
    assertEquals(Float.POSITIVE_INFINITY, binaryF(DIVF, 2f, 0f));
    assertEquals(Float.NEGATIVE_INFINITY, binaryF(DIVF, -2f, 0f));
    assertTrue(Float.isNaN(binaryF(DIVF, 0f, 0f)));
    assertTrue(Float.isNaN(binaryF(SUBF, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)));
    assertEquals(16777216f, binaryF(ADDF, 16777216f, 1f), "rounds to float, not double");
    assertEquals(Float.MIN_VALUE, binaryF(MULF, Float.MIN_VALUE, 1f));
    assertEquals(0x80000000, unary(NEGF, 0));
    assertEquals(Float.floatToIntBits(-2.5f), unary(NEGF, Float.floatToIntBits(2.5f)));
    assertEquals(NAN ^ 0x80000000, unary(NEGF, NAN), "NEGF flips the sign bit only");
  }

  @Test
  void conversionsTruncateTowardZeroAndSaturateLikeX86() {
    assertEquals(-3f, Float.intBitsToFloat(unary(CVIF, -3)));
    assertEquals(16777216f, Float.intBitsToFloat(unary(CVIF, 16777217)));
    assertEquals(-0x1.0p31f, Float.intBitsToFloat(unary(CVIF, MIN)));
    assertEquals(-3, unary(CVFI, Float.floatToIntBits(-3.9f)));
    assertEquals(3, unary(CVFI, Float.floatToIntBits(3.9f)));
    assertEquals(0, unary(CVFI, Float.floatToIntBits(-0.5f)));
    assertEquals(MIN, unary(CVFI, Float.floatToIntBits(-0x1.0p31f)));
    assertEquals(0x7fffff80, unary(CVFI, Float.floatToIntBits(0x1.fffffep30f)));
    assertEquals(MIN, unary(CVFI, Float.floatToIntBits(0x1.0p31f)));
    assertEquals(MIN, unary(CVFI, Float.floatToIntBits(-0x1.000002p31f)));
    assertEquals(MIN, unary(CVFI, NAN));
    assertEquals(MIN, unary(CVFI, Float.floatToIntBits(Float.POSITIVE_INFINITY)));
    assertEquals(MIN, unary(CVFI, Float.floatToIntBits(Float.NEGATIVE_INFINITY)));
  }
}
