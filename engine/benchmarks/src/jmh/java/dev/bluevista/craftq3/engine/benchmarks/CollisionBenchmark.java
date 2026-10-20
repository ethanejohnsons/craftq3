package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Collision traces per second on {@link SyntheticArena}: points, player hulls and capsules swept
 * through brushes, ramps and patches. One operation is one trace; run with {@code -prof gc} (the
 * default here) to see the allocation per trace, which should be zero.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CollisionBenchmark {
  private CollisionModel model;
  private TraceSet set;
  private final TraceWork work = new TraceWork();
  private final Trace trace = new Trace();

  @Setup
  public void setUp() {
    model = CollisionModel.build(SyntheticArena.build());
    float h = SyntheticArena.HALF;
    set = new TraceSet(model, new float[] {-h, -h, 8}, new float[] {h, h, 320}, 17);
  }

  @Benchmark
  @OperationsPerInvocation(TraceSet.SIZE)
  public void pointTrace(Blackhole sink) {
    traces(model, set, work, trace, null, null, false, sink);
  }

  @Benchmark
  @OperationsPerInvocation(TraceSet.SIZE)
  public void hullTrace(Blackhole sink) {
    traces(model, set, work, trace, TraceSet.HULL_MINS, TraceSet.HULL_MAXS, false, sink);
  }

  @Benchmark
  @OperationsPerInvocation(TraceSet.SIZE)
  public void capsuleTrace(Blackhole sink) {
    traces(model, set, work, trace, TraceSet.HULL_MINS, TraceSet.HULL_MAXS, true, sink);
  }

  static void traces(
      CollisionModel model,
      TraceSet set,
      TraceWork work,
      Trace trace,
      float[] mins,
      float[] maxs,
      boolean capsule,
      Blackhole sink) {
    for (int i = 0; i < TraceSet.SIZE; i++) {
      model.boxTrace(
          work, trace, set.starts[i], set.ends[i], mins, maxs, 0, TraceSet.MASK, capsule);
      sink.consume(trace.fraction());
    }
  }
}
