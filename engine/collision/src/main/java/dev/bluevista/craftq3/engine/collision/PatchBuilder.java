package dev.bluevista.craftq3.engine.collision;

import java.util.Arrays;

/**
 * Turns a biquadratic patch's control grid into collision facets, following Quake III's documented
 * approach:
 *
 * <ol>
 *   <li>Refine each column triple (on-curve, control, on-curve) until the curve's midpoint lies
 *       within {@link #SUBDIVIDE_DISTANCE} of the chord, splitting with de Casteljau midpoints and
 *       collapsing control columns that are flat enough. Columns that collapse to the same points
 *       (within {@link #POINT_EPSILON}) are removed. The grid is then transposed and the rows
 *       refined the same way, so every remaining point lies on the curve.
 *   <li>Give each grid cell's two triangles a plane from a per-patch plane list, sharing planes
 *       that every vertex lies within {@link #PLANE_TRI_EPSILON} of.
 *   <li>Make each cell one quad facet when both triangles share a plane, otherwise two triangle
 *       facets. Borders are the neighbouring cells' planes, or a plane through the edge standing
 *       perpendicular to the surface where there is no distinct neighbour (grid edges, flat
 *       neighbours). Each border is flagged inward or outward from the facet's own vertices.
 *   <li>Discard facets whose borders do not close them in, then add bevels: the six axial planes of
 *       the facet's winding and the planes through each non-axial winding edge that keep the whole
 *       winding behind them, and finally the reversed surface plane so the facet has a back.
 * </ol>
 *
 * Builders are not thread-safe; one instance can build many patches.
 */
final class PatchBuilder {
  static final int MAX_GRID_SIZE = 129;
  static final float SUBDIVIDE_DISTANCE = 16;
  static final float POINT_EPSILON = 0.1f;
  static final float WRAP_POINT_EPSILON = 0.1f;
  static final float PLANE_TRI_EPSILON = 0.1f;
  static final float NORMAL_EPSILON = 0.0001f;
  static final float DIST_EPSILON = 0.02f;
  static final float CHOP_EPSILON = 0.1f;
  static final float BEVEL_EPSILON = 0.1f;
  static final int MAX_FACETS = 1024;
  static final int MAX_PATCH_PLANES = 4096;

  /** How far above an edge the third point of its perpendicular border plane is placed. */
  private static final float EDGE_PLANE_HEIGHT = 4;

  private static final int TOP = 0;
  private static final int RIGHT = 1;
  private static final int BOTTOM = 2;
  private static final int LEFT = 3;
  private static final int DIAGONAL_OUT_OF_FIRST = 4;
  private static final int DIAGONAL_OUT_OF_SECOND = 5;

  /** Signals a patch that exceeds a facet or plane budget; the patch is skipped. */
  static final class BudgetException extends RuntimeException {
    private static final long serialVersionUID = 1;

    BudgetException(String message) {
      super(message, null, false, false);
    }
  }

  private float[] grid = new float[MAX_GRID_SIZE * MAX_GRID_SIZE * 3];
  private final float[] swap = new float[MAX_GRID_SIZE * MAX_GRID_SIZE * 3];
  private int width;
  private int height;
  private boolean wrapWidth;
  private boolean wrapHeight;
  private boolean truncated;

  private final float[] planes = new float[MAX_PATCH_PLANES * 4];
  private int planeCount;
  private int[] gridPlanes = new int[0];

  private int facetCount;
  private int[] facetSurface = new int[64];
  private int[] facetFirst = new int[64];
  private int[] facetBorders = new int[64];
  private int borderTotal;
  private int[] borderPlane = new int[512];
  private boolean[] borderInward = new boolean[512];

  private final int[] borders = new int[64];
  private final boolean[] inward = new boolean[64];
  private int borderCount;
  private final float[] scratchPlane = new float[4];

  /** The number of grid columns and rows after refinement (for tests and statistics). */
  int refinedWidth() {
    return width;
  }

