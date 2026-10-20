package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Collision traces per second on real baseq3 maps (needs {@code CRAFTQ3_Q3ROOT}): q3dm17's open
 * space and jump pads, and q3dm7's curved, detailed architecture. One operation is one trace.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class RealCollisionBenchmark {
  @Param({"q3dm17", "q3dm7"})
  public String map;

  private CollisionModel model;
  private TraceSet set;
  private final TraceWork work = new TraceWork();
  private final Trace trace = new Trace();

  @Setup
  public void setUp() throws IOException {
    BspMap bsp;
    try (SearchPath search = SearchPath.mount(RealDataBench.root(), SearchPath.BASE_GAME)) {
      String path = "maps/" + map + ".bsp";
      bsp = BspReader.read(search.view().read(VirtualPath.of(path)), path);
    }
    model = CollisionModel.build(bsp);
    float[] mins = new float[3], maxs = new float[3];
    model.modelBounds(CollisionModel.WORLD_MODEL, mins, maxs);
    set = new TraceSet(model, mins, maxs, map.hashCode());
  }

  @Benchmark
  @OperationsPerInvocation(TraceSet.SIZE)
  public void pointTrace(Blackhole sink) {
    CollisionBenchmark.traces(model, set, work, trace, null, null, false, sink);
  }

  @Benchmark
  @OperationsPerInvocation(TraceSet.SIZE)
  public void hullTrace(Blackhole sink) {
    CollisionBenchmark.traces(
        model, set, work, trace, TraceSet.HULL_MINS, TraceSet.HULL_MAXS, false, sink);
  }
}
