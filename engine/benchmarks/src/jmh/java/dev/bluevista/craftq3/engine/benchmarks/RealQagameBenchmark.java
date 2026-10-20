package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vmabi.VmExports.Qagame;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The original baseq3 qagame (pak8, 1.32) running its frame exports directly, as {@code SV_Frame}
 * calls them: {@code BOTAI_START_FRAME} then {@code GAME_RUN_FRAME}, with 8 bots on q3dm17 (needs
 * {@code CRAFTQ3_Q3ROOT}). One operation is one server frame of qagame work, syscalls included; the
 * {@code instructions} and {@code syscalls} counters report QVM rates.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = "-Xmx2g")
@State(Scope.Thread)
public class RealQagameBenchmark {
  /** The execution engine: the interpreter ({@code vm_jit 0}) or compiled code ({@code 2}). */
  @Param({"interpreter", "compiled"})
  public String engine;

  private RealServer server;
  private QvmInstance vm;
  private int time;
  private int step;

  /** QVM work retired, reported by JMH as rates. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Retired {
    public long instructions;
    public long syscalls;

    @Setup(Level.Iteration)
    public void clear() {
      instructions = 0;
      syscalls = 0;
    }
  }

  @Setup
  public void setUp() throws IOException {
    String jit =
        switch (engine) {
          case "interpreter" -> "0";
          case "compiled" -> "2";
          default -> throw new IllegalArgumentException("Unknown VM engine " + engine);
        };
    server = new RealServer("q3dm17", 8, jit);
    vm = server.qagame();
    if ((vm.executionMode() == QvmInstance.ExecutionMode.COMPILE) != engine.equals("compiled"))
      throw new IllegalStateException("qagame runs in " + vm.executionMode());
    time = server.levelTime();
    step = 1000 / Math.max(1, server.server.cvars().integer("sv_fps"));
  }

  @TearDown
  public void tearDown() throws IOException {
    server.close();
  }

  @Benchmark
  public int frame(Retired retired) {
    QvmInstance.Stats before = vm.stats();
    vm.vmMain(Qagame.BOTAI_START_FRAME.command(), time);
    time += step;
    server.clock.advance(step);
    int result = vm.vmMain(Qagame.GAME_RUN_FRAME.command(), time);
    QvmInstance.Stats after = vm.stats();
    retired.instructions += after.instructions() - before.instructions();
    retired.syscalls += after.syscalls() - before.syscalls();
    return result;
  }
}
