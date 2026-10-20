package dev.bluevista.craftq3.engine.vm;

import static dev.bluevista.craftq3.engine.vm.Opcode.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.vm.QvmInstance.ExecutionMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Differential checks of compiled against interpreted execution: results, faults (reason,
 * instruction, opcode, syscall, message), statistics, syscall arguments and the whole data segment
 * must agree, for hand-written edge cases and randomly generated programs.
 */
final class QvmCompilerTest {
  private static final Syscalls ZERO = (vm, number, args) -> 0;

  /** Everything observable about one run. */
  private record Outcome(
      List<String> events, QvmInstance.Stats stats, int memoryHash, long deoptimizations) {}

  private static Outcome run(
      QvmImage image,
      ExecutionMode mode,
      QvmInstance.Limits limits,
      Function<List<String>, Syscalls> syscalls,
      int[]... calls) {
    List<String> events = new ArrayList<>();
    var vm = new QvmInstance(image, syscalls.apply(events), limits, Clock.system(), mode);
    assertEquals(mode == ExecutionMode.INTERPRET, vm.executionMode() == ExecutionMode.INTERPRET);
    for (int[] call : calls) {
      try {
        events.add("= " + vm.vmMain(call[0], Arrays.copyOfRange(call, 1, call.length)));
      } catch (QvmException fault) {
        events.add(
            "! "
                + fault.reason()
                + " pc="
                + fault.pc()
                + " op="
                + fault.opcode()
                + " sys="
                + fault.syscall()
                + " "
                + fault.getMessage());
        vm.reset();
      }
    }
    return new Outcome(
        events,
        vm.stats(),
        Arrays.hashCode(vm.memory().readBytes(0, vm.memory().size())),
        vm.deoptimizations());
  }

  private static Function<List<String>, Syscalls> recording(Syscalls inner) {
    return events ->
        (vm, number, args) -> {
          events.add("sys " + number + " " + Arrays.toString(Arrays.copyOf(args, 4)));
          return inner.call(vm, number, args);
        };
  }

  /** Runs in both modes and requires identical outcomes; returns the compiled one. */
  private static Outcome same(
      QvmImage image,
      QvmInstance.Limits limits,
      Function<List<String>, Syscalls> syscalls,
      int[]... calls) {
    Outcome interpreted = run(image, ExecutionMode.INTERPRET, limits, syscalls, calls);
    Outcome compiled = run(image, ExecutionMode.COMPILE, limits, syscalls, calls);
    assertEquals(interpreted.events(), compiled.events());
    assertEquals(interpreted.stats(), compiled.stats());
    assertEquals(interpreted.memoryHash(), compiled.memoryHash());
    return compiled;
  }

  private static Outcome same(QvmImage image, int[]... calls) {
    return same(image, QvmInstance.Limits.defaults(), recording(ZERO), calls);
  }

  private static QvmInstance.Limits budget(long instructions) {
    return new QvmInstance.Limits(instructions, 1000, 0, 4096, 64, 4);
  }

  private static QvmCompileReport report(QvmImage image) {
    return new QvmInstance(
            image, ZERO, QvmInstance.Limits.defaults(), Clock.system(), ExecutionMode.COMPILE)
        .compileReport()
        .orElseThrow();
  }

  @Test
  void hotLoopsAgreeIncludingEveryBudgetExhaustionPoint() {
    for (boolean syscall : new boolean[] {false, true}) {
      QvmImage image = QvmInterpreterBenchmark.hotLoop(syscall).image();
      Syscalls f = (vm, number, args) -> (args[0] >> 1) + args[0];
      var outcome = same(image, QvmInstance.Limits.defaults(), recording(f), new int[] {50});
      assertEquals("= " + QvmInterpreterBenchmark.expected(50), outcome.events().getLast());
      assertEquals(0, report(image).interpreted());
      // Every budget from 1 to past the end faults (or finishes) at the same instruction.
      for (long limit = 1; limit < 400; limit++)
        same(image, budget(limit), recording(f), new int[] {6});
    }
  }

