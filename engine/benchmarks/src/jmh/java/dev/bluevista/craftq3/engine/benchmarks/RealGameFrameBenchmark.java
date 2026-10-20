package dev.bluevista.craftq3.engine.benchmarks;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * Dedicated-server frame time with the original baseq3 qagame and 8 bots on q3dm17 (needs {@code
 * CRAFTQ3_Q3ROOT}). One operation is one complete {@code SV_Frame} at {@code sv_fps 20}: console,
 * bot AI frame, game frame, timeouts and bot snapshots. JMH's sample mode reports the p50, p90, p99
 * and p99.9 frame times; the plan's budget is p99 under 4 ms.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 4)
@Fork(value = 1, jvmArgsAppend = "-Xmx2g")
@State(Scope.Thread)
public class RealGameFrameBenchmark {
  @Param({"8"})
  public int bots;

  private RealServer server;

  @Setup
  public void setUp() throws IOException {
    server = new RealServer("q3dm17", bots);
  }

  @TearDown
  public void tearDown() throws IOException {
    int active = server.activeBots();
    server.close();
    if (active != bots)
      throw new IllegalStateException("Only " + active + " of " + bots + " bots stayed in");
  }

  @Benchmark
  public int serverFrame() {
    return server.frame();
  }
}
