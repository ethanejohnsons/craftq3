package dev.bluevista.craftq3.engine.vm;

import static dev.bluevista.craftq3.engine.vm.Opcode.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.time.Clock;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A non-JMH micro benchmark: a synthetic hot loop shaped like lcc output (locals, loads/stores,
 * integer arithmetic, a procedure call or a syscall per iteration). Prints instructions per second.
 * Run with {@code ./gradlew :engine:vm:benchmark}; excluded from {@code test}.
 */
@Tag("benchmark")
final class QvmInterpreterBenchmark {
  private static final int ITERATIONS = 1_000_000;
  private static final int WARMUP_ROUNDS = 15;
  private static final int MEASURED_ROUNDS = 10;

  /**
   * vmMain(n): {@code sum = 0; for (i = 0; i < n; i++) sum = (sum + 3 * i) ^ f(i); return sum;}
   * where {@code f(x) = (x >> 1) + x} is a procedure, or syscall 0 when {@code syscall} is set.
   */
  static QvmAssembler hotLoop(boolean syscall) {
    var code =
        new QvmAssembler()
            .op(ENTER, 32)
            .op(LOCAL, 16)
            .op(CONST, 0)
            .op(STORE4)
            .op(LOCAL, 20)
            .op(CONST, 0)
            .op(STORE4)
            .op(LOCAL, 16) // 7: loop head
            .op(LOAD4)
            .op(LOCAL, 40)
            .op(LOAD4)
            .op(GEI, 35)
            .op(LOCAL, 20)
            .op(LOCAL, 20)
            .op(LOAD4)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 3)
            .op(MULI)
            .op(ADD)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(ARG, 8)
            .op(CONST, syscall ? -1 : 38)
            .op(CALL)
            .op(BXOR)
            .op(STORE4)
            .op(LOCAL, 16)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 1)
            .op(ADD)
            .op(STORE4)
            .op(CONST, 7)
            .op(JUMP)
            .op(LOCAL, 20) // 35: exit
            .op(LOAD4)
            .op(LEAVE, 32)
            .op(ENTER, 16) // 38: f
            .op(LOCAL, 24)
            .op(LOAD4)
            .op(CONST, 1)
            .op(RSHI)
            .op(LOCAL, 24)
            .op(LOAD4)
            .op(ADD)
            .op(LEAVE, 16);
    assertEquals(47, code.size());
    return code;
  }

  static int expected(int n) {
    int sum = 0;
    for (int i = 0; i < n; i++) sum = (sum + 3 * i) ^ ((i >> 1) + i);
    return sum;
  }

  @Test
  void procedureCallLoop() {
    measure("procedure calls", hotLoop(false), null);
  }

  @Test
  void syscallLoop() {
    measure("syscalls", hotLoop(true), (vm, number, args) -> (args[0] >> 1) + args[0]);
  }

  @Test
  void procedureCallLoopWithTracer() {
    measure("procedure calls, tracer", hotLoop(false), null, (pc, op, imm, depth, stack) -> {});
  }

  private static void measure(String label, QvmAssembler code, Syscalls syscalls) {
    measure(label, code, syscalls, null);
  }

  private static void measure(String label, QvmAssembler code, Syscalls syscalls, VmTracer tracer) {
    var limits = new QvmInstance.Limits(Long.MAX_VALUE / 8, Long.MAX_VALUE / 8, 0, 4096, 1024, 16);
    var vm =
        new QvmInstance(
            code.image(),
            syscalls == null ? QvmAssembler.NO_SYSCALLS : syscalls,
            limits,
            Clock.system());
    vm.setTracer(tracer);
    int expected = expected(ITERATIONS);
    double[] rates = new double[MEASURED_ROUNDS];
    for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
      long before = vm.stats().instructions();
      long start = System.nanoTime();
      assertEquals(expected, vm.vmMain(ITERATIONS));
      long nanos = System.nanoTime() - start;
      long executed = vm.stats().instructions() - before;
      if (round >= WARMUP_ROUNDS) rates[round - WARMUP_ROUNDS] = executed * 1e9 / nanos;
    }
    Arrays.sort(rates);
    System.out.printf(
        "QVM benchmark [%s]: median %.1f M instr/s, best %.1f M instr/s%n",
        label, rates[MEASURED_ROUNDS / 2] / 1e6, rates[MEASURED_ROUNDS - 1] / 1e6);
  }
}
