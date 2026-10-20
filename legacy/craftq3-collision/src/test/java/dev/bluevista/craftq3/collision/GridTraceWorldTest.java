package dev.bluevista.craftq3.collision;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.*;
import org.junit.jupiter.api.Test;

class GridTraceWorldTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void gridMatchesExhaustiveBoxTracesAcrossNegativeCellsCornersAndLongRays() {
    var boxes = new LinkedHashMap<GridTraceWorld.Cell, List<TraceWorld>>();
    var all = new ArrayList<TraceWorld>();
    var random = new Random(42);
    for (int i = 0; i < 200; i++) {
      var c =
          new GridTraceWorld.Cell(
              random.nextInt(20) - 10, random.nextInt(20) - 10, random.nextInt(4) - 2);
      Vec3 min = new Vec3(c.x() * 32, c.y() * 32, c.z() * 32),
          max = min.add(new Vec3(32, 32, 16 + random.nextInt(33)));
      var shape = new BoxTraceWorld(min, max, 1, 0, 1022);
      boxes.computeIfAbsent(c, k -> new ArrayList<>()).add(shape);
      all.add(shape);
    }
    var grid = new GridTraceWorld(32, c -> boxes.getOrDefault(c, List.of()));
    var oracle = new CompositeTraceWorld(all);
    for (int i = 0; i < 500; i++) {
      var start =
          new Vec3(
              random.nextDouble() * 1000 - 500,
              random.nextDouble() * 1000 - 500,
              random.nextDouble() * 150 - 75);
      var end =
          i % 10 == 0
              ? start
              : new Vec3(
                  random.nextDouble() * 1000 - 500,
                  random.nextDouble() * 1000 - 500,
                  random.nextDouble() * 150 - 75);
      var request =
          TraceRequest.box(
              start,
              end,
              i % 2 == 0 ? ZERO : new Vec3(-15, -15, -24),
              i % 2 == 0 ? ZERO : new Vec3(15, 15, 32),
              1);
      var expected = oracle.trace(request);
      var actual = grid.trace(request);
      assertEquals(expected.fraction(), actual.fraction(), 1e-12);
      assertEquals(expected.startSolid(), actual.startSolid());
      assertEquals(expected.allSolid(), actual.allSolid());
      assertEquals(oracle.pointContents(start), grid.pointContents(start));
    }
  }

  @Test
  void liveBlockEditsChangeCollisionAndFluidsHonorMasks() {
    var shapes = new ArrayList<TraceWorld>();
    var world =
        new GridTraceWorld(
            32, c -> c.equals(new GridTraceWorld.Cell(0, 0, 0)) ? shapes : List.of());
    var ray = TraceRequest.ray(new Vec3(-10, 16, 16), new Vec3(40, 16, 16), 1);
    assertFalse(world.trace(ray).blocked());
    shapes.add(new BoxTraceWorld(ZERO, new Vec3(32, 32, 32), 1, 0, 1022));
    assertTrue(world.trace(ray).blocked());
    shapes.clear();
    shapes.add(new BoxTraceWorld(ZERO, new Vec3(32, 32, 16), 32, 0, 1022));
    assertFalse(world.trace(ray).blocked());
    assertEquals(32, world.pointContents(new Vec3(8, 8, 8)));
    assertEquals(0, world.pointContents(new Vec3(8, 8, 20)));
  }

  @Test
  void diagonalHitscanUsesLineNeighborhoodNotCubicRegion() {
    int[] queries = {0};
    var world =
        new GridTraceWorld(
            32,
            c -> {
              queries[0]++;
              return List.of();
            });
    assertFalse(world.trace(TraceRequest.ray(ZERO, new Vec3(8192, 8192, 8192), 1)).blocked());
    assertTrue(queries[0] < 10000);
  }
}
