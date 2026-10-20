package dev.bluevista.craftq3.mc.hybrid.building;

import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which block faces the BSP's solid brushes support, precomputed once per map placement.
 *
 * <p>Only an outward brush face lying on a block boundary can support a block (a floor half a block
 * below the grid is a gap, not a foundation), so the index takes every axial side of every solid
 * world brush, cuts it to the brush's other planes, keeps it when its plane maps to an integral
 * Minecraft coordinate, and computes for each block face it overlaps whether the union of such
 * faces covers the {@link SupportShape}s. Player clip (without {@code CONTENTS_SOLID}) and patches
 * never support. Cells only partly covered keep their polygons so a query can combine them with the
 * block's own face shape.
 *
 * <p>Immutable after construction; lookups are lock-free and allocation-free.
 */
final class FaceSupportIndex {
  static final int FULL_BIT = 1;
  static final int CENTER_BIT = 2;
  static final int RIGID_BIT = 4;
  static final int TOUCHED_BIT = 8;

  private static final double ALIGN_EPSILON = 1e-4;
  private static final double AXIAL_EPSILON = 1e-6;
  private static final double CORNER_EPSILON = 1e-7;
  private static final double HUGE = 1 << 20;

  private final int originX;
  private final int originY;
  private final int originZ;
  private final long[] keys;
  private final int[] slots;
  private final byte[] flags;
  private final int[] polygonFrom;
  private final int[] polygonIds;
  private final double[][] polygons;
  private final int alignedFaces;

  private FaceSupportIndex(
      CoordinateTransform transform,
      long[] keys,
      int[] slots,
      byte[] flags,
      int[] polygonFrom,
      int[] polygonIds,
      double[][] polygons,
      int alignedFaces) {
    this.originX = (int) transform.originX();
    this.originY = (int) transform.originY();
    this.originZ = (int) transform.originZ();
    this.keys = keys;
    this.slots = slots;
    this.flags = flags;
    this.polygonFrom = polygonFrom;
    this.polygonIds = polygonIds;
    this.polygons = polygons;
    this.alignedFaces = alignedFaces;
  }

  /** Brush faces on block boundaries that were indexed. */
  int alignedFaces() {
    return alignedFaces;
  }

  /** Block faces with any BSP support. */
  int touchedFaces() {
    return flags.length;
  }

  /** Block faces fully covered by the BSP. */
  int fullFaces() {
    int n = 0;
    for (byte f : flags) if ((f & FULL_BIT) != 0) n++;
    return n;
  }

  /** The support bits of block {@code (x, y, z)}'s {@code face}, or 0. */
  int bits(int x, int y, int z, BlockFace face) {
    int dense = find(key(x - originX, y - originY, z - originZ, face));
    return dense < 0 ? 0 : flags[dense];
  }

  static int bit(SupportShape shape) {
    return switch (shape) {
      case FULL -> FULL_BIT;
      case CENTER -> CENTER_BIT;
      case RIGID -> RIGID_BIT;
    };
  }

  /**
   * Whether the BSP's faces together with the block's own face rectangles {@code host[0 .. 4 *
   * hostCount)} ({@code u0, v0, u1, v1}, block-local) cover {@code shape} on {@code face}.
   */
  boolean supports(
      int x, int y, int z, BlockFace face, SupportShape shape, double[] host, int hostCount) {
    int dense = find(key(x - originX, y - originY, z - originZ, face));
    int bits = dense < 0 ? 0 : flags[dense];
    if ((bits & bit(shape)) != 0) return true;
    if (hostCount == 0) return false;
    // Block-local: the cell's corner in face coordinates.
    int cu = component(face.uAxis(), x, y, z);
    int cv = component(face.vAxis(), x, y, z);
    double[] rect = new double[4];
    for (int r = 0; r < shape.rectangles(); r++) {
      shape.rectangle(face, r, rect);
      if (!(rect[0] < rect[2] && rect[1] < rect[3])) continue;
      FaceCoverage coverage = new FaceCoverage(rect[0], rect[1], rect[2], rect[3]);
      for (int h = 0; h < hostCount; h++) {
        double u0 = host[h * 4];
        double v0 = host[h * 4 + 1];
        double u1 = host[h * 4 + 2];
        double v1 = host[h * 4 + 3];
        if (u0 < u1 && v0 < v1) coverage.subtractRect(u0, v0, u1, v1);
      }
      if (dense >= 0 && (bits & TOUCHED_BIT) != 0)
        for (int p = polygonFrom[dense]; p < polygonFrom[dense + 1] && !coverage.covered(); p++) {
          double[] planes = local(polygons[polygonIds[p]], cu, cv);
          coverage.subtract(planes, planes.length / 3);
        }
      if (!coverage.covered()) return false;
    }
    return true;
  }

