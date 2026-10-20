package dev.bluevista.craftq3.engine.vm;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.engine.vm.QvmInstance.ExecutionMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * Compiles every distinct QVM build of the installation and runs a fixed sequence of {@code vmMain}
 * calls against a recording stub host in both execution modes: the syscall streams (numbers,
 * arguments and the memory they point at), results, faults, statistics and the data segment after
 * every call must be identical. Prints each build's compile report; nothing else read is printed.
 */
@RealData
final class RealQvmCompileTest {
  /** Generous enough to run module initialisation against the stub, small enough to stay quick. */
  private static final QvmInstance.Limits LIMITS =
      new QvmInstance.Limits(4_000_000, 200_000, 0, 1 << 16, 4096, 8);

  /** vmMain calls: command and three arguments, covering every export number of the modules. */
  private static final int[][] CALLS = calls();

  private static int[][] calls() {
    List<int[]> calls = new ArrayList<>();
    for (int round = 0; round < 2; round++)
      for (int command = 0; command <= 12; command++)
        calls.add(new int[] {command, 1000 * (round + 1) + command, round, command & 1});
    return calls.toArray(int[][]::new);
  }

  /** Everything one mode observed. */
  private record Trace(List<String> events, long deoptimizations) {}

  @Test
  void everyBuildCompilesAndBehavesLikeTheInterpreter() throws Exception {
    RealQvmBuilds corpus = RealQvmBuilds.scan(RealDataRoot.require());
    assertFalse(corpus.builds.isEmpty(), "no QVMs found");
    Map<String, Integer> fallbackTotals = new TreeMap<>();
    int procedures = 0, compiled = 0, events = 0;
    long deoptimizations = 0, nanos = 0;
    List<String> mismatches = new ArrayList<>();
    for (RealQvmBuilds.Build build : corpus.builds.values()) {
      QvmImage image = QvmImage.parse(build.source(), build.bytes());
      Trace interpreted = trace(image, ExecutionMode.INTERPRET);
      Trace compiledTrace = trace(image, ExecutionMode.COMPILE);
      QvmCompileReport report = image.compiled(true).report();
      System.out.println(report);
      procedures += report.procedures();
      compiled += report.compiled();
      nanos += report.compileNanos();
      report
          .fallbacks()
          .forEach((reason, count) -> fallbackTotals.merge(reason, count, Integer::sum));
      deoptimizations += compiledTrace.deoptimizations();
      events += interpreted.events().size();
      if (!interpreted.events().equals(compiledTrace.events()))
        mismatches.add(build.source() + ": " + firstDifference(interpreted, compiledTrace));
    }
    System.out.printf(
        "QVM compile: %d builds, %d/%d procedures compiled (%d ms), interpreted %s, %d recorded"
            + " events, %d deoptimizations%n",
        corpus.builds.size(),
        compiled,
        procedures,
        nanos / 1_000_000,
        fallbackTotals,
        events,
        deoptimizations);
    assertEquals(List.of(), mismatches);
  }

  private static String firstDifference(Trace a, Trace b) {
    int i = 0;
    while (i < a.events().size()
        && i < b.events().size()
        && a.events().get(i).equals(b.events().get(i))) i++;
    return "event "
        + i
        + ": interpreter "
        + (i < a.events().size() ? a.events().get(i) : "<end>")
        + " / compiled "
        + (i < b.events().size() ? b.events().get(i) : "<end>");
  }

  private static Trace trace(QvmImage image, ExecutionMode mode) {
    List<String> events = new ArrayList<>();
    Syscalls stub =
        (vm, number, args) -> {
          StringBuilder event = new StringBuilder("sys ").append(number);
          for (int arg : args) event.append(' ').append(arg);
          // What the arguments point at: strings, structs and buffers the guest prepared.
          QvmMemory memory = vm.memory();
          for (int i = 0; i < 3; i++) {
            int address = args[i];
            if (address > 0 && address <= memory.size() - 64)
              event.append(" @").append(Arrays.hashCode(memory.readBytes(address, 64)));
          }
          events.add(event.toString());
          return 0;
        };
    var vm = new QvmInstance(image, stub, LIMITS, Clock.system(), mode);
    assertEquals(mode == ExecutionMode.COMPILE, vm.executionMode() == ExecutionMode.COMPILE);
    long deoptimizations = 0;
    for (int[] call : CALLS) {
      String outcome;
      try {
        outcome = "= " + vm.vmMain(call[0], call[1], call[2], call[3]);
      } catch (QvmException fault) {
        outcome =
            "! "
                + fault.reason()
                + " pc="
                + fault.pc()
                + " sys="
                + fault.syscall()
                + " "
                + fault.getMessage();
      }
      CRC32 crc = new CRC32();
      crc.update(vm.memory().readBytes(0, vm.memory().size()));
      deoptimizations += vm.deoptimizations();
      events.add(outcome + " mem=" + Long.toHexString(crc.getValue()) + " " + vm.stats());
      if (vm.faulted()) vm.reset();
    }
    return new Trace(events, deoptimizations);
  }
}