  int refinedHeight() {
    return height;
  }

  /** True when refinement stopped at the grid size limit before reaching the tolerance. */
  boolean truncated() {
    return truncated;
  }

  /**
   * Builds the facets of a {@code w} by {@code h} control grid ({@code points} row-major, three
   * floats per point) into {@code out}.
   *
   * @return the number of facets added, 0 when the patch has no collision surface
   * @throws IllegalArgumentException when the grid size is invalid for a quadratic patch
   * @throws BudgetException when the patch needs more facets or planes than allowed
   */
  int build(
      int w, int h, float[] points, int face, int contents, int flags, PatchTable.Builder out) {
    if (w < 3 || h < 3 || (w & 1) == 0 || (h & 1) == 0)
      throw new IllegalArgumentException("Patch grid " + w + "x" + h + " is not a quadratic mesh");
    if (w > MAX_GRID_SIZE || h > MAX_GRID_SIZE)
      throw new IllegalArgumentException("Patch grid " + w + "x" + h + " exceeds " + MAX_GRID_SIZE);
    if (points.length < w * h * 3) throw new IllegalArgumentException("Too few patch points");
    width = w;
    height = h;
    truncated = false;
    wrapWidth = false;
    wrapHeight = false;
    for (int i = 0; i < w; i++)
      for (int j = 0; j < h; j++) System.arraycopy(points, (j * w + i) * 3, grid, at(i, j), 3);
    detectWrapWidth();
    refineColumns();
    removeDegenerateColumns();
    transpose();
    detectWrapWidth();
    refineColumns();
    removeDegenerateColumns();

    float[] bounds = {
      Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
      -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
    };
    for (int i = 0; i < width; i++)
      for (int j = 0; j < height; j++)
        for (int axis = 0; axis < 3; axis++) {
          float v = grid[at(i, j) + axis];
          bounds[axis] = Math.min(bounds[axis], v);
          bounds[3 + axis] = Math.max(bounds[3 + axis], v);
        }
    // Expanded by a unit so the cheap rejection never cuts into a facet's epsilon zone.
    for (int axis = 0; axis < 3; axis++) {
      bounds[axis] -= 1;
      bounds[3 + axis] += 1;
    }

    planeCount = 0;
    facetCount = 0;
    borderTotal = 0;
    makeFacets();
    if (facetCount == 0) return 0;
    out.begin(face, contents, flags);
    for (int p = 0; p < planeCount; p++)
      out.plane(planes[p * 4], planes[p * 4 + 1], planes[p * 4 + 2], planes[p * 4 + 3]);
    int[] b = new int[64];
    boolean[] in = new boolean[64];
    for (int f = 0; f < facetCount; f++) {
      int n = facetBorders[f];
      if (n > b.length) {
        b = new int[n];
        in = new boolean[n];
      }
      System.arraycopy(borderPlane, facetFirst[f], b, 0, n);
      System.arraycopy(borderInward, facetFirst[f], in, 0, n);
      out.facet(facetSurface[f], b, in, n);
    }
    out.end(bounds);
    return facetCount;
  }

  // ---- grid refinement ------------------------------------------------------------------------

  private static int at(int column, int row) {
    return (column * MAX_GRID_SIZE + row) * 3;
  }

  private void detectWrapWidth() {
    wrapWidth = true;
    for (int j = 0; j < height && wrapWidth; j++) {
      int a = at(0, j);
      int b = at(width - 1, j);
      for (int axis = 0; axis < 3; axis++)
        if (Math.abs(grid[a + axis] - grid[b + axis]) > WRAP_POINT_EPSILON) {
          wrapWidth = false;
          break;
        }
    }
  }