  /** Half-planes {@code a u + b v <= c} moved so the cell corner is the origin. */
  private static double[] local(double[] planes, int cu, int cv) {
    double[] out = planes.clone();
    for (int i = 0; i < out.length; i += 3) out[i + 2] -= out[i] * cu + out[i + 1] * cv;
    return out;
  }

  // ---- table ---------------------------------------------------------------------------------

  /** Packs origin-relative block coordinates (each within +-2^20) and a face. */
  static long key(int x, int y, int z, BlockFace face) {
    return ((long) (x & 0x1fffff) << 43)
        | ((long) (y & 0x1fffff) << 22)
        | ((long) (z & 0x7ffff) << 3)
        | face.ordinal();
  }

  private int find(long key) {
    int mask = keys.length - 1;
    int i = mix(key) & mask;
    while (true) {
      long k = keys[i];
      if (k == 0) return -1;
      if (k == key + 1) return slots[i];
      i = (i + 1) & mask;
    }
  }

  private static int mix(long key) {
    long h = key * 0x9E3779B97F4A7C15L;
    return (int) (h ^ (h >>> 29));
  }

  // ---- construction --------------------------------------------------------------------------

  static FaceSupportIndex build(BspMap map, CoordinateTransform transform) {
    if (!transform.gridAligned())
      throw new IllegalArgumentException("Face support needs a block-aligned origin");
    List<double[]> polygons = new ArrayList<>();
    Map<Long, List<Integer>> cells = new HashMap<>();
    int aligned = 0;
    BspMap.Planes planes = map.planes();
    BspMap.Brushes brushes = map.brushes();
    BspMap.BrushSides sides = map.brushSides();
    List<BspMap.Texture> textures = map.textures();
    if (map.models().isEmpty()) return empty(transform);
    BspMap.Model world = map.models().getFirst();
    double s = transform.unitsPerBlock();
    for (int b = world.firstBrush(); b < world.firstBrush() + world.brushCount(); b++) {
      int texture = brushes.texture(b);
      int contents =
          texture >= 0 && texture < textures.size() ? textures.get(texture).contents() : 0;
      if ((contents & Contents.SOLID) == 0) continue;
      int first = brushes.firstSide(b);
      int count = brushes.sideCount(b);
      for (int side = first; side < first + count; side++) {
        int plane = sides.plane(side);
        int qa = axialAxis(planes, plane);
        if (qa < 0) continue;
        double sign = Math.signum(planes.normal(plane, qa));
        double c = sign * planes.distance(plane);
        // Q3 in-plane axes and the face polygon in them.
        int qu = qa == 0 ? 1 : 0;
        int qv = qa == 2 ? 1 : 2;
        double[] polygon = {-HUGE, -HUGE, HUGE, -HUGE, HUGE, HUGE, -HUGE, HUGE};
        boolean empty = false;
        for (int other = first; other < first + count && !empty; other++) {
          if (other == side) continue;
          int op = sides.plane(other);
          double nu = planes.normal(op, qu);
          double nv = planes.normal(op, qv);
          double rhs = planes.distance(op) - planes.normal(op, qa) * c;
          if (Math.abs(nu) < AXIAL_EPSILON && Math.abs(nv) < AXIAL_EPSILON) {
            if (rhs < -ALIGN_EPSILON) empty = true;
            continue;
          }
          polygon = FaceCoverage.clip(polygon, nu, nv, rhs, true);
          if (FaceCoverage.area(polygon) <= 1e-6) empty = true;
        }
        if (empty) continue;
        // Into Minecraft: axis, outward sign and plane coordinate.
        int ma = qa == 0 ? 0 : qa == 1 ? 2 : 1;
        int mSign = (int) (qa == 1 ? -sign : sign);
        double k =
            switch (ma) {
              case 0 -> transform.minecraftX(c);
              case 1 -> transform.minecraftY(c);
              default -> transform.minecraftZ(c);
            };
        double rounded = Math.rint(k);
        if (Math.abs(k - rounded) > ALIGN_EPSILON / s) continue;
        aligned++;
        BlockFace face = BlockFace.of(ma, mSign);
        int cellAlong = mSign > 0 ? (int) rounded - 1 : (int) rounded;
        double[] mc = toMinecraft(polygon, qa, qu, qv, c, transform, face);
        int id = polygons.size();
        double[] halfPlanes = halfPlanes(mc);
        if (halfPlanes == null) continue;
        polygons.add(halfPlanes);
        addCells(cells, mc, id, face, cellAlong, transform);
      }
    }
    return assemble(transform, polygons, cells, aligned);
  }