  @Test
  void versionOneSwitchesUseDataTablesAndDeoptimizeOnUnlistedTargets() {
    // vmMain(0, i): jump through the table at data offset 0 (entry i); each case returns a value.
    var code =
        new QvmAssembler()
            .data(7, 9, 11)
            .op(ENTER, 8) // 0
            .op(LOCAL, 20) // 1
            .op(LOAD4) // 2
            .op(CONST, 4) // 3
            .op(MULI) // 4
            .op(LOAD4) // 5: table[i]
            .op(JUMP) // 6
            .op(CONST, 1) // 7
            .op(LEAVE, 8) // 8
            .op(CONST, 2) // 9
            .op(LEAVE, 8) // 10
            .op(CONST, 3) // 11
            .op(CONST, 4) // 12
            .op(LEAVE, 8); // 13 (from 11: two operands: fault; from 12: returns 4)
    var outcome =
        same(
            code.image(),
            new int[] {0, 0},
            new int[] {0, 1},
            new int[] {0, 2},
            new int[] {0, 12}, // table[3] reads 0 (BSS): jump to the entry faults
            new int[] {0, 0});
    assertTrue(outcome.events().get(2).startsWith("! STACK"), outcome.events()::toString);
    assertTrue(outcome.events().get(3).startsWith("! CONTROL_FLOW"), outcome.events()::toString);
    // vmMain(0, target) jumps to its argument; only instruction 4 appears in the data.
    var direct =
        new QvmAssembler()
            .data(4)
            .op(ENTER, 8) // 0
            .op(LOCAL, 20) // 1
            .op(LOAD4) // 2
            .op(JUMP) // 3
            .op(CONST, 1) // 4
            .op(LEAVE, 8) // 5
            .op(CONST, 2) // 6
            .op(LEAVE, 8); // 7
    var deopt =
        same(
            direct.image(), new int[] {0, 4}, new int[] {0, 6}, new int[] {0, 5}, new int[] {0, 9});
    assertEquals("= 1", deopt.events().get(0));
    assertEquals("= 2", deopt.events().get(1));
    assertTrue(deopt.events().get(2).startsWith("! STACK"), deopt.events()::toString);
    assertTrue(deopt.events().get(3).startsWith("! CONTROL_FLOW"), deopt.events()::toString);
    var once =
        run(
            direct.image(),
            ExecutionMode.COMPILE,
            QvmInstance.Limits.defaults(),
            recording(ZERO),
            new int[] {0, 4},
            new int[] {0, 6});
    assertEquals(1, once.deoptimizations());
  }

  @Test
  void versionTwoSwitchesUseTheDeclaredTable() {
    var code =
        new QvmAssembler()
            .data(5, 7, 4)
            .op(ENTER, 8) // 0
            .op(LOCAL, 20) // 1
            .op(LOAD4) // 2
            .op(LOAD4) // 3
            .op(JUMP) // 4
            .op(CONST, 1) // 5
            .op(LEAVE, 8) // 6
            .op(CONST, 2) // 7
            .op(LEAVE, 8); // 8
    var image = code.v2(5, 7).image();
    var outcome = same(image, new int[] {0, 0}, new int[] {0, 4}, new int[] {0, 8});
    assertEquals("= 1", outcome.events().get(0));
    assertEquals("= 2", outcome.events().get(1));
    assertTrue(
        outcome.events().get(2).contains("not in v2 jump table"), outcome.events()::toString);
  }

  @Test
  void computedCallsSyscallsAndReentryAgree() {
    // vmMain(0, f): calls the function pointer f with 5; f = 7 doubles, f < 0 is a syscall.
    var code =
        new QvmAssembler()
            .op(ENTER, 16) // 0
            .op(CONST, 5) // 1
            .op(ARG, 8) // 2
            .op(LOCAL, 28) // 3
            .op(LOAD4) // 4
            .op(CALL) // 5
            .op(LEAVE, 16) // 6
            .op(ENTER, 8) // 7
            .op(LOCAL, 16) // 8
            .op(LOAD4) // 9
            .op(CONST, 2) // 10
            .op(MULI) // 11
            .op(LEAVE, 8); // 12
    Syscalls reenter =
        (vm, number, args) -> number == 3 && args[0] == 5 ? vm.vmMain(0, 7) + 1 : number;
    var outcome =
        same(
            code.image(),
            QvmInstance.Limits.defaults(),
            recording(reenter),
            new int[] {0, 7},
            new int[] {0, -4},
            new int[] {0, -1},
            new int[] {0, 8},
            new int[] {0, 99});
    assertEquals("= 10", outcome.events().get(0));
    assertEquals("= 11", outcome.events().get(2));
    assertTrue(outcome.events().get(5).contains("CALL target 8"), outcome.events()::toString);
  }