  private boolean needsRefinement(int a, int b, int c) {
    float squared = 0;
    for (int axis = 0; axis < 3; axis++) {
      float pa = grid[a + axis];
      float pb = grid[b + axis];
      float pc = grid[c + axis];
      float curve = 0.5f * (0.5f * (pa + pb) + 0.5f * (pb + pc));
      float chord = 0.5f * (pa + pc);
      float d = curve - chord;
      squared += d * d;
    }
    return (float) Math.sqrt(squared) >= SUBDIVIDE_DISTANCE;
  }

  private void refineColumns() {
    int i = 0;
    while (i < width - 2) {
      boolean flat = true;
      for (int j = 0; j < height; j++)
        if (needsRefinement(at(i, j), at(i + 1, j), at(i + 2, j))) {
          flat = false;
          break;
        }
      if (!flat && width + 2 > MAX_GRID_SIZE) {
        truncated = true;
        flat = true;
      }
      if (flat) {
        // The chord is close enough: drop the control column and move to the next segment.
        removeColumn(i + 1);
        i++;
        continue;
      }
      for (int j = 0; j < height; j++) {
        for (int k = width - 1; k > i + 1; k--)
          System.arraycopy(grid, at(k, j), grid, at(k + 2, j), 3);
        int prev = at(i, j);
        int mid = at(i + 1, j);
        int next = at(i + 4, j);
        for (int axis = 0; axis < 3; axis++) {
          float a = grid[prev + axis];
          float b = grid[mid + axis];
          float c = grid[next + axis];
          float left = 0.5f * (a + b);
          float right = 0.5f * (b + c);
          grid[at(i + 1, j) + axis] = left;
          grid[at(i + 3, j) + axis] = right;
          grid[at(i + 2, j) + axis] = 0.5f * (left + right);
        }
      }
      width += 2;
      // The new control column at i + 1 may itself need refinement: do not advance.
    }
  }

  private void removeColumn(int column) {
    for (int j = 0; j < height; j++)
      for (int k = column + 1; k < width; k++)
        System.arraycopy(grid, at(k, j), grid, at(k - 1, j), 3);
    width--;
  }

  private void removeDegenerateColumns() {
    for (int i = 0; i < width - 1; i++) {
      boolean same = true;
      for (int j = 0; j < height && same; j++) {
        int a = at(i, j);
        int b = at(i + 1, j);
        for (int axis = 0; axis < 3; axis++)
          if (Math.abs(grid[a + axis] - grid[b + axis]) > POINT_EPSILON) {
            same = false;
            break;
          }
      }
      if (!same) continue;
      removeColumn(i + 1);
      i--;
    }
  }

  private void transpose() {
    for (int i = 0; i < width; i++)
      for (int j = 0; j < height; j++) System.arraycopy(grid, at(i, j), swap, at(j, i), 3);
    System.arraycopy(swap, 0, grid, 0, grid.length);
    int t = width;
    width = height;
    height = t;
    boolean wrap = wrapWidth;
    wrapWidth = wrapHeight;
    wrapHeight = wrap;
  }

  // ---- planes ---------------------------------------------------------------------------------

  /**
   * The index of the plane through three grid points (normal {@code (p3 - p1) x (p2 - p1)}),
   * reusing an existing plane facing the same way that all three points lie close to; -1 for a
   * degenerate triangle.
   */
  private int findPlane(float[] src1, int o1, float[] src2, int o2, float[] src3, int o3) {
    float d1x = src2[o2] - src1[o1];
    float d1y = src2[o2 + 1] - src1[o1 + 1];
    float d1z = src2[o2 + 2] - src1[o1 + 2];
    float d2x = src3[o3] - src1[o1];
    float d2y = src3[o3 + 1] - src1[o1 + 1];
    float d2z = src3[o3 + 2] - src1[o1 + 2];
    float nx = d2y * d1z - d2z * d1y;
    float ny = d2z * d1x - d2x * d1z;
    float nz = d2x * d1y - d2y * d1x;
    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (length == 0) return -1;
    float inverse = 1 / length;
    nx *= inverse;
    ny *= inverse;
    nz *= inverse;
    float dist = src1[o1] * nx + src1[o1 + 1] * ny + src1[o1 + 2] * nz;
    for (int p = 0; p < planeCount; p++) {
      int base = p * 4;
      float px = planes[base];
      float py = planes[base + 1];
      float pz = planes[base + 2];
      float pd = planes[base + 3];
      if (nx * px + ny * py + nz * pz < 0) continue;
      if (!near(src1, o1, px, py, pz, pd)) continue;
      if (!near(src2, o2, px, py, pz, pd)) continue;
      if (!near(src3, o3, px, py, pz, pd)) continue;
      return p;
    }
    return addPlane(nx, ny, nz, dist);
  }