  private static FaceSupportIndex empty(CoordinateTransform transform) {
    return assemble(transform, List.of(), Map.of(), 0);
  }

  private static int axialAxis(BspMap.Planes planes, int plane) {
    int axis = -1;
    for (int a = 0; a < 3; a++) {
      float n = planes.normal(plane, a);
      if (Math.abs(Math.abs(n) - 1) < AXIAL_EPSILON) {
        if (axis >= 0) return -1;
        axis = a;
      } else if (Math.abs(n) > AXIAL_EPSILON) return -1;
    }
    return axis;
  }

  /** The polygon in the face's Minecraft (u, v) coordinates. */
  private static double[] toMinecraft(
      double[] polygon, int qa, int qu, int qv, double c, CoordinateTransform t, BlockFace face) {
    double[] out = new double[polygon.length];
    double[] q = new double[3];
    double[] m = new double[3];
    for (int i = 0; i < polygon.length; i += 2) {
      q[qa] = c;
      q[qu] = polygon[i];
      q[qv] = polygon[i + 1];
      t.toMinecraft(q[0], q[1], q[2], m);
      out[i] = m[face.uAxis()];
      out[i + 1] = m[face.vAxis()];
    }
    return out;
  }

  /** The convex polygon's edges as inward half-planes {@code a u + b v <= c}, or null. */
  private static double[] halfPlanes(double[] polygon) {
    int n = polygon.length / 2;
    if (n < 3) return null;
    double signed = 0;
    for (int i = 0; i < n; i++) {
      int j = (i + 1) % n;
      signed += polygon[i * 2] * polygon[j * 2 + 1] - polygon[j * 2] * polygon[i * 2 + 1];
    }
    if (Math.abs(signed) < 1e-12) return null;
    double orientation = Math.signum(signed);
    double[] out = new double[n * 3];
    int count = 0;
    for (int i = 0; i < n; i++) {
      int j = (i + 1) % n;
      double du = polygon[j * 2] - polygon[i * 2];
      double dv = polygon[j * 2 + 1] - polygon[i * 2 + 1];
      double length = Math.hypot(du, dv);
      if (length < 1e-12) continue;
      // For a counter-clockwise polygon the interior is to the left; the outward normal is (dv,
      // -du).
      double a = orientation * dv / length;
      double b = -orientation * du / length;
      out[count * 3] = a;
      out[count * 3 + 1] = b;
      out[count * 3 + 2] = a * polygon[i * 2] + b * polygon[i * 2 + 1];
      count++;
    }
    return count < 3 ? null : Arrays.copyOf(out, count * 3);
  }