  @Test
  void interpretedAndCompiledProceduresCallEachOther() {
    // root (compiled) calls p (operand depth not static: interpreted), which calls q (compiled).
    var fixed =
        new QvmAssembler()
            .op(ENTER, 16)
            .op(LOCAL, 28)
            .op(LOAD4)
            .op(ARG, 8)
            .op(CONST, 9)
            .op(CALL)
            .op(CONST, 1000)
            .op(ADD)
            .op(LEAVE, 16) // 8
            .op(ENTER, 16) // 9: p
            .op(LOCAL, 24) // 10
            .op(LOAD4) // 11
            .op(CONST, 0) // 12
            .op(EQ, 15) // 13
            .op(CONST, 7) // 14
            .op(CONST, 3) // 15
            .op(ARG, 8) // 16
            .op(CONST, 20) // 17
            .op(CALL) // 18
            .op(LEAVE, 16) // 19
            .op(ENTER, 8) // 20: q
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(MULI)
            .op(LEAVE, 8);

    var report = report(fixed.image());
    assertEquals(2, report.compiled(), report::toString);
    assertEquals(1, report.fallbacks().get("operand depth is not static"), report::toString);
    var outcome = same(fixed.image(), new int[] {0, 0}, new int[] {0, 1}, new int[] {0, 0});
    assertEquals("= 1009", outcome.events().get(0));
    assertTrue(outcome.events().get(1).startsWith("! STACK"), outcome.events()::toString);
  }

  @Test
  void operandStackOverflowFaultsAtTheSameInstruction() {
    // Each activation keeps two operands across its recursive call.
    var code =
        new QvmAssembler()
            .op(ENTER, 8)
            .op(CONST, 1)
            .op(CONST, 2)
            .op(CONST, 0)
            .op(CALL)
            .op(ADD)
            .op(ADD)
            .op(LEAVE, 8);
    var limits = new QvmInstance.Limits(100_000, 10, 0, 16, 1000, 4);
    var outcome = same(code.image(), limits, recording(ZERO), new int[] {0});
    assertTrue(outcome.events().getFirst().contains("overflow"), outcome.events()::toString);
  }

  @Test
  void localRangeChecksMemoryBoundsAndStaticFaultsAgree() {
    same(new QvmAssembler().op(ENTER, 8).op(LOCAL, 1 << 20).op(LEAVE, 8).image(), new int[] {0});
    same(
        new QvmAssembler().op(ENTER, 8).op(LOCAL, 200).op(LOAD4).op(LEAVE, 8).image(),
        new int[] {0});
    same(
        new QvmAssembler().op(ENTER, 8).op(CONST, -3).op(LOAD4).op(LEAVE, 8).image(),
        new int[] {0});
    same(
        new QvmAssembler().op(ENTER, 8).op(CONST, -2).op(CONST, 1).op(STORE2).image(),
        new int[] {0});
    same(new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(CONST, 0).op(DIVU).image(), new int[] {0});
    same(new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(ARG, 8).image(), new int[] {0});
    same(
        new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(CONST, 2).op(LEAVE, 8).image(),
        new int[] {0});
    same(new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(LEAVE, 12).image(), new int[] {0});
    same(new QvmAssembler().op(ENTER, 8).op(CONST, 1).image(), new int[] {0});
    same(
        new QvmAssembler().op(ENTER, 8).op(CONST, 1).op(ENTER, 8).op(CONST, 2).op(LEAVE, 8).image(),
        new int[] {0});
    same(new QvmAssembler().op(ENTER, 8).op(CONST, 3).op(CALL).op(LEAVE, 8).image(), new int[] {0});
    same(
        new QvmAssembler()
            .op(ENTER, 8)
            .op(LOCAL, 8)
            .op(CONST, 1)
            .op(STORE4)
            .op(CONST, 0)
            .op(LEAVE, 8)
            .image(),
        new int[] {0});
  }

  @Test
  void hugeProceduresStayInTheInterpreter() {
    var code = new QvmAssembler().bss(1 << 16).op(ENTER, 8).op(CONST, 0);
    for (int i = 0; i < 4000; i++) code.op(CONST, 1000 + i * 4).op(LOAD4).op(ADD);
    code.op(LEAVE, 8);
    var report = report(code.image());
    assertEquals(0, report.compiled(), report::toString);
    assertEquals(
        1, report.fallbacks().get("method over " + QvmCompiler.HUGE_METHOD_BYTES + " bytes"));
    same(code.image(), new int[] {0});
  }