  private static boolean near(float[] src, int o, float px, float py, float pz, float pd) {
    float d = src[o] * px + src[o + 1] * py + src[o + 2] * pz - pd;
    return d >= -PLANE_TRI_EPSILON && d <= PLANE_TRI_EPSILON;
  }

  private int addPlane(float nx, float ny, float nz, float dist) {
    if (planeCount == MAX_PATCH_PLANES)
      throw new BudgetException("Patch needs more than " + MAX_PATCH_PLANES + " planes");
    int base = planeCount * 4;
    planes[base] = nx;
    planes[base + 1] = ny;
    planes[base + 2] = nz;
    planes[base + 3] = dist;
    return planeCount++;
  }

  /** 0 when {@code plane} equals stored plane {@code p}, 1 when it equals it reversed, else -1. */
  private int compare(int p, float[] plane) {
    int base = p * 4;
    if (Math.abs(planes[base] - plane[0]) < NORMAL_EPSILON
        && Math.abs(planes[base + 1] - plane[1]) < NORMAL_EPSILON
        && Math.abs(planes[base + 2] - plane[2]) < NORMAL_EPSILON
        && Math.abs(planes[base + 3] - plane[3]) < DIST_EPSILON) return 0;
    if (Math.abs(planes[base] + plane[0]) < NORMAL_EPSILON
        && Math.abs(planes[base + 1] + plane[1]) < NORMAL_EPSILON
        && Math.abs(planes[base + 2] + plane[2]) < NORMAL_EPSILON
        && Math.abs(planes[base + 3] + plane[3]) < DIST_EPSILON) return 1;
    return -1;
  }

  /**
   * Finds or adds {@code plane}; returns its index, negated minus one ({@code ~index}) when the
   * stored plane faces the other way.
   */
  private int findOrAddEither(float[] plane) {
    for (int p = 0; p < planeCount; p++) {
      int match = compare(p, plane);
      if (match == 0) return p;
      if (match == 1) return ~p;
    }
    return addPlane(plane[0], plane[1], plane[2], plane[3]);
  }

  private int side(int plane, float[] src, int o) {
    if (plane == -1) return 2;
    int base = plane * 4;
    float d =
        src[o] * planes[base]
            + src[o + 1] * planes[base + 1]
            + src[o + 2] * planes[base + 2]
            - planes[base + 3];
    if (d > PLANE_TRI_EPSILON) return 0;
    if (d < -PLANE_TRI_EPSILON) return 1;
    return 2;
  }

  // ---- facets ---------------------------------------------------------------------------------

  private int cellPlane(int i, int j, int triangle) {
    return gridPlanes[(i * (height - 1) + j) * 2 + triangle];
  }

  /** The plane of a cell triangle, falling back to the other triangle of the cell. */
  private int cellPlaneOrOther(int i, int j, int triangle) {
    int p = cellPlane(i, j, triangle);
    return p != -1 ? p : cellPlane(i, j, 1 - triangle);
  }