  private static void addCells(
      Map<Long, List<Integer>> cells,
      double[] polygon,
      int id,
      BlockFace face,
      int cellAlong,
      CoordinateTransform t) {
    double u0 = Double.POSITIVE_INFINITY;
    double v0 = Double.POSITIVE_INFINITY;
    double u1 = Double.NEGATIVE_INFINITY;
    double v1 = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < polygon.length; i += 2) {
      u0 = Math.min(u0, polygon[i]);
      u1 = Math.max(u1, polygon[i]);
      v0 = Math.min(v0, polygon[i + 1]);
      v1 = Math.max(v1, polygon[i + 1]);
    }
    int ox = (int) t.originX();
    int oy = (int) t.originY();
    int oz = (int) t.originZ();
    for (int u = (int) Math.floor(u0); u < Math.ceil(u1); u++)
      for (int v = (int) Math.floor(v0); v < Math.ceil(v1); v++) {
        double[] piece = polygon;
        piece = FaceCoverage.clip(piece, -1, 0, -u, true);
        piece = FaceCoverage.clip(piece, 1, 0, u + 1, true);
        piece = FaceCoverage.clip(piece, 0, -1, -v, true);
        piece = FaceCoverage.clip(piece, 0, 1, v + 1, true);
        if (FaceCoverage.area(piece) <= 1e-9) continue;
        int x;
        int y;
        int z;
        switch (face.axis()) {
          case 0 -> {
            x = cellAlong;
            y = u;
            z = v;
          }
          case 1 -> {
            x = u;
            y = cellAlong;
            z = v;
          }
          default -> {
            x = u;
            y = v;
            z = cellAlong;
          }
        }
        long key = key(x - ox, y - oy, z - oz, face);
        cells.computeIfAbsent(key, k -> new ArrayList<>(2)).add(id);
      }
  }

  private static FaceSupportIndex assemble(
      CoordinateTransform transform,
      List<double[]> polygons,
      Map<Long, List<Integer>> cells,
      int aligned) {
    int n = cells.size();
    int capacity = Integer.highestOneBit(Math.max(4, n * 2 + 1)) * 2;
    long[] keys = new long[capacity];
    int[] slots = new int[capacity];
    byte[] flags = new byte[n];
    int[] from = new int[n + 1];
    List<Integer> ids = new ArrayList<>();
    int dense = 0;
    double[] rect = new double[4];
    for (Map.Entry<Long, List<Integer>> entry : cells.entrySet()) {
      long key = entry.getKey();
      BlockFace face = BlockFace.ofOrdinal((int) (key & 7));
      List<Integer> list = entry.getValue();
      // Cell corner in face coordinates, recovered from the key.
      int x = (int) (key >> 43);
      int y = (int) ((key << 21) >> 43);
      int z = (int) ((key << 42) >> 45);
      int wx = x + (int) transform.originX();
      int wy = y + (int) transform.originY();
      int wz = z + (int) transform.originZ();
      int cu = component(face.uAxis(), wx, wy, wz);
      int cv = component(face.vAxis(), wx, wy, wz);
      int bits = TOUCHED_BIT;
      boolean full = false;
      for (int id : list)
        if (containsSquare(polygons.get(id), cu, cv)) {
          full = true;
          break;
        }
      for (SupportShape shape : SupportShape.values()) {
        boolean covered = full;
        if (!covered) {
          covered = true;
          for (int r = 0; r < shape.rectangles() && covered; r++) {
            shape.rectangle(face, r, rect);
            FaceCoverage coverage =
                new FaceCoverage(cu + rect[0], cv + rect[1], cu + rect[2], cv + rect[3]);
            for (int id : list) {
              double[] planes = polygons.get(id);
              coverage.subtract(planes, planes.length / 3);
              if (coverage.covered()) break;
            }
            covered = coverage.covered();
          }
        }
        if (covered) bits |= bit(shape);
      }
      flags[dense] = (byte) bits;
      from[dense] = ids.size();
      if ((bits & FULL_BIT) == 0) ids.addAll(list);
      int mask = capacity - 1;
      int i = mix(key) & mask;
      while (keys[i] != 0) i = (i + 1) & mask;
      keys[i] = key + 1;
      slots[i] = dense;
      dense++;
    }
    from[n] = ids.size();
    int[] polygonIds = new int[ids.size()];
    for (int i = 0; i < polygonIds.length; i++) polygonIds[i] = ids.get(i);
    return new FaceSupportIndex(
        transform,
        keys,
        slots,
        flags,
        from,
        polygonIds,
        polygons.toArray(double[][]::new),
        aligned);
  }

  private static int component(int axis, int x, int y, int z) {
    return axis == 0 ? x : axis == 1 ? y : z;
  }

  private static boolean containsSquare(double[] planes, int u, int v) {
    for (int i = 0; i < planes.length; i += 3) {
      double a = planes[i];
      double b = planes[i + 1];
      double c = planes[i + 2] + CORNER_EPSILON;
      if (a * u + b * v > c
          || a * (u + 1) + b * v > c
          || a * u + b * (v + 1) > c
          || a * (u + 1) + b * (v + 1) > c) return false;
    }
    return true;
  }
}
