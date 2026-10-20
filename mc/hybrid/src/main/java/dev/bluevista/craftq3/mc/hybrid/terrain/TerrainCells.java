package dev.bluevista.craftq3.mc.hybrid.terrain;

/**
 * Block-cell collision shapes in Minecraft block coordinates, the SPI the Fabric adapter implements
 * over a level (or that {@link TerrainSnapshot} implements over a copy).
 *
 * <p>{@link #describe} reports one cell's collision boxes, fluid and surface properties into a
 * reused {@link CellSink}. Boxes are block-local ({@code 0..1} on each axis for a full block) and
 * may rise above the cell by at most {@link #maxRise()} (fences and walls are 1.5 blocks tall);
 * they never leave the cell horizontally or below. An unloaded cell should be reported as {@link
 * CellSink#full() full}, so unloaded chunks act as collision boundaries.
 *
 * <p>Implementations are called on every thread that traces the terrain and must not retain the
 * sink. They should not allocate per call.
 */
public interface TerrainCells {
  /** The most any cell's boxes rise above the cell's top, in blocks. */
  double DEFAULT_MAX_RISE = 0.5;

  /** Describes cell {@code (x, y, z)} into {@code sink}. */
  void describe(int x, int y, int z, CellSink sink);

  /** The most any cell's boxes rise above its top, in blocks, in {@code [0, 1]}. */
  default double maxRise() {
    return DEFAULT_MAX_RISE;
  }

  /** The lowest cell Y that can hold anything; cells below are empty. */
  default int minY() {
    return -2048;
  }

  /** One past the highest cell Y that can hold anything; cells at or above are empty. */
  default int maxY() {
    return 2048;
  }
}
