package dev.bluevista.craftq3.mc.hybrid.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Builds the building queries of retail maps placed as {@link BuildingRegions#place} places them
 * and checks occupancy and support invariants, thread safety and timing. Prints a summary; no map
 * data is written.
 */
@RealData
class RealMapBuildingTest {
  private static final List<String> MAPS =
      List.of("q3dm17", "q3dm1", "q3dm6", "q3dm13", "q3tourney2", "q3ctf1");

  /** Quake units inside or outside a face at which the solid side is sampled. */
  private static final double PROBE = 0.25;

  @Test
  void retailMapsKeepOccupancyAndSupportInvariants() throws Exception {
    StringBuilder report = new StringBuilder();
    List<BuildingRegion> placed = new ArrayList<>();
    try (SearchPath search = SearchPath.mount(RealDataRoot.require(), SearchPath.BASE_GAME)) {
      for (String name : MAPS) {
        String path = "maps/" + name + ".bsp";
        byte[] bytes = search.read(VirtualPath.of(path));
        BuildingRegion region =
            BuildingRegions.place(placed, "baseq3", path, bytes, BuildingRegions.Layout.DEFAULT);
        placed.add(region);
        long start = System.nanoTime();
        BuildingRegions.Enabled enabled =
            assertInstanceOf(
                BuildingRegions.Enabled.class,
                BuildingRegions.load(region, (game, map) -> Optional.of(bytes)));
        long buildMillis = (System.nanoTime() - start) / 1_000_000;
        BuildingQueries q = enabled.queries();
        report.append(check(name, q, buildMillis)).append('\n');
      }
    }
    System.out.print(report);
  }

  private static String check(String name, BuildingQueries q, long buildMillis) throws Exception {
    double s = q.transform().unitsPerBlock();
    double probe = PROBE / s;
    AtomicInteger faces = new AtomicInteger();
    AtomicInteger full = new AtomicInteger();
    AtomicInteger outsideSolid = new AtomicInteger();
    List<String> failures = new ArrayList<>();
    q.supportIndex()
        .forEach(
            (x, y, z, face, bits) -> {
              faces.incrementAndGet();
              boolean isFull = (bits & FaceSupportIndex.FULL_BIT) != 0;
              // FULL covers CENTER's and RIGID's areas.
              if (isFull
                  && ((bits & FaceSupportIndex.CENTER_BIT) == 0
                      || (bits & FaceSupportIndex.RIGID_BIT) == 0))
                failures.add("FULL without CENTER/RIGID at " + x + "," + y + "," + z + " " + face);
              if (!isFull) return;
              full.incrementAndGet();
              // The supported cell lies behind the face: just behind its centre is solid.
              double[] c = faceCentre(x, y, z, face);
              double ix = c[0] - face.stepX() * probe;
              double iy = c[1] - face.stepY() * probe;
              double iz = c[2] - face.stepZ() * probe;
              if ((q.pointContents(ix, iy, iz) & Contents.SOLID) == 0)
                failures.add("supported face over air at " + x + "," + y + "," + z + " " + face);
              if (q.cellClear(x, y, z))
                failures.add("supported cell reported clear at " + x + "," + y + "," + z);
              double ox = c[0] + face.stepX() * probe;
              double oy = c[1] + face.stepY() * probe;
              double oz = c[2] + face.stepZ() * probe;
              if ((q.pointContents(ox, oy, oz) & Contents.SOLID) != 0)
                outsideSolid.incrementAndGet();
            });
    assertTrue(
        failures.isEmpty(), name + ": " + failures.size() + " failures, first " + first(failures));
    assertTrue(full.get() > 500, name + " has only " + full.get() + " supported faces");

    // Occupancy agrees with point contents at random cells.
    Random random = new Random(name.hashCode());
    int samples = 20000;
    int solidCells = 0;
    int[][] cells = new int[samples][];
    double ox = q.transform().originX();
    double oy = q.transform().originY();
    for (int i = 0; i < samples; i++) {
      int x = (int) ox + random.nextInt(120) - 60;
      int y = (int) oy + random.nextInt(40) - 10;
      int z = random.nextInt(120) - 60;
      cells[i] = new int[] {x, y, z};
      boolean centreSolid = (q.pointContents(x + 0.5, y + 0.5, z + 0.5) & Contents.SOLID) != 0;
      boolean clear = q.cellClear(x, y, z);
      if (centreSolid) {
        solidCells++;
        assertTrue(!clear, name + ": cell with solid centre is clear at " + x + "," + y + "," + z);
      }
    }
    assertTrue(solidCells > 100, name + ": too few solid samples " + solidCells);

    // Same answers from four threads at once.
    boolean[] expected = new boolean[samples];
    for (int i = 0; i < samples; i++)
      expected[i] = q.cellClear(cells[i][0], cells[i][1], cells[i][2]);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    AtomicInteger mismatches = new AtomicInteger();
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int t = 0; t < 4; t++)
        jobs.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < samples; i++)
                    if (q.cellClear(cells[i][0], cells[i][1], cells[i][2]) != expected[i])
                      mismatches.incrementAndGet();
                }));
      for (Future<?> job : jobs) job.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(0, mismatches.get(), name + ": concurrent answers differ");

    // Timing of the hot queries.
    AtomicLong sink = new AtomicLong();
    long clearNs =
        time(
            samples,
            i -> sink.addAndGet(q.cellClear(cells[i][0], cells[i][1], cells[i][2]) ? 1 : 0));
    long supportNs =
        time(
            samples,
            i ->
                sink.addAndGet(
                    q.supportsFace(
                            cells[i][0], cells[i][1], cells[i][2], BlockFace.UP, SupportShape.FULL)
                        ? 1
                        : 0));
    long sightNs =
        time(
            samples,
            i -> {
              int[] a = cells[i];
              int[] b = cells[(i + 1) % samples];
              sink.addAndGet(
                  q.occludes(a[0] + .5, a[1] + .5, a[2] + .5, b[0] + .5, b[1] + .5, b[2] + .5)
                      ? 1
                      : 0);
            });
    assertTrue(supportNs < 2_000, name + ": support query " + supportNs + " ns");
    assertTrue(clearNs < 200_000, name + ": occupancy query " + clearNs + " ns");
    return String.format(
        Locale.ROOT,
        "%-11s build %5d ms  aligned brush faces %6d  supported block faces %7d (full %6d,"
            + " full with solid outside %5d)  cellClear %6d ns  supportsFace %4d ns  occludes"
            + " %6d ns  solid samples %d",
        name,
        buildMillis,
        q.statistics().alignedBrushFaces(),
        faces.get(),
        full.get(),
        outsideSolid.get(),
        clearNs,
        supportNs,
        sightNs,
        solidCells);
  }

  private interface Op {
    void run(int i);
  }

  private static long time(int n, Op op) {
    for (int i = 0; i < n; i++) op.run(i);
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) op.run(i);
    return (System.nanoTime() - start) / n;
  }

  private static double[] faceCentre(int x, int y, int z, BlockFace face) {
    double[] c = {x + 0.5, y + 0.5, z + 0.5};
    c[face.axis()] += face.sign() * 0.5;
    return c;
  }

  private static String first(List<String> list) {
    return list.isEmpty() ? "" : list.getFirst();
  }
}
