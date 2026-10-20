package dev.bluevista.craftq3.mc.hybrid.terrain;

import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.collision.TraceClipper;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.Objects;

/**
 * Minecraft terrain as foreign Quake collision: every block cell the swept volume may touch is
 * described by a {@link TerrainCells} and its boxes are clipped into the running world trace, so
 * qagame and cgame traces (movement, weapons, items) collide with blocks exactly as with brushes.
 * Solids report {@code CONTENTS_SOLID}; water, lava and slime volumes report their contents up to
 * the fluid height; climbable cells carry {@code SURF_LADDER}.
 *
 * <p>Candidate cells: a small swept volume is enumerated exhaustively. A long trace is covered by
 * the cells along its segment: {@link TraceClipper} exposes only the swept bounds, whose four
 * diagonals include the real segment, so each diagonal is walked as a thick segment padded by the
 * possible box size (bounded by {@link #BOX_BOUND}) and Minecraft's shape rise. Walking all four
 * costs up to four times the exact walk; an engine hook exposing the trace's start and end would
 * remove that factor.
 *
 * <p>Thread-safe and allocation-free after each thread's first trace: per-thread scratch, and an
 * optional per-thread cell cache invalidated by {@link #invalidate()} (call it whenever blocks may
 * have changed, e.g. once per Minecraft tick).
 */
public final class GridCollisionSource implements CollisionSource {
  /** Swept volumes with at most this many candidate cells are enumerated exhaustively. */
  public static final int EXHAUSTIVE_CELLS = 512;

  /**
   * The largest trace box (Quake units per axis) assumed for long box sweeps. Every baseq3 box is
   * at most 64 units; longer sweeps of larger boxes may miss terrain beside the segment.
   */
  public static final float BOX_BOUND = 256;

  /** Longest walked span in blocks; longer traces are clipped to nothing (with a warning). */
  public static final int MAX_SPAN_BLOCKS = 1 << 16;

  private static final float EPSILON = 0.125f;
  private static final int CACHE_SLOTS = 512;
  private static final Log LOG = Log.get(GridCollisionSource.class);

  private final TerrainCells cells;
  private final CoordinateTransform transform;
  private final double inverseScale;
  private final int originX;
  private final int originY;
  private final int originZ;
  private final double rise;
  private final int minY;
  private final int maxY;
  private final boolean cached;
  private volatile int generation = 1;
  private final ThreadLocal<Work> works;

  /**
   * @param cells the terrain
   * @param transform the coordinate mapping; its origin must be a block corner
   * @param cached keep a small per-thread cache of described cells until {@link #invalidate()}
   */
  public GridCollisionSource(TerrainCells cells, CoordinateTransform transform, boolean cached) {
    this.cells = Objects.requireNonNull(cells, "cells");
    this.transform = Objects.requireNonNull(transform, "transform");
    if (!transform.gridAligned())
      throw new IllegalArgumentException("Terrain collision needs a block-aligned origin");
    double r = cells.maxRise();
    if (!(r >= 0 && r <= 1)) throw new IllegalArgumentException("maxRise must be in [0, 1]");
    this.rise = r;
    this.inverseScale = 1 / transform.unitsPerBlock();
    this.originX = (int) transform.originX();
    this.originY = (int) transform.originY();
    this.originZ = (int) transform.originZ();
    this.minY = cells.minY();
    this.maxY = cells.maxY();
    this.cached = cached;
    this.works = ThreadLocal.withInitial(() -> new Work(cached, rise));
  }

  public GridCollisionSource(TerrainCells cells, CoordinateTransform transform) {
    this(cells, transform, true);
  }

  public CoordinateTransform transform() {
    return transform;
  }

  public TerrainCells cells() {
    return cells;
  }

  /** Forgets every thread's cached cells; the next traces describe cells afresh. */
  public void invalidate() {
    generation++;
  }