  /** Random lcc-shaped procedures: statements over locals and globals with forward branches. */
  @Test
  void randomProgramsAgree() {
    var random = new Random(0x51564d43);
    Opcode[] binary = {
      ADD, SUB, MULI, MULU, BAND, BOR, BXOR, LSH, RSHI, RSHU, DIVI, DIVU, MODI, MODU, ADDF, SUBF,
      MULF, DIVF
    };
    Opcode[] unary = {SEX8, SEX16, NEGI, BCOM, NEGF, CVIF, CVFI};
    Opcode[] branches = {
      EQ, NE, LTI, LEI, GTI, GEI, LTU, LEU, GTU, GEU, EQF, NEF, LTF, LEF, GTF, GEF
    };
    int[] interesting = {
      0,
      1,
      -1,
      2,
      3,
      31,
      32,
      33,
      255,
      256,
      0x7fff,
      0x8000,
      Integer.MIN_VALUE,
      Integer.MAX_VALUE,
      Float.floatToRawIntBits(1.5f),
      Float.floatToRawIntBits(-2f),
      Float.floatToRawIntBits(Float.NaN),
      Float.floatToRawIntBits(3e9f),
      0x7fc00001
    };
    int compiledPrograms = 0;
    for (int program = 0; program < PROGRAMS; program++) {
      QvmAssembler code = null;
      int statements = 4 + random.nextInt(12);
      // Statement i starts at starts[i]; branches go forward to a later statement start, which
      // is patched after layout by re-assembling with the same random choices.
      long seed = random.nextLong();
      var sketch = new ArrayList<int[]>();
      for (int pass = 0; pass < 2; pass++) {
        var rng = new Random(seed);
        code = new QvmAssembler().bss(8192).stack(2048).data(new Random(seed).ints(16).toArray());
        code.op(ENTER, 64);
        List<Integer> starts = new ArrayList<>();
        List<int[]> patches = new ArrayList<>();
        for (int s = 0; s < statements; s++) {
          starts.add(code.size());
          int shape = rng.nextInt(4);
          if (shape == 0) {
            code.op(LOCAL, 8 + 4 * rng.nextInt(12));
            expression(code, rng, binary, unary, interesting, 3);
            code.op(rng.nextBoolean() ? STORE4 : rng.nextBoolean() ? STORE2 : STORE1);
          } else if (shape == 1) {
            code.op(CONST, 4 * rng.nextInt(80) + (rng.nextInt(8) == 0 ? rng.nextInt(4) : 0));
            expression(code, rng, binary, unary, interesting, 2);
            code.op(STORE4);
          } else if (shape == 2) {
            expression(code, rng, binary, unary, interesting, 2);
            expression(code, rng, binary, unary, interesting, 2);
            int target = s + 1 + rng.nextInt(statements - s);
            patches.add(new int[] {code.size(), target});
            Opcode op = branches[rng.nextInt(branches.length)];
            int fixed = pass == 1 ? sketch.get(patches.size() - 1)[0] : 1;
            code.op(op, fixed);
          } else {
            code.op(CONST, 4 * rng.nextInt(40)).op(CONST, 4 * rng.nextInt(40));
            code.op(BLOCK_COPY, 4 * rng.nextInt(8));
          }
        }
        starts.add(code.size());
        code.op(LOCAL, 8 + 4 * rng.nextInt(12)).op(LOAD4).op(LEAVE, 64);
        if (pass == 0) {
          sketch.clear();
          for (int[] patch : patches) sketch.add(new int[] {starts.get(patch[1])});
        }
      }
      QvmImage image = code.image();
      if (report(image).interpreted() == 0) compiledPrograms++;
      same(image, QvmInstance.Limits.defaults(), recording(ZERO), new int[] {0}, new int[] {1});
      for (long limit : new long[] {5, 17, 40})
        same(image, budget(limit), recording(ZERO), new int[] {0});
    }
    assertEquals(PROGRAMS, compiledPrograms, "programs run compiled");
  }

  private static final int PROGRAMS = 300;

  private static void expression(
      QvmAssembler code,
      Random rng,
      Opcode[] binary,
      Opcode[] unary,
      int[] interesting,
      int depth) {
    int choice = depth == 0 ? rng.nextInt(3) : rng.nextInt(6);
    switch (choice) {
      case 0 ->
          code.op(
              CONST,
              rng.nextBoolean() ? interesting[rng.nextInt(interesting.length)] : rng.nextInt());
      case 1 -> code.op(LOCAL, 8 + 4 * rng.nextInt(12)).op(LOAD4);
      case 2 -> {
        int width = rng.nextInt(3);
        code.op(CONST, rng.nextInt(400) - (rng.nextInt(16) == 0 ? 600 : 0));
        code.op(width == 0 ? LOAD1 : width == 1 ? LOAD2 : LOAD4);
      }
      case 3 -> {
        expression(code, rng, binary, unary, interesting, depth - 1);
        code.op(unary[rng.nextInt(unary.length)]);
      }
      default -> {
        expression(code, rng, binary, unary, interesting, depth - 1);
        expression(code, rng, binary, unary, interesting, depth - 1);
        code.op(binary[rng.nextInt(binary.length)]);
      }
    }
  }
}
