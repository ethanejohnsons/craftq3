package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.botlib.aas.AasRouteTimes;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.engine.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.AasReader;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
 * AAS route queries per second on a real map's {@code .aas} (needs {@code CRAFTQ3_Q3ROOT}): travel
 * time and first reachability between seeded pairs of routable areas. {@code warmCache} is the
 * steady state of a running game (the area and portal caches already hold the goals); {@code
 * coldCache} clears the caches before every batch, so each query builds its goal's cache.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class RealBotlibBenchmark {
  private static final int QUERIES = 256;
  private static final TravelPolicy POLICY = TravelPolicy.ofFlags(TravelFlags.DEFAULT);

  @Param({"q3dm17"})
  public String map;

  private AasWorld world;
  private AasRouteTimes routes;
  private final int[] startAreas = new int[QUERIES];
  private final int[] goalAreas = new int[QUERIES];
  private final Vec3f[] origins = new Vec3f[QUERIES];

  @Setup
  public void setUp() throws IOException {
    try (SearchPath search = SearchPath.mount(RealDataBench.root(), SearchPath.BASE_GAME)) {
      String path = "maps/" + map + ".aas";
      world = AasWorld.of(AasReader.read(search.view().read(VirtualPath.of(path)), path));
    }
    routes = new AasRouteTimes(world);
    List<Integer> areas = new ArrayList<>();
    for (int area = 1; area < world.areaSettings().size(); area++)
      if (world.areaSettings().get(area).reachabilityCount() > 0) areas.add(area);
    Random random = new Random(7);
    for (int i = 0; i < QUERIES; i++) {
      startAreas[i] = areas.get(random.nextInt(areas.size()));
      goalAreas[i] = areas.get(random.nextInt(areas.size()));
      origins[i] = world.areas().get(startAreas[i]).center();
    }
    int found = 0;
    for (int i = 0; i < QUERIES; i++)
      if (routes.route(startAreas[i], origins[i], goalAreas[i], POLICY).travelTime() > 0) found++;
    System.out.println(
        "AAS routes: "
            + areas.size()
            + " routable areas, "
            + found
            + "/"
            + QUERIES
            + " pairs reachable, "
            + routes.cacheStats());
  }

  @Benchmark
  @OperationsPerInvocation(QUERIES)
  public void warmCache(Blackhole sink) {
    for (int i = 0; i < QUERIES; i++)
      sink.consume(routes.route(startAreas[i], origins[i], goalAreas[i], POLICY));
  }

  @Benchmark
  @OperationsPerInvocation(QUERIES)
  public void coldCache(Blackhole sink) {
    // Clearing is a map clear: negligible next to 256 cache builds.
    routes.clearCaches();
    for (int i = 0; i < QUERIES; i++)
      sink.consume(routes.route(startAreas[i], origins[i], goalAreas[i], POLICY));
  }
}
