package dev.bluevista.craftq3.mc.hybrid.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable copy of a box of terrain cells, safe to trace from any thread while the live world
 * changes. Capture it on the thread that owns the live cells (the Minecraft server thread), for
 * example a few chunk sections around the bridge player, then hand it to a {@link
 * GridCollisionSource} running elsewhere.
 *
 * <p>Distinct cell descriptions are stored once in a palette, as Minecraft stores block states, so
 * a region costs two bytes per cell plus its distinct shapes. Cells outside the box are empty or
 * full according to {@link Outside}.
 */
public final class TerrainSnapshot implements TerrainCells {
  /** What cells outside the captured box report. */
  public enum Outside {
    /** Nothing: open space. */
    EMPTY,
    /** Full blocks, like unloaded chunks: collision boundaries. */
    SOLID
  }

  /** The largest box captured, in cells. */
  public static final long MAX_CELLS = 1L << 25;

  /** Distinct cell descriptions per snapshot (index 0 is the empty cell). */
  public static final int MAX_PALETTE = 65535;

  private final int minX;
  private final int minY;
  private final int minZ;
  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final char[] cells;
  private final CellShape[] palette;
  private final Outside outside;
  private final double maxRise;
  private final int worldMinY;
  private final int worldMaxY;

  private TerrainSnapshot(
      int minX,
      int minY,
      int minZ,
      int sizeX,
      int sizeY,
      int sizeZ,
      char[] cells,
      CellShape[] palette,
      Outside outside,
      double maxRise,
      int worldMinY,
      int worldMaxY) {
    this.minX = minX;
    this.minY = minY;
    this.minZ = minZ;
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.cells = cells;
    this.palette = palette;
    this.outside = outside;
    this.maxRise = maxRise;
    this.worldMinY = worldMinY;
    this.worldMaxY = worldMaxY;
  }

  /**
   * Copies the cells {@code min .. min + size - 1} of {@code source}. Call on the thread that owns
   * {@code source}.
   */
  public static TerrainSnapshot capture(
      TerrainCells source,
      int minX,
      int minY,
      int minZ,
      int sizeX,
      int sizeY,
      int sizeZ,
      Outside outside) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(outside, "outside");
    if (sizeX < 1 || sizeY < 1 || sizeZ < 1)
      throw new IllegalArgumentException("Snapshot sizes must be positive");
    long count = (long) sizeX * sizeY * sizeZ;
    if (count > MAX_CELLS)
      throw new IllegalArgumentException("Snapshot of " + count + " cells exceeds " + MAX_CELLS);
    double rise = source.maxRise();
    char[] cells = new char[(int) count];
    List<CellShape> palette = new ArrayList<>();
    palette.add(new CellShape());
    Map<Key, Integer> index = new HashMap<>();
    CellShape scratch = new CellShape().maxRise(rise);
    int i = 0;
    for (int y = 0; y < sizeY; y++)
      for (int z = 0; z < sizeZ; z++)
        for (int x = 0; x < sizeX; x++, i++) {
          source.describe(minX + x, minY + y, minZ + z, scratch.reset());
          if (scratch.isEmpty()) continue;
          Key key = new Key(scratch);
          Integer id = index.get(key);
          if (id == null) {
            if (palette.size() > MAX_PALETTE)
              throw new IllegalStateException("More than " + MAX_PALETTE + " distinct cells");
            CellShape copy = new CellShape().maxRise(rise).copyFrom(scratch);
            id = palette.size();
            palette.add(copy);
            index.put(new Key(copy), id);
          }
          cells[i] = (char) (int) id;
        }
    return new TerrainSnapshot(
        minX,
        minY,
        minZ,
        sizeX,
        sizeY,
        sizeZ,
        cells,
        palette.toArray(CellShape[]::new),
        outside,
        rise,
        source.minY(),
        source.maxY());
  }

  @Override
  public void describe(int x, int y, int z, CellSink sink) {
    int lx = x - minX;
    int ly = y - minY;
    int lz = z - minZ;
    if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) {
      if (outside == Outside.SOLID) sink.full();
      return;
    }
    int id = cells[(ly * sizeZ + lz) * sizeX + lx];
    if (id != 0) palette[id].replay(sink);
  }

  @Override
  public double maxRise() {
    return maxRise;
  }

  @Override
  public int minY() {
    return worldMinY;
  }

  @Override
  public int maxY() {
    return worldMaxY;
  }

  /** True when {@code (x, y, z)} lies inside the captured box. */
  public boolean contains(int x, int y, int z) {
    return x >= minX
        && y >= minY
        && z >= minZ
        && x < minX + sizeX
        && y < minY + sizeY
        && z < minZ + sizeZ;
  }

  /** Distinct non-empty cell descriptions. */
  public int paletteSize() {
    return palette.length - 1;
  }

  public int minX() {
    return minX;
  }

  public int minZ() {
    return minZ;
  }

  public int sizeX() {
    return sizeX;
  }

  public int sizeY() {
    return sizeY;
  }

  public int sizeZ() {
    return sizeZ;
  }

  /** Palette key: equality by cell description. */
  private static final class Key {
    final CellShape shape;
    final int hash;

    Key(CellShape shape) {
      this.shape = shape;
      this.hash = shape.shapeHash();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key k && k.hash == hash && k.shape.sameAs(shape);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
