package dev.bluevista.craftq3.mc.hybrid.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.collision.BoxSource;
import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.collision.SurfaceFlags;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GridCollisionSourceTest {
  private static final float[] HULL_MINS = {-15, -15, -24};
  private static final float[] HULL_MAXS = {15, 15, 32};

  private final CollisionModel world = TestTerrain.emptyWorld();
  private final TraceWork work = new TraceWork();

  private Trace trace(
      CollisionSource source, float[] start, float[] end, float[] mins, float[] maxs, int mask) {
    Trace out = new Trace();
    world.boxTrace(
        work, out, start, end, mins, maxs, CollisionModel.WORLD_MODEL, mask, false, source);
    return out;
  }

  @Test
  void matchesBruteForceBoxesOnRandomTerrain() {
    // Region: x -20..20, y 40..60, z -20..20 around an origin inside it.
    CoordinateTransform t = CoordinateTransform.at(3, 50, -7);
    TerrainCells cells = TestTerrain.random(42, -20, 40, -20, 20, 60, 20);
    BoxSource brute = TestTerrain.bruteForce(cells, t, -20, 40, -20, 20, 60, 20);
    GridCollisionSource cached = new GridCollisionSource(cells, t, true);
    GridCollisionSource uncached = new GridCollisionSource(cells, t, false);
    Random random = new Random(5);
    int hits = 0;
    int longTraces = 0;
    List<String> failures = new ArrayList<>();
    List<String> ties = new ArrayList<>();
    for (int i = 0; i < 3000; i++) {
      int kind = i % 5;
      float[] start = randomPoint(random, t, 18);
      float[] end;
      if (kind == 4) end = start.clone();
      else if (kind == 3) {
        // Long traces exceed the exhaustive budget and take the diagonal walk.
        end = randomPoint(random, t, 18);
        longTraces++;
      } else {
        end = start.clone();
        for (int axis = 0; axis < 3; axis++) end[axis] += (random.nextFloat() - 0.5f) * 160;
      }
      boolean box = (i & 1) == 0;
      float[] mins = box ? HULL_MINS : null;
      float[] maxs = box ? HULL_MAXS : null;
      int mask = (i % 3 == 0) ? Contents.MASK_PLAYERSOLID | Contents.MASK_WATER : Contents.SOLID;
      Trace expected = trace(brute, start, end, mins, maxs, mask);
      for (GridCollisionSource grid : List.of(cached, uncached)) {
        Trace actual = trace(grid, start, end, mins, maxs, mask);
        boolean hard =
            expected.fraction() != actual.fraction()
                || expected.startSolid() != actual.startSolid()
                || expected.allSolid() != actual.allSolid();
        boolean soft =
            expected.contents() != actual.contents()
                || expected.surfaceFlags() != actual.surfaceFlags()
                || (expected.fraction() < 1
                    && (expected.planeNormal(0) != actual.planeNormal(0)
                        || expected.planeNormal(1) != actual.planeNormal(1)
                        || expected.planeNormal(2) != actual.planeNormal(2)));
        if (hard) failures.add("trace " + i + ": expected " + expected + " got " + actual);
        else if (soft)
          ties.add("trace " + i + " kind " + kind + ": expected " + expected + " got " + actual);
      }
      if (expected.blocked()) hits++;
    }
    assertTrue(
        failures.isEmpty(), () -> failures.size() + " mismatches, first: " + failures.getFirst());
    // Equally near boxes are visited in a different order only by the diagonal walk of long
    // traces; then which coplanar box's contents and flags are reported may differ, as it does
    // between brush orders in the original.
    for (String tie : ties) assertTrue(tie.contains("kind 3"), tie);
    assertTrue(ties.size() < 60, ties.size() + " tie-order differences");
    assertTrue(hits > 1000, "the terrain should obstruct many traces, got " + hits);
    assertTrue(longTraces > 500);
  }

  @Test
  void pointContentsMatchesBruteForce() {
    CoordinateTransform t = CoordinateTransform.at(0, 64, 0);
    TerrainCells cells = TestTerrain.random(7, -6, 60, -6, 6, 70, 6);
    BoxSource brute = TestTerrain.bruteForce(cells, t, -6, 60, -6, 6, 70, 6);
    GridCollisionSource grid = new GridCollisionSource(cells, t, true);
    Random random = new Random(9);
    int nonEmpty = 0;
    for (int i = 0; i < 20000; i++) {
      float[] p = randomPoint(random, t, 6);
      if (i % 4 == 0) for (int axis = 0; axis < 3; axis++) p[axis] = Math.round(p[axis] / 16) * 16f;
      int expected = brute.pointContents(p[0], p[1], p[2]);
      assertEquals(expected, grid.pointContents(p[0], p[1], p[2]), "point " + i);
      if (expected != 0) nonEmpty++;
    }
    assertTrue(nonEmpty > 3000);
  }

  @Test
  void playerStandsOnFlatGroundAndHitsAWall() {
    CoordinateTransform t = CoordinateTransform.at(100, 64, 100);
    // Ground below y=64 and a wall at x >= 105 (5 blocks east of the origin), 3 blocks tall.
    TerrainCells cells =
        (x, y, z, sink) -> {
          if (y < 64 || (x >= 105 && y < 67)) sink.full();
        };
    GridCollisionSource grid = new GridCollisionSource(cells, t);
    // Drop from 2 blocks up: the hull's feet (mins z = -24) land on the ground at z = 0.
    Trace down =
        trace(
            grid,
            new float[] {16, -16, 88},
            new float[] {16, -16, -100},
            HULL_MINS,
            HULL_MAXS,
            Contents.MASK_PLAYERSOLID);
    assertTrue(down.fraction() < 1);
    assertEquals(24, down.endPos(2), 0.2f);
    assertEquals(1, down.planeNormal(2));
    // Walk east: the hull's front (maxs x = 15) stops at the wall face x = 5 * 32 = 160.
    Trace east =
        trace(
            grid,
            new float[] {16, -16, 25},
            new float[] {600, -16, 25},
            HULL_MINS,
            HULL_MAXS,
            Contents.MASK_PLAYERSOLID);
    assertEquals(145, east.endPos(0), 0.2f);
    assertEquals(1, -east.planeNormal(0));
    // Above the wall (z 3 blocks = 96, feet at 97) the way is clear.
    Trace over =
        trace(
            grid,
            new float[] {16, -16, 125},
            new float[] {600, -16, 125},
            HULL_MINS,
            HULL_MAXS,
            Contents.MASK_PLAYERSOLID);
    assertEquals(1, over.fraction());
    // A rail shot across 256 blocks hits the wall.
    Trace rail =
        trace(
            grid,
            new float[] {0, 0, 40},
            new float[] {8192, 0, 40},
            null,
            null,
            Contents.MASK_SHOT);
    assertEquals(160, rail.endPos(0), 0.2f);
    assertEquals(Contents.SOLID, rail.contents());
  }

  @Test
  void fluidsReportTheirContentsUpToTheirHeight() {
    CoordinateTransform t = CoordinateTransform.at(0, 0, 0);
    TerrainCells cells =
        (x, y, z, sink) -> {
          if (y < 0) sink.full();
          else if (y < 2 && x >= 0 && x < 4) sink.fluid(FluidKind.WATER, y == 1 ? 8 / 9.0 : 1);
          else if (y == 0 && x >= 4 && x < 6) sink.fluid(FluidKind.LAVA, 0.5);
        };
    GridCollisionSource grid = new GridCollisionSource(cells, t);
    assertEquals(Contents.WATER, grid.pointContents(16, -16, 16));
    assertEquals(Contents.WATER, grid.pointContents(16, -16, 32 + 28));
    assertEquals(0, grid.pointContents(16, -16, 32 + 29));
    assertEquals(Contents.LAVA, grid.pointContents(4 * 32 + 16, -16, 15));
    assertEquals(0, grid.pointContents(4 * 32 + 16, -16, 17));
    assertEquals(Contents.SOLID, grid.pointContents(16, -16, -1));
    // Water does not block player movement, but a water-masked trace stops at its surface.
    Trace swim =
        trace(
            grid,
            new float[] {16, -16, 100},
            new float[] {16, -16, 10},
            null,
            null,
            Contents.MASK_PLAYERSOLID);
    assertEquals(1, swim.fraction());
    Trace surface =
        trace(
            grid,
            new float[] {16, -16, 100},
            new float[] {16, -16, 10},
            null,
            null,
            Contents.MASK_WATER);
    assertEquals(32 + 32 * 8 / 9f, surface.endPos(2), 0.2f);
    assertEquals(Contents.WATER, surface.contents());
  }

  @Test
  void climbableAndSlickCellsCarrySurfaceFlags() {
    CoordinateTransform t = CoordinateTransform.at(0, 0, 0);
    TerrainCells cells =
        (x, y, z, sink) -> {
          if (y < 0) {
            sink.full();
            if (x == 1) sink.surfaceFlags(SurfaceFlags.SLICK);
          } else if (x == 3 && y < 4) {
            sink.solid(0, 0, 0, 0.1875, 1, 1);
            sink.climbable();
          }
        };
    GridCollisionSource grid = new GridCollisionSource(cells, t);
    Trace ice =
        trace(
            grid,
            new float[] {48, -16, 50},
            new float[] {48, -16, -50},
            null,
            null,
            Contents.SOLID);
    assertEquals(SurfaceFlags.SLICK, ice.surfaceFlags());
    Trace ladder =
        trace(
            grid,
            new float[] {64, -16, 50},
            new float[] {200, -16, 50},
            null,
            null,
            Contents.SOLID);
    assertEquals(96, ladder.endPos(0), 0.2f);
    assertEquals(SurfaceFlags.LADDER, ladder.surfaceFlags());
  }

  @Test
  void invalidationObservesChangedBlocks() {
    CoordinateTransform t = CoordinateTransform.at(0, 0, 0);
    AtomicInteger wall = new AtomicInteger(1);
    TerrainCells cells =
        (x, y, z, sink) -> {
          if (x == 3 && wall.get() == 1) sink.full();
        };
    GridCollisionSource grid = new GridCollisionSource(cells, t, true);
    float[] a = {16, -16, 16};
    float[] b = {300, -16, 16};
    assertTrue(trace(grid, a, b, null, null, Contents.SOLID).fraction() < 1);
    wall.set(0);
    // The cache still holds the wall until invalidated.
    assertTrue(trace(grid, a, b, null, null, Contents.SOLID).fraction() < 1);
    grid.invalidate();
    assertEquals(1, trace(grid, a, b, null, null, Contents.SOLID).fraction());
  }

  @Test
  void unloadedCellsAreBoundaries() {
    CoordinateTransform t = CoordinateTransform.at(0, 0, 0);
    TerrainCells cells =
        (x, y, z, sink) -> {
          if (y < 0 || x >= 16) sink.full(); // x >= 16: an unloaded chunk
        };
    GridCollisionSource grid = new GridCollisionSource(cells, t);
    Trace out =
        trace(
            grid,
            new float[] {16, -16, 40},
            new float[] {4000, -16, 40},
            HULL_MINS,
            HULL_MAXS,
            Contents.MASK_PLAYERSOLID);
    assertEquals(16 * 32 - 15, out.endPos(0), 0.2f);
  }

  @Test
  void cellsOutsideTheVerticalRangeAreEmpty() {
    CoordinateTransform t = CoordinateTransform.at(0, 0, 0);
    TerrainCells cells =
        new TerrainCells() {
          @Override
          public void describe(int x, int y, int z, CellSink sink) {
            sink.full();
          }

          @Override
          public int minY() {
            return -4;
          }

          @Override
          public int maxY() {
            return 4;
          }
        };
    GridCollisionSource grid = new GridCollisionSource(cells, t);
    assertEquals(Contents.SOLID, grid.pointContents(0, 0, 3 * 32 + 1));
    assertEquals(0, grid.pointContents(0, 0, 4 * 32 + 1));
    assertEquals(0, grid.pointContents(0, 0, -5 * 32 - 1));
    assertFalse(
        trace(grid, new float[] {0, 0, 200}, new float[] {50, 0, 300}, null, null, -1).blocked());
  }

  @Test
  void hotPathDoesNotAllocate() {
    CoordinateTransform t = CoordinateTransform.at(0, 64, 0);
    TerrainCells cells = TestTerrain.random(3, -32, 50, -32, 32, 80, 32);
    for (boolean cached : new boolean[] {true, false}) {
      GridCollisionSource grid = new GridCollisionSource(cells, t, cached);
      Random random = new Random(1);
      float[][] points = new float[128][];
      for (int i = 0; i < points.length; i++) points[i] = randomPoint(random, t, 30);
      Trace out = new Trace();
      Runnable batch =
          () -> {
            for (int i = 0; i < points.length; i++) {
              float[] s = points[i];
              float[] e = points[(i + 1) % points.length];
              world.boxTrace(work, out, s, e, null, null, 0, -1, false, grid);
              world.boxTrace(work, out, s, e, HULL_MINS, HULL_MAXS, 0, -1, false, grid);
              world.boxTrace(work, out, s, s, HULL_MINS, HULL_MAXS, 0, -1, false, grid);
              grid.pointContents(s[0], s[1], s[2]);
            }
          };
      for (int i = 0; i < 20; i++) batch.run();
      var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      long thread = Thread.currentThread().threadId();
      long before = threads.getThreadAllocatedBytes(thread);
      for (int i = 0; i < 10; i++) batch.run();
      long allocated = threads.getThreadAllocatedBytes(thread) - before;
      assertTrue(allocated < 4096, "allocated " + allocated + " bytes (cached " + cached + ")");
    }
  }

  private static float[] randomPoint(Random random, CoordinateTransform t, int radius) {
    double x = t.originX() + (random.nextDouble() * 2 - 1) * radius;
    double y = t.originY() + (random.nextDouble() * 2 - 1) * Math.min(radius, 9);
    double z = t.originZ() + (random.nextDouble() * 2 - 1) * radius;
    float[] q = new float[3];
    t.toQuake(x, y, z, q);
    return q;
  }
}