  /** A border plane through one edge of a cell, standing perpendicular to the cell's surface. */
  private int edgePlane(int i, int j, int edge) {
    int a;
    int b;
    int surface;
    boolean reversed;
    switch (edge) {
      case TOP -> {
        a = at(i, j);
        b = at(i + 1, j);
        surface = cellPlaneOrOther(i, j, 0);
        reversed = false;
      }
      case BOTTOM -> {
        a = at(i, j + 1);
        b = at(i + 1, j + 1);
        surface = cellPlaneOrOther(i, j, 1);
        reversed = true;
      }
      case LEFT -> {
        a = at(i, j);
        b = at(i, j + 1);
        surface = cellPlaneOrOther(i, j, 1);
        reversed = true;
      }
      case RIGHT -> {
        a = at(i + 1, j);
        b = at(i + 1, j + 1);
        surface = cellPlaneOrOther(i, j, 0);
        reversed = false;
      }
      case DIAGONAL_OUT_OF_FIRST -> {
        a = at(i + 1, j + 1);
        b = at(i, j);
        surface = cellPlaneOrOther(i, j, 0);
        reversed = false;
      }
      case DIAGONAL_OUT_OF_SECOND -> {
        a = at(i, j);
        b = at(i + 1, j + 1);
        surface = cellPlaneOrOther(i, j, 1);
        reversed = false;
      }
      default -> throw new IllegalArgumentException("edge " + edge);
    }
    if (surface == -1) return -1;
    float[] up = scratchPlane;
    for (int axis = 0; axis < 3; axis++)
      up[axis] = grid[a + axis] + EDGE_PLANE_HEIGHT * planes[surface * 4 + axis];
    return reversed ? findPlane(grid, b, grid, a, up, 0) : findPlane(grid, a, grid, b, up, 0);
  }

