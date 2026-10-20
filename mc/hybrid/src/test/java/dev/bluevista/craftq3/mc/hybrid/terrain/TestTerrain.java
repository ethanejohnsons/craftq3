package dev.bluevista.craftq3.mc.hybrid.terrain;

import dev.bluevista.craftq3.engine.collision.BoxSource;
import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.map.SyntheticBsp;

/** Synthetic terrains for tests, and their brute-force equivalent as one box per solid. */
final class TestTerrain {
  private TestTerrain() {}

  /** A collision model with no geometry of its own. */
  static CollisionModel emptyWorld() {
    return CollisionModel.build(
        new SyntheticBsp().entity(BspEntity.of("classname", "worldspawn")).build("empty.bsp"));
  }

  /** Solid ground: every cell with {@code y < top} is full. */
  static TerrainCells flat(int top) {
    return (x, y, z, sink) -> {
      if (y < top) sink.full();
    };
  }

  /**
   * Deterministic pseudo-random cells in a box: full blocks, slabs, fences (rising half a block),
   * stairs, water and lava of various heights, ice and ladders. Outside the box everything is
   * empty.
   */
  static TerrainCells random(
      long seed, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    return (x, y, z, sink) -> {
      if (x < minX || y < minY || z < minZ || x > maxX || y > maxY || z > maxZ) return;
      long h = mix(seed ^ (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L));
      int kind = (int) Long.remainderUnsigned(h, 20);
      switch (kind) {
        case 0, 1, 2 -> sink.full();
        case 3 -> sink.solid(0, 0, 0, 1, 0.5, 1);
        case 4 -> sink.solid(0, 0.5, 0, 1, 1, 1);
        case 5 -> sink.solid(0.375, 0, 0.375, 0.625, 1.5, 0.625);
        case 6 -> {
          sink.solid(0, 0, 0, 1, 0.5, 1);
          sink.solid(0, 0.5, 0.5, 1, 1, 1);
        }
        case 7 -> sink.fluid(FluidKind.WATER, 8 / 9.0);
        case 8 -> sink.fluid(FluidKind.WATER, 1);
        case 9 -> sink.fluid(FluidKind.LAVA, (1 + (h >>> 40) % 8) / 9.0);
        case 10 -> {
          sink.full();
          sink.surfaceFlags(0x2);
        }
        case 11 -> {
          sink.solid(0, 0, 0.8125, 1, 1, 1);
          sink.climbable();
        }
        case 12 -> {
          sink.solid(0, 0, 0, 1, 0.5, 1);
          sink.fluid(FluidKind.WATER, 1);
        }
        default -> {}
      }
    };
  }

  /** Every solid and fluid box of the cells in a region, in Quake coordinates. */
  static BoxSource bruteForce(
      TerrainCells cells,
      CoordinateTransform t,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ) {
    BoxSource.Builder builder = BoxSource.builder();
    CellShape shape = new CellShape();
    float[] mins = new float[3];
    float[] maxs = new float[3];
    for (int y = minY; y <= maxY; y++)
      for (int z = minZ; z <= maxZ; z++)
        for (int x = minX; x <= maxX; x++) {
          cells.describe(x, y, z, shape.reset());
          for (int i = 0; i < shape.boxCount(); i++) {
            t.boxToQuake(
                x + (double) shape.box(i, 0),
                y + (double) shape.box(i, 1),
                z + (double) shape.box(i, 2),
                x + (double) shape.box(i, 3),
                y + (double) shape.box(i, 4),
                z + (double) shape.box(i, 5),
                mins,
                maxs);
            builder.add(mins, maxs, Contents.SOLID, shape.solidSurfaceFlags());
          }
          if (shape.fluidContents() != 0) {
            t.boxToQuake(x, y, z, x + 1, y + (double) shape.fluidHeight(), z + 1, mins, maxs);
            builder.add(mins, maxs, shape.fluidContents(), 0);
          }
        }
    return builder.build();
  }

  static long mix(long z) {
    z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
    z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
    return z ^ (z >>> 33);
  }
}
