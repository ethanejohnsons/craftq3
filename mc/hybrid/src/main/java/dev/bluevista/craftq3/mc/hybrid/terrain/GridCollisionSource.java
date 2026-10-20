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
 * <p>Candidate cells: exactly the cells the swept box touches (padded by the clip epsilon and,
 * below, by Minecraft's shape rise), from the trace's start, end and box that {@link TraceClipper}
 * exposes. Cells are visited layer by layer (Minecraft y, then z, then x): for each layer the part
 * of the sweep that overlaps it is a parameter interval, narrowed again per row, so no cell outside
 * the swept box is described, whatever the trace's length or box size.
 *
 * <p>Thread-safe and allocation-free after each thread's first trace: per-thread scratch, and an
 * optional per-thread cell cache invalidated by {@link #invalidate()} (call it whenever blocks may
 * have changed, e.g. once per Minecraft tick).
 */
public final class GridCollisionSource implements CollisionSource {
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
    // Local block coordinates: L0 = MC x, L1 = MC y (Quake z), L2 = MC z (Quake -y). The box's
    // centre moves from a to b; a cell c on axis k is touched while the centre's coordinate is in
    // [c - below[k], c + above[k]] (the box, the clip epsilon and, on L1, the shapes' rise).
    double epsilon = EPSILON * inverseScale;
    double[] a = w.a;
    double[] d = w.d;
    double[] below = w.below;
    double[] above = w.above;
    for (int k = 0; k < 3; k++) {
      int q = QUAKE_AXIS[k];
      double sign = k == 2 ? -1 : 1;
      a[k] = sign * clipper.start(q) * inverseScale;
      d[k] = sign * clipper.end(q) * inverseScale - a[k];
      double lo = (k == 2 ? -clipper.maxs(q) : clipper.mins(q)) * inverseScale;
      double hi = (k == 2 ? -clipper.mins(q) : clipper.maxs(q)) * inverseScale;
      below[k] = hi + epsilon;
      above[k] = 1 + (k == 1 ? rise : 0) - lo + epsilon;
      if (Math.abs(d[k]) + below[k] + above[k] > MAX_SPAN_BLOCKS) {
        LOG.warnOnce("terrain-span", "A trace spans more than " + MAX_SPAN_BLOCKS + " blocks");
        return;
      }
    }
    int yFirst = Math.max(first(w, 1, 0, 1), minY - originY);
    int yLast = Math.min(last(w, 1, 0, 1), maxY - 1 - originY);
    for (int y = yFirst; y <= yLast; y++) {
      if (!overlap(w, 1, y, 0, 1)) continue;
      double ty0 = w.t0;
      double ty1 = w.t1;
      int zLast = last(w, 2, ty0, ty1);
      for (int z = first(w, 2, ty0, ty1); z <= zLast; z++) {
        if (!overlap(w, 2, z, ty0, ty1)) continue;
        double tz0 = w.t0;
        double tz1 = w.t1;
        int xLast = last(w, 0, tz0, tz1);
        for (int x = first(w, 0, tz0, tz1); x <= xLast; x++) clipCell(clipper, w, x, y, z);
      }
    }
  }

  /** The Quake axis of each local axis. */
  private static final int[] QUAKE_AXIS = {0, 2, 1};

  /** The lowest cell on local {@code axis} that the sweep touches while {@code t0 <= t <= t1}. */
  private static int first(Work w, int axis, double t0, double t1) {
    double p = w.a[axis] + w.d[axis] * (w.d[axis] >= 0 ? t0 : t1);
    return (int) Math.ceil(p - w.above[axis]);
  }

  /** The highest cell on local {@code axis} that the sweep touches while {@code t0 <= t <= t1}. */
  private static int last(Work w, int axis, double t0, double t1) {
    double p = w.a[axis] + w.d[axis] * (w.d[axis] >= 0 ? t1 : t0);
    return floor(p + w.below[axis]);
  }

  /**
   * Narrows {@code t0 .. t1} to the part of the sweep touching cell {@code c} on local {@code
   * axis}, into {@code w.t0 .. w.t1}; false when that part is empty.
   */
  private static boolean overlap(Work w, int axis, int c, double t0, double t1) {
    double d = w.d[axis];
    if (d != 0) {
      double enter = (c - w.below[axis] - w.a[axis]) / d;
      double leave = (c + w.above[axis] - w.a[axis]) / d;
      if (d < 0) {
        double swap = enter;
        enter = leave;
        leave = swap;
      }
      t0 = Math.max(t0, enter);
      t1 = Math.min(t1, leave);
    }
    w.t0 = t0;
    w.t1 = t1;
    return t0 <= t1;
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
    /** The sweep in local coordinates: start, displacement and the touch margins per axis. */
    final double[] a = new double[3];

    final double[] d = new double[3];
    final double[] below = new double[3];
    final double[] above = new double[3];

    /** The parameter interval {@link #overlap} narrowed to. */
    double t0;

    double t1;
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
