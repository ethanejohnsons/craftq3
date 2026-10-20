package dev.bluevista.craftq3.engine.collision;

/**
 * Convex brushes in primitive arrays: planes ({@code [nx, ny, nz, dist]} with type and signbits),
 * sides (plane and surface flags) and per-brush side ranges, contents and bounds. The map's table
 * is immutable after construction; a {@link TraceWork} owns small mutable tables for temporary
 * boxes.
 */
final class BrushTable {
  /** Bounds of a brush side that is not bounded on some axis. */
  static final float UNBOUNDED = 1 << 20;

  final float[] planes;
  final byte[] planeTypes;
  final byte[] planeSigns;
  final int[] sidePlanes;
  final int[] sideFlags;
  final int[] firstSide;
  final int[] sideCount;
  final int[] contents;
  final float[] bounds;

  /**
   * Whether a brush's first six sides are the axial planes {@code -x, +x, -y, +y, -z, +z} that the
   * map compiler always emits first. Their test is then exactly the bounds test, so position tests
   * skip them as the original does.
   */
  final boolean[] axialFirst;

  BrushTable(int planeCount, int sides, int brushes) {
    planes = new float[planeCount * 4];
    planeTypes = new byte[planeCount];
    planeSigns = new byte[planeCount];
    sidePlanes = new int[sides];
    sideFlags = new int[sides];
    firstSide = new int[brushes];
    sideCount = new int[brushes];
    contents = new int[brushes];
    bounds = new float[brushes * 6];
    axialFirst = new boolean[brushes];
  }

  int count() {
    return contents.length;
  }

  void setPlane(int plane, float nx, float ny, float nz, float dist) {
    int base = plane * 4;
    planes[base] = nx;
    planes[base + 1] = ny;
    planes[base + 2] = nz;
    planes[base + 3] = dist;
    planeTypes[plane] = (byte) planeType(nx, ny, nz);
    planeSigns[plane] = (byte) signbits(nx, ny, nz);
  }

  /** {@code PlaneTypeForNormal}: the axis whose component is exactly 1, else non-axial. */
  static int planeType(float nx, float ny, float nz) {
    if (nx == 1) return 0;
    if (ny == 1) return 1;
    if (nz == 1) return 2;
    return Trace.PLANE_NON_AXIAL;
  }

  static int signbits(float nx, float ny, float nz) {
    return (nx < 0 ? 1 : 0) | (ny < 0 ? 2 : 0) | (nz < 0 ? 4 : 0);
  }

  /** Computes bounds and {@link #axialFirst} for {@code brush} from its sides. */
  void bound(int brush) {
    int first = firstSide[brush];
    int count = sideCount[brush];
    int base = brush * 6;
    boolean canonical = count >= 6;
    for (int i = 0; i < 6 && canonical; i++) {
      int plane = sidePlanes[first + i] * 4;
      int axis = i >> 1;
      float sign = (i & 1) == 0 ? -1 : 1;
      for (int k = 0; k < 3; k++)
        if (planes[plane + k] != (k == axis ? sign : 0)) {
          canonical = false;
          break;
        }
    }
    axialFirst[brush] = canonical;
    for (int axis = 0; axis < 3; axis++) {
      bounds[base + axis] = -UNBOUNDED;
      bounds[base + 3 + axis] = UNBOUNDED;
    }
    if (canonical) {
      for (int axis = 0; axis < 3; axis++) {
        bounds[base + axis] = -planes[sidePlanes[first + axis * 2] * 4 + 3];
        bounds[base + 3 + axis] = planes[sidePlanes[first + axis * 2 + 1] * 4 + 3];
      }
      return;
    }
    // Brushes from other tools may lack or reorder the axial sides: bound by what is present.
    boolean[] seen = new boolean[6];
    for (int i = 0; i < count; i++) {
      int plane = sidePlanes[first + i] * 4;
      for (int axis = 0; axis < 3; axis++) {
        float n = planes[plane + axis];
        if (Math.abs(n) != 1) continue;
        float d = planes[plane + 3];
        if (n > 0) {
          if (!seen[3 + axis] || d < bounds[base + 3 + axis]) bounds[base + 3 + axis] = d;
          seen[3 + axis] = true;
        } else {
          if (!seen[axis] || -d > bounds[base + axis]) bounds[base + axis] = -d;
          seen[axis] = true;
        }
      }
    }
  }

  /**
   * A one-brush table for an axis-aligned box: sides in the canonical {@code -x, +x, -y, +y, -z,
   * +z} order, plane types as the original temporary box model reports them (3 + axis for the
   * negative planes) and no surface flags.
   */
  static BrushTable box() {
    BrushTable table = new BrushTable(6, 6, 1);
    for (int i = 0; i < 6; i++) table.sidePlanes[i] = i;
    table.sideCount[0] = 6;
    table.axialFirst[0] = true;
    table.setBox(new float[3], new float[3], Contents.BODY, 0);
    return table;
  }

  /** Repositions the single box of a {@link #box()} table. */
  void setBox(float[] mins, float[] maxs, int boxContents, int surfaceFlags) {
    setBox(mins[0], mins[1], mins[2], maxs[0], maxs[1], maxs[2], boxContents, surfaceFlags);
  }

  void setBox(
      float minX,
      float minY,
      float minZ,
      float maxX,
      float maxY,
      float maxZ,
      int boxContents,
      int surfaceFlags) {
    for (int axis = 0; axis < 3; axis++) {
      float min = axis == 0 ? minX : axis == 1 ? minY : minZ;
      float max = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
      int negative = axis * 2;
      int positive = negative + 1;
      planes[negative * 4] = 0;
      planes[negative * 4 + 1] = 0;
      planes[negative * 4 + 2] = 0;
      planes[negative * 4 + axis] = -1;
      planes[negative * 4 + 3] = -min;
      planeTypes[negative] = (byte) (Trace.PLANE_NON_AXIAL + axis);
      planeSigns[negative] = (byte) (1 << axis);
      planes[positive * 4] = 0;
      planes[positive * 4 + 1] = 0;
      planes[positive * 4 + 2] = 0;
      planes[positive * 4 + axis] = 1;
      planes[positive * 4 + 3] = max;
      planeTypes[positive] = (byte) axis;
      planeSigns[positive] = 0;
      bounds[axis] = min;
      bounds[3 + axis] = max;
      sideFlags[negative] = surfaceFlags;
      sideFlags[positive] = surfaceFlags;
    }
    contents[0] = boxContents;
  }
}
