package dev.bluevista.craftq3.mc.hybrid.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TerrainSnapshotTest {
  @Test
  void copiesEveryCellAndDeduplicatesShapes() {
    TerrainCells live = TestTerrain.random(11, -10, 0, -10, 10, 10, 10);
    TerrainSnapshot snapshot =
        TerrainSnapshot.capture(live, -12, -2, -12, 25, 15, 25, TerrainSnapshot.Outside.EMPTY);
    CellShape a = new CellShape();
    CellShape b = new CellShape();
    for (int y = -2; y < 13; y++)
      for (int z = -12; z < 13; z++)
        for (int x = -12; x < 13; x++) {
          live.describe(x, y, z, a.reset());
          snapshot.describe(x, y, z, b.reset());
          assertTrue(a.sameAs(b), "cell " + x + "," + y + "," + z);
        }
    // 13 kinds of random cell (lava has 8 heights): far fewer than the 9261 cells.
    assertTrue(snapshot.paletteSize() <= 20, "palette " + snapshot.paletteSize());
    assertTrue(snapshot.contains(-12, -2, -12));
    assertFalse(snapshot.contains(13, 0, 0));
  }

  @Test
  void outsidePolicyDecidesBoundaries() {
    TerrainCells live = TestTerrain.flat(0);
    TerrainSnapshot open =
        TerrainSnapshot.capture(live, 0, -4, 0, 8, 8, 8, TerrainSnapshot.Outside.EMPTY);
    TerrainSnapshot walled =
        TerrainSnapshot.capture(live, 0, -4, 0, 8, 8, 8, TerrainSnapshot.Outside.SOLID);
    CellShape shape = new CellShape();
    open.describe(20, 2, 2, shape.reset());
    assertTrue(shape.isEmpty());
    walled.describe(20, 2, 2, shape.reset());
    assertEquals(Contents.SOLID, shape.contents());
    walled.describe(2, 2, 2, shape.reset());
    assertTrue(shape.isEmpty());
  }

  @Test
  void tracesMatchTheLiveTerrainFromOtherThreads() throws Exception {
    CoordinateTransform t = CoordinateTransform.at(0, 0, 0);
    TerrainCells live = TestTerrain.random(5, -16, -4, -16, 16, 8, 16);
    TerrainSnapshot snapshot =
        TerrainSnapshot.capture(live, -16, -4, -16, 33, 13, 33, TerrainSnapshot.Outside.EMPTY);
    CollisionModel world = TestTerrain.emptyWorld();
    GridCollisionSource liveGrid = new GridCollisionSource(live, t, false);
    GridCollisionSource copyGrid = new GridCollisionSource(snapshot, t, true);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    AtomicBoolean mismatch = new AtomicBoolean();
    try {
      Future<?>[] jobs = new Future<?>[4];
      for (int j = 0; j < jobs.length; j++) {
        int seed = j;
        jobs[j] =
            pool.submit(
                () -> {
                  TraceWork work = new TraceWork();
                  Trace a = new Trace();
                  Trace b = new Trace();
                  Random random = new Random(seed);
                  float[] mins = {-15, -15, -24};
                  float[] maxs = {15, 15, 32};
                  for (int i = 0; i < 2000; i++) {
                    float[] s = point(random);
                    float[] e = point(random);
                    world.boxTrace(work, a, s, e, mins, maxs, 0, -1, false, liveGrid);
                    world.boxTrace(work, b, s, e, mins, maxs, 0, -1, false, copyGrid);
                    if (a.fraction() != b.fraction() || a.startSolid() != b.startSolid())
                      mismatch.set(true);
                  }
                });
      }
      for (Future<?> job : jobs) job.get();
    } finally {
      pool.shutdownNow();
    }
    assertFalse(mismatch.get());
  }

  @Test
  void rejectsOversizedBoxes() {
    TerrainCells live = TestTerrain.flat(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> TerrainSnapshot.capture(live, 0, 0, 0, 0, 1, 1, TerrainSnapshot.Outside.EMPTY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerrainSnapshot.capture(
                live, 0, 0, 0, 4096, 4096, 4096, TerrainSnapshot.Outside.EMPTY));
  }

  private static float[] point(Random random) {
    return new float[] {
      (random.nextFloat() - 0.5f) * 30 * 32,
      (random.nextFloat() - 0.5f) * 30 * 32,
      (random.nextFloat() - 0.3f) * 10 * 32
    };
  }
}