  private void makeFacets() {
    int cells = (width - 1) * (height - 1);
    if (gridPlanes.length < cells * 2) gridPlanes = new int[cells * 2];
    for (int i = 0; i < width - 1; i++)
      for (int j = 0; j < height - 1; j++) {
        int g = (i * (height - 1) + j) * 2;
        gridPlanes[g] = findPlane(grid, at(i, j), grid, at(i + 1, j), grid, at(i + 1, j + 1));
        gridPlanes[g + 1] = findPlane(grid, at(i + 1, j + 1), grid, at(i, j + 1), grid, at(i, j));
      }
    for (int i = 0; i < width - 1; i++)
      for (int j = 0; j < height - 1; j++) {
        int first = cellPlane(i, j, 0);
        int second = cellPlane(i, j, 1);

        int top = -1;
        if (j > 0) top = cellPlane(i, j - 1, 1);
        else if (wrapHeight) top = cellPlane(i, height - 2, 1);
        if (top == -1 || top == first) top = edgePlane(i, j, TOP);

        int bottom = -1;
        if (j < height - 2) bottom = cellPlane(i, j + 1, 0);
        else if (wrapHeight) bottom = cellPlane(i, 0, 0);
        if (bottom == -1 || bottom == second) bottom = edgePlane(i, j, BOTTOM);

        int left = -1;
        if (i > 0) left = cellPlane(i - 1, j, 0);
        else if (wrapWidth) left = cellPlane(width - 2, j, 0);
        if (left == -1 || left == second) left = edgePlane(i, j, LEFT);

        int right = -1;
        if (i < width - 2) right = cellPlane(i + 1, j, 1);
        else if (wrapWidth) right = cellPlane(0, j, 1);
        if (right == -1 || right == first) right = edgePlane(i, j, RIGHT);

        if (first == second) {
          if (first == -1) continue;
          startFacet(top, right, bottom, left);
          classifyBorders(new int[] {at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1)});
          finishFacet(first);
        } else {
          int diagonal = second;
          if (diagonal == -1) diagonal = bottom;
          if (diagonal == -1) diagonal = edgePlane(i, j, DIAGONAL_OUT_OF_FIRST);
          startFacet(top, right, diagonal);
          classifyBorders(new int[] {at(i, j), at(i + 1, j), at(i + 1, j + 1)});
          finishFacet(first);

          diagonal = first;
          if (diagonal == -1) diagonal = top;
          if (diagonal == -1) diagonal = edgePlane(i, j, DIAGONAL_OUT_OF_SECOND);
          startFacet(bottom, left, diagonal);
          classifyBorders(new int[] {at(i + 1, j + 1), at(i, j + 1), at(i, j)});
          finishFacet(second);
        }
      }
  }

  private void startFacet(int... planesOfBorders) {
    borderCount = planesOfBorders.length;
    System.arraycopy(planesOfBorders, 0, borders, 0, borderCount);
    Arrays.fill(inward, 0, borderCount, false);
  }

  /**
   * Flags each border inward when the facet's points lie in front of it. A border the points all
   * lie on cannot bound the facet and is cleared, which later invalidates the facet.
   */
  private void classifyBorders(int[] points) {
    for (int k = 0; k < borderCount; k++) {
      int front = 0;
      int back = 0;
      for (int p : points) {
        int s = side(borders[k], grid, p);
        if (s == 0) front++;
        else if (s == 1) back++;
      }
      if (front > 0 && back == 0) inward[k] = true;
      else if (back > 0 && front == 0) inward[k] = false;
      else if (front == 0) borders[k] = -1;
      else inward[k] = false; // The border bisects the facet; keep it facing outward.
    }
  }

  /** Clips the surface plane's winding by every border, each oriented to face out of the facet. */
  private Winding clippedWinding(int surface, boolean skipSurfaceBorders) {
    int s = surface * 4;
    Winding w = Winding.forPlane(planes[s], planes[s + 1], planes[s + 2], planes[s + 3]);
    for (int k = 0; k < borderCount && w != null; k++) {
      int b = borders[k];
      if (b == -1) return null;
      if (skipSurfaceBorders && b == surface) continue;
      int base = b * 4;
      float sign = inward[k] ? 1 : -1;
      w =
          w.chop(
              sign * planes[base],
              sign * planes[base + 1],
              sign * planes[base + 2],
              sign * planes[base + 3],
              CHOP_EPSILON);
    }
    return w;
  }

  private void finishFacet(int surface) {
    if (surface == -1) return;
    Winding w = clippedWinding(surface, false);
    if (w == null) return;
    float[] b = w.bounds();
    for (int axis = 0; axis < 3; axis++) {
      // An unclipped side means a missing border.
      if (b[3 + axis] - b[axis] > Winding.MAX_MAP_BOUNDS) return;
      if (b[axis] >= Winding.MAX_MAP_BOUNDS || b[3 + axis] <= -Winding.MAX_MAP_BOUNDS) return;
    }
    if (facetCount == MAX_FACETS)
      throw new BudgetException("Patch needs more than " + MAX_FACETS + " facets");
    addBevels(surface);
    if (facetCount == facetSurface.length) {
      int n = facetCount * 2;
      facetSurface = Arrays.copyOf(facetSurface, n);
      facetFirst = Arrays.copyOf(facetFirst, n);
      facetBorders = Arrays.copyOf(facetBorders, n);
    }
    while (borderTotal + borderCount > borderPlane.length) {
      borderPlane = Arrays.copyOf(borderPlane, borderPlane.length * 2);
      borderInward = Arrays.copyOf(borderInward, borderInward.length * 2);
    }
    facetSurface[facetCount] = surface;
    facetFirst[facetCount] = borderTotal;
    facetBorders[facetCount] = borderCount;
    System.arraycopy(borders, 0, borderPlane, borderTotal, borderCount);
    System.arraycopy(inward, 0, borderInward, borderTotal, borderCount);
    borderTotal += borderCount;
    facetCount++;
  }

  private boolean alreadyBorder(int surface, float[] plane) {
    if (compare(surface, plane) >= 0) return true;
    for (int k = 0; k < borderCount; k++) if (compare(borders[k], plane) >= 0) return true;
    return false;
  }

  private void addBorder(float[] plane) {
    if (borderCount == borders.length) return;
    int found = findOrAddEither(plane);
    borders[borderCount] = found < 0 ? ~found : found;
    // The bevel faces out of the facet; a reversed stored plane must be flipped back.
    inward[borderCount] = found < 0;
    borderCount++;
  }

  private void addBevels(int surface) {
    Winding w = clippedWinding(surface, true);
    if (w == null) return;
    float[] bounds = w.bounds();
    float[] plane = new float[4];
    for (int axis = 0; axis < 3; axis++)
      for (int dir = -1; dir <= 1; dir += 2) {
        Arrays.fill(plane, 0);
        plane[axis] = dir;
        plane[3] = dir == 1 ? bounds[3 + axis] : -bounds[axis];
        if (!alreadyBorder(surface, plane)) addBorder(plane);
      }
    float[] edge = new float[3];
    for (int j = 0; j < w.count; j++) {
      int k = (j + 1) % w.count;
      for (int axis = 0; axis < 3; axis++)
        edge[axis] = w.points[j * 3 + axis] - w.points[k * 3 + axis];
      if (normalize(edge) < 0.5f) continue;
      snap(edge);
      if (Math.abs(edge[0]) == 1 || Math.abs(edge[1]) == 1 || Math.abs(edge[2]) == 1) continue;
      for (int axis = 0; axis < 3; axis++)
        for (int dir = -1; dir <= 1; dir += 2) {
          // edge x (dir * axis unit vector)
          float[] unit = {0, 0, 0};
          unit[axis] = dir;
          plane[0] = edge[1] * unit[2] - edge[2] * unit[1];
          plane[1] = edge[2] * unit[0] - edge[0] * unit[2];
          plane[2] = edge[0] * unit[1] - edge[1] * unit[0];
          if (normalize(plane) < 0.5f) continue;
          plane[3] =
              w.points[j * 3] * plane[0]
                  + w.points[j * 3 + 1] * plane[1]
                  + w.points[j * 3 + 2] * plane[2];
          boolean behind = true;
          for (int l = 0; l < w.count && behind; l++) {
            float d =
                w.points[l * 3] * plane[0]
                    + w.points[l * 3 + 1] * plane[1]
                    + w.points[l * 3 + 2] * plane[2]
                    - plane[3];
            if (d > BEVEL_EPSILON) behind = false;
          }
          if (!behind || alreadyBorder(surface, plane)) continue;
          int before = borderCount;
          addBorder(plane);
          if (borderCount == before) continue;
          int added = borders[before] * 4;
          float sign = inward[before] ? 1 : -1;
          Winding check =
              w.copy()
                  .chop(
                      sign * planes[added],
                      sign * planes[added + 1],
                      sign * planes[added + 2],
                      sign * planes[added + 3],
                      CHOP_EPSILON);
          if (check == null) borderCount = before; // Invalid bevel: it would cut the facet away.
        }
    }
    // The reversed surface plane gives the facet a back; traces never clip against it.
    if (borderCount < borders.length) {
      borders[borderCount] = surface;
      inward[borderCount] = true;
      borderCount++;
    }
  }

  private static float normalize(float[] v) {
    float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    if (length != 0) {
      float inverse = 1 / length;
      v[0] *= inverse;
      v[1] *= inverse;
      v[2] *= inverse;
    }
    return length;
  }

  /** Snaps a unit vector within {@link #NORMAL_EPSILON} of an axis onto it. */
  private static void snap(float[] v) {
    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(v[axis] - 1) < NORMAL_EPSILON) {
        Arrays.fill(v, 0);
        v[axis] = 1;
        return;
      }
      if (Math.abs(v[axis] + 1) < NORMAL_EPSILON) {
        Arrays.fill(v, 0);
        v[axis] = -1;
        return;
      }
    }
  }
}