  // ---- traces --------------------------------------------------------------------------------

  @Override
  public void clip(TraceClipper clipper) {
    Work w = works.get();
    float qMinX = clipper.sweptMin(0) - EPSILON;
    float qMaxX = clipper.sweptMax(0) + EPSILON;
    float qMinY = clipper.sweptMin(1) - EPSILON;
    float qMaxY = clipper.sweptMax(1) + EPSILON;
    float qMinZ = clipper.sweptMin(2) - EPSILON;
    float qMaxZ = clipper.sweptMax(2) + EPSILON;
    // Local block coordinates: L0 = MC x, L1 = MC y (Quake z), L2 = MC z (Quake -y).
    double l0a = qMinX * inverseScale;
    double l0b = qMaxX * inverseScale;
    double l1a = qMinZ * inverseScale - rise;
    double l1b = qMaxZ * inverseScale;
    double l2a = -qMaxY * inverseScale;
    double l2b = -qMinY * inverseScale;
    if (l0b - l0a > MAX_SPAN_BLOCKS || l1b - l1a > MAX_SPAN_BLOCKS || l2b - l2a > MAX_SPAN_BLOCKS) {
      LOG.warnOnce("terrain-span", "A trace spans more than " + MAX_SPAN_BLOCKS + " blocks");
      return;
    }
    int x0 = floor(l0a);
    int x1 = floor(l0b);
    int y0 = Math.max(floor(l1a), minY - originY);
    int y1 = Math.min(floor(l1b), maxY - 1 - originY);
    int z0 = floor(l2a);
    int z1 = floor(l2b);
    if (y0 > y1) return;
    long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
    if (count <= EXHAUSTIVE_CELLS) {
      for (int y = y0; y <= y1; y++)
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) clipCell(clipper, w, x, y, z);
      return;
    }
    walkDiagonals(clipper, w);
  }

  private void walkDiagonals(TraceClipper clipper, Work w) {
    // The un-padded swept bounds in local coordinates.
    double[] lo = w.lo;
    double[] hi = w.hi;
    lo[0] = clipper.sweptMin(0) * inverseScale;
    hi[0] = clipper.sweptMax(0) * inverseScale;
    lo[1] = clipper.sweptMin(2) * inverseScale;
    hi[1] = clipper.sweptMax(2) * inverseScale;
    lo[2] = -clipper.sweptMax(1) * inverseScale;
    hi[2] = -clipper.sweptMin(1) * inverseScale;
    double epsilon = EPSILON * inverseScale;
    double bound = BOX_BOUND * inverseScale;
    boolean point = clipper.isPoint();
    for (int axis = 0; axis < 3; axis++) {
      double box = point ? 0 : Math.min(hi[axis] - lo[axis], bound);
      w.padLow[axis] = box + epsilon;
      w.padHigh[axis] = box + epsilon;
    }
    w.padLow[1] += rise;
    for (int pattern = 0; pattern < 4; pattern++) {
      boolean flipY = (pattern & 1) != 0;
      boolean flipZ = (pattern & 2) != 0;
      if (flipY && hi[1] == lo[1]) continue;
      if (flipZ && hi[2] == lo[2]) continue;
      w.a[0] = lo[0];
      w.b[0] = hi[0];
      w.a[1] = flipY ? hi[1] : lo[1];
      w.b[1] = flipY ? lo[1] : hi[1];
      w.a[2] = flipZ ? hi[2] : lo[2];
      w.b[2] = flipZ ? lo[2] : hi[2];
      walkThick(clipper, w);
    }
  }

  /** Visits every cell within the padding of the segment {@code w.a .. w.b} (local coordinates). */
  private void walkThick(TraceClipper clipper, Work w) {
    double[] a = w.a;
    double[] b = w.b;
    int slab = 0;
    double longest = -1;
    for (int axis = 0; axis < 3; axis++) {
      double span = Math.abs(b[axis] - a[axis]);
      if (span > longest) {
        longest = span;
        slab = axis;
      }
    }
    int u = slab == 0 ? 1 : 0;
    int v = slab == 2 ? 1 : 2;
    double d = b[slab] - a[slab];
    int first = floor(Math.min(a[slab], b[slab]) - w.padLow[slab]);
    int last = floor(Math.max(a[slab], b[slab]) + w.padHigh[slab]);
    int[] cell = w.cell;
    for (int i = first; i <= last; i++) {
      double t0;
      double t1;
      if (d == 0) {
        t0 = 0;
        t1 = 1;
      } else {
        t0 = (i - w.padHigh[slab] - a[slab]) / d;
        t1 = (i + 1 + w.padLow[slab] - a[slab]) / d;
        if (t0 > t1) {
          double swap = t0;
          t0 = t1;
          t1 = swap;
        }
        t0 = Math.max(t0, 0);
        t1 = Math.min(t1, 1);
        if (t0 > t1) continue;
      }
      double u0 = a[u] + (b[u] - a[u]) * t0;
      double u1 = a[u] + (b[u] - a[u]) * t1;
      double v0 = a[v] + (b[v] - a[v]) * t0;
      double v1 = a[v] + (b[v] - a[v]) * t1;
      int uFirst = floor(Math.min(u0, u1) - w.padLow[u]);
      int uLast = floor(Math.max(u0, u1) + w.padHigh[u]);
      int vFirst = floor(Math.min(v0, v1) - w.padLow[v]);
      int vLast = floor(Math.max(v0, v1) + w.padHigh[v]);
      cell[slab] = i;
      for (int j = uFirst; j <= uLast; j++) {
        cell[u] = j;
        for (int k = vFirst; k <= vLast; k++) {
          cell[v] = k;
          int y = cell[1];
          if (y + originY < minY || y + originY >= maxY) continue;
          clipCell(clipper, w, cell[0], y, cell[2]);
        }
      }
    }
  }

  /** Clips cell (local {@code x, y, z}) into the trace. */
  private void clipCell(TraceClipper clipper, Work w, int x, int y, int z) {
    CellShape shape = w.shape(cells, originX + x, originY + y, originZ + z, generation);
    if (shape.isEmpty()) return;
    // Double arithmetic, rounded once, so a box lands on exactly the float a brush at the same
    // place would have.
    double s = transform.unitsPerBlock();
    int flags = shape.solidSurfaceFlags();
    float[] boxes = shape.boxes;
    for (int i = 0; i < shape.count; i++) {
      int k = i * 6;
      clipBox(
          clipper,
          (float) ((x + (double) boxes[k]) * s),
          (float) ((0 - (z + (double) boxes[k + 5])) * s),
          (float) ((y + (double) boxes[k + 1]) * s),
          (float) ((x + (double) boxes[k + 3]) * s),
          (float) ((0 - (z + (double) boxes[k + 2])) * s),
          (float) ((y + (double) boxes[k + 4]) * s),
          Contents.SOLID,
          flags);
    }
    if (shape.fluidContents != 0)
      clipBox(
          clipper,
          (float) (x * s),
          (float) ((0 - (z + 1.0)) * s),
          (float) (y * s),
          (float) ((x + 1.0) * s),
          (float) ((0 - (double) z) * s),
          (float) ((y + (double) shape.fluidHeight) * s),
          shape.fluidContents,
          0);
  }

  private static void clipBox(
      TraceClipper c,
      float minX,
      float minY,
      float minZ,
      float maxX,
      float maxY,
      float maxZ,
      int contents,
      int flags) {
    if ((contents & c.contentMask()) == 0) return;
    if (c.sweptMax(0) < minX - EPSILON
        || c.sweptMax(1) < minY - EPSILON
        || c.sweptMax(2) < minZ - EPSILON
        || c.sweptMin(0) > maxX + EPSILON
        || c.sweptMin(1) > maxY + EPSILON
        || c.sweptMin(2) > maxZ + EPSILON) return;
    c.clipBox(minX, minY, minZ, maxX, maxY, maxZ, contents, flags);
  }

  // ---- point contents ------------------------------------------------------------------------

  @Override
  public int pointContents(float qx, float qy, float qz) {
    Work w = works.get();
    double lx = qx * inverseScale;
    double ly = qz * inverseScale;
    double lz = -qy * inverseScale;
    int x = floor(lx);
    int y = floor(ly);
    int z = floor(lz);
    // A point on a cell face belongs to both cells, as a point on a brush face is inside it.
    int xFrom = lx == x ? x - 1 : x;
    int zFrom = lz == z ? z - 1 : z;
    int yFrom = rise > 0 || ly == y ? y - 1 : y;
    int result = 0;
    for (int cy = yFrom; cy <= y; cy++) {
      if (cy + originY < minY || cy + originY >= maxY) continue;
      for (int cz = zFrom; cz <= z; cz++)
        for (int cx = xFrom; cx <= x; cx++)
          result |=
              cellContents(w, cx, cy, cz, (float) (lx - cx), (float) (ly - cy), (float) (lz - cz));
    }
    return result;
  }

  private int cellContents(Work w, int x, int y, int z, float px, float py, float pz) {
    CellShape shape = w.shape(cells, originX + x, originY + y, originZ + z, generation);
    if (shape.isEmpty()) return 0;
    int result = 0;
    float[] boxes = shape.boxes;
    for (int i = 0; i < shape.count; i++) {
      int k = i * 6;
      if (px >= boxes[k]
          && py >= boxes[k + 1]
          && pz >= boxes[k + 2]
          && px <= boxes[k + 3]
          && py <= boxes[k + 4]
          && pz <= boxes[k + 5]) {
        result |= Contents.SOLID;
        break;
      }
    }
    if (shape.fluidContents != 0
        && px >= 0
        && px <= 1
        && pz >= 0
        && pz <= 1
        && py >= 0
        && py <= shape.fluidHeight) result |= shape.fluidContents;
    return result;
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }

  /** Per-thread scratch and cell cache. */
  private static final class Work {
    final double[] lo = new double[3];
    final double[] hi = new double[3];
    final double[] a = new double[3];
    final double[] b = new double[3];
    final double[] padLow = new double[3];
    final double[] padHigh = new double[3];
    final int[] cell = new int[3];
    final CellShape scratch;
    final long[] keys;
    final int[] generations;
    final CellShape[] shapes;

    Work(boolean cached, double rise) {
      scratch = new CellShape().maxRise(rise);
      if (cached) {
        keys = new long[CACHE_SLOTS];
        generations = new int[CACHE_SLOTS];
        shapes = new CellShape[CACHE_SLOTS];
        for (int i = 0; i < CACHE_SLOTS; i++) shapes[i] = new CellShape().maxRise(rise);
      } else {
        keys = null;
        generations = null;
        shapes = null;
      }
    }

    CellShape shape(TerrainCells cells, int x, int y, int z, int generation) {
      if (shapes == null) {
        cells.describe(x, y, z, scratch.reset());
        return scratch;
      }
      long key = pack(x, y, z);
      int slot = slot(key);
      CellShape shape = shapes[slot];
      if (generations[slot] == generation && keys[slot] == key) return shape;
      cells.describe(x, y, z, shape.reset());
      keys[slot] = key;
      generations[slot] = generation;
      return shape;
    }

    static long pack(int x, int y, int z) {
      return ((long) x << 38) ^ ((long) (z & 0x3ffffff) << 12) ^ (y & 0xfff);
    }

    static int slot(long key) {
      long h = key * 0x9E3779B97F4A7C15L;
      return (int) (h >>> 55) & (CACHE_SLOTS - 1);
    }
  }
}
