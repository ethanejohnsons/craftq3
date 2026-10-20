package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import dev.bluevista.craftq3.engine.render.material.StageEvaluator;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Tessellates the patch faces of a map into sample grids, with Quake III's {@code r_subdivisions}
 * rules:
 *
 * <ol>
 *   <li><b>Error-bounded subdivision.</b> Each direction of a patch is refined span by span, first
 *       across its columns and then across its rows. A span is halved (de Casteljau) while, on some
 *       control curve running across it, the curve's midpoint lies more than {@code
 *       subdivisionError} units from the span's chord line, unless that distance is under {@value
 *       #FLAT_SPAN} units or the direction already has {@value #MAX_GRID_SIZE} samples. Halves are
 *       refined left to right, depth first. The row pass measures the control net the column pass
 *       produced, so both passes see the same curves the subdivided surface is made of. Every final
 *       span contributes its two ends and its midpoint as samples.
 *   <li><b>Collinear samples are dropped.</b> A sample column is removed when, on every row, it
 *       lies within {@code sqrt(0.1)} units of the line through its remaining neighbours (this also
 *       removes duplicates); then rows likewise.
 *   <li><b>Shared edges are stitched.</b> Patches whose boundary spans have the same three control
 *       points (in either order) take the union of each other's samples along that span, repeated
 *       until no patch gains a sample, so every vertex on a shared edge exists on both sides. Those
 *       boundary vertices are evaluated from the edge's control points alone, in a canonical order,
 *       so both patches compute bit-identical positions and no crack can open between them.
 * </ol>
 *
 * Samples are evaluated on the biquadratic surface itself, so positions, normals (renormalised),
 * texture and lightmap coordinates and colours are all interpolated alike. A uniform override
 * samples every block on a fixed {@code n}×{@code n} grid instead, with no removal or stitching.
 */
final class PatchTessellator {
  /** Most samples the subdivision step produces along one direction of a patch. */
  static final int MAX_GRID_SIZE = 65;

  private static final int MAX_SPANS = (MAX_GRID_SIZE - 1) / 2;

  /** A span whose control curves stay this close to their chords is flat (no subdivision). */
  private static final double FLAT_SPAN = 0.1;

  /** Squared distance from its neighbours' line under which a sample is collinear. */
  private static final double COLLINEAR_SQUARED = 0.1;

  /** Border sides: first and last row (sampled along u), first and last column (along v). */
  private static final int FIRST_ROW = 0, LAST_ROW = 1, FIRST_COLUMN = 2, LAST_COLUMN = 3;

  private static final Log LOG = Log.get(PatchTessellator.class);

  /** One patch face: its control grid and the sample parameters along each direction. */
  private static final class Grid {
    final int width;
    final int height;
    final int first;
    final int blocksX;
    final int blocksY;

    /** Global parameters {@code block + t}, {@code t} in [0, 1], along columns (u) and rows (v). */
    final TreeSet<Double> u = new TreeSet<>();

    final TreeSet<Double> v = new TreeSet<>();

    Grid(int width, int height, int first) {
      this.width = width;
      this.height = height;
      this.first = first;
      this.blocksX = (width - 1) / 2;
      this.blocksY = (height - 1) / 2;
    }

    int control(int row, int column) {
      return first + row * width + column;
    }

    TreeSet<Double> samples(int side) {
      return side == FIRST_ROW || side == LAST_ROW ? u : v;
    }
  }

  /** One boundary span of a patch: three control points along one of its four edges. */
  private record Edge(Grid grid, int side, int block, SpanKey key, boolean reversed) {}

  /**
   * Three control positions in canonical order (bit patterns, {@code -0} folded into {@code 0}).
   */
  private record SpanKey(int x0, int y0, int z0, int x1, int y1, int z1, int x2, int y2, int z2) {}

  private final VertexArrays raw;
  private final float[] p;
  private final Map<Integer, Grid> grids = new LinkedHashMap<>();

  private PatchTessellator(VertexArrays raw) {
    this.raw = raw;
    this.p = raw.positions();
  }

  /**
   * Plans the sample grids of every patch face for which {@code tessellate[face]} is set. Faces
   * with an invalid control grid are skipped with a warning.
   */
  static PatchTessellator plan(
      String name,
      BspMap map,
      VertexArrays raw,
      boolean[] tessellate,
      RenderScene.Options options) {
    PatchTessellator tessellator = new PatchTessellator(raw);
    BspMap.Faces faces = map.faces();
    int vertices = raw.positions().length / 3;
    for (int face = 0; face < faces.count(); face++) {
      if (!tessellate[face] || faces.surfaceType(face) != SurfaceType.PATCH) continue;
      int width = faces.patchWidth(face), height = faces.patchHeight(face);
      int first = faces.firstVertex(face);
      if (width < 3
          || height < 3
          || (width & 1) == 0
          || (height & 1) == 0
          || (long) width * height > faces.vertexCount(face)
          || first < 0
          || (long) first + (long) width * height > vertices) {
        LOG.warnOnce(
            "patch:" + name + ":" + face,
            name + ": patch face " + face + " has invalid size " + width + "x" + height);
        continue;
      }
      Grid grid = new Grid(width, height, first);
      if (options.uniformSubdivisions() > 0) {
        uniform(grid.u, grid.blocksX, options.uniformSubdivisions());
        uniform(grid.v, grid.blocksY, options.uniformSubdivisions());
      } else {
        tessellator.subdivide(grid, options.subdivisionError());
        tessellator.removeCollinear(grid);
      }
      tessellator.grids.put(face, grid);
    }
    if (options.uniformSubdivisions() == 0) tessellator.shareSpans(tessellator.sharedEdges());
    return tessellator;
  }

  // ------------------------------------------------------------------ subdivision

  private static void uniform(TreeSet<Double> out, int blocks, int steps) {
    for (int block = 0; block < blocks; block++)
      for (int k = 0; k <= steps; k++) out.add(block + (double) k / steps);
  }

  /** Step 1: error-bounded spans across the columns, then across the rows of the result. */
  private void subdivide(Grid grid, float tolerance) {
    // Columns: the control curves of block bx are the grid's rows over its three columns.
    double[][] curves = new double[grid.blocksX][];
    for (int bx = 0; bx < grid.blocksX; bx++) {
      double[] block = new double[grid.height * 9];
      for (int row = 0; row < grid.height; row++)
        for (int i = 0; i < 3; i++) copy(grid.control(row, 2 * bx + i), block, row * 9 + i * 3);
      curves[bx] = block;
    }
    List<double[]> uSpans = spans(curves, tolerance);
    for (double[] span : uSpans) {
      grid.u.add(span[0] + span[1]);
      grid.u.add(span[0] + (span[1] + span[2]) / 2);
      grid.u.add(span[0] + span[2]);
    }

    // The control net of the column-subdivided surface: per span, its start point, its blossom
    // (the inner control point) and its end point, as weights over the block's three columns.
    List<double[]> net = new ArrayList<>();
    for (int s = 0; s < uSpans.size(); s++) {
      double[] span = uSpans.get(s);
      double a = span[1], b = span[2];
      // Consecutive spans share their end and start (across blocks, the shared control column).
      if (s == 0) net.add(new double[] {span[0], (1 - a) * (1 - a), 2 * a * (1 - a), a * a});
      net.add(new double[] {span[0], (1 - a) * (1 - b), (1 - a) * b + a * (1 - b), a * b});
      net.add(new double[] {span[0], (1 - b) * (1 - b), 2 * b * (1 - b), b * b});
    }
    curves = new double[grid.blocksY][];
    for (int by = 0; by < grid.blocksY; by++) {
      double[] block = new double[net.size() * 9];
      for (int k = 0; k < net.size(); k++) {
        double[] w = net.get(k);
        int bx = (int) w[0];
        for (int j = 0; j < 3; j++)
          for (int axis = 0; axis < 3; axis++) {
            double sum = 0;
            for (int i = 0; i < 3; i++)
              sum += w[1 + i] * p[grid.control(2 * by + j, 2 * bx + i) * 3 + axis];
            block[k * 9 + j * 3 + axis] = sum;
          }
      }
      curves[by] = block;
    }
    for (double[] span : spans(curves, tolerance)) {
      grid.v.add(span[0] + span[1]);
      grid.v.add(span[0] + (span[1] + span[2]) / 2);
      grid.v.add(span[0] + span[2]);
    }
  }

  /**
   * The final spans {@code {block, a, b}} of one direction, given per block the quadratic control
   * curves (9 doubles each) that run across it.
   */
  private static List<double[]> spans(double[][] curves, float tolerance) {
    List<double[]> out = new ArrayList<>();
    int[] count = {curves.length};
    double[] scratch = new double[9];
    for (int block = 0; block < curves.length; block++)
      refine(curves[block], block, 0, 1, tolerance, count, scratch, out);
    return out;
  }

  private static void refine(
      double[] curves,
      int block,
      double a,
      double b,
      float tolerance,
      int[] count,
      double[] scratch,
      List<double[]> out) {
    double mid = (a + b) / 2;
    double error = 0;
    for (int c = 0; c < curves.length; c += 9) {
      bezier(curves, c, a, scratch, 0);
      bezier(curves, c, mid, scratch, 3);
      bezier(curves, c, b, scratch, 6);
      error = Math.max(error, lineDistanceSquared(scratch, 3, 0, 6));
    }
    error = Math.sqrt(error);
    if (error >= FLAT_SPAN && error > tolerance && count[0] < MAX_SPANS) {
      count[0]++;
      refine(curves, block, a, mid, tolerance, count, scratch, out);
      refine(curves, block, mid, b, tolerance, count, scratch, out);
    } else {
      out.add(new double[] {block, a, b});
    }
  }

  // ------------------------------------------------------------------ collinear removal

  /** Step 2: drops sample columns, then rows, that lie on the line through their neighbours. */
  private void removeCollinear(Grid grid) {
    double[] us = toArray(grid.u), vs = toArray(grid.v);
    int nu = us.length, nv = vs.length;
    double[] points = new double[nu * nv * 3];
    double[] a = new double[3], b = new double[3];
    for (int j = 0; j < nv; j++) {
      int by = block(vs[j], grid.blocksY);
      basis(vs[j] - by, b);
      for (int i = 0; i < nu; i++) {
        int bx = block(us[i], grid.blocksX);
        basis(us[i] - bx, a);
        for (int axis = 0; axis < 3; axis++) {
          double sum = 0;
          for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
              sum += a[c] * b[r] * p[grid.control(2 * by + r, 2 * bx + c) * 3 + axis];
          points[(j * nu + i) * 3 + axis] = sum;
        }
      }
    }
    List<Integer> columns = new ArrayList<>(), rows = new ArrayList<>();
    for (int i = 0; i < nu; i++) columns.add(i);
    for (int j = 0; j < nv; j++) rows.add(j);
    for (int k = 1; k < columns.size() - 1; ) {
      boolean collinear = true;
      for (int j = 0; j < nv && collinear; j++)
        collinear =
            lineDistanceSquared(
                    points,
                    (j * nu + columns.get(k)) * 3,
                    (j * nu + columns.get(k - 1)) * 3,
                    (j * nu + columns.get(k + 1)) * 3)
                < COLLINEAR_SQUARED;
      if (collinear) {
        grid.u.remove(us[columns.get(k)]);
        columns.remove(k);
      } else k++;
    }
    for (int k = 1; k < rows.size() - 1; ) {
      boolean collinear = true;
      for (int c = 0; c < columns.size() && collinear; c++) {
        int i = columns.get(c);
        collinear =
            lineDistanceSquared(
                    points,
                    (rows.get(k) * nu + i) * 3,
                    (rows.get(k - 1) * nu + i) * 3,
                    (rows.get(k + 1) * nu + i) * 3)
                < COLLINEAR_SQUARED;
      }
      if (collinear) {
        grid.v.remove(vs[rows.get(k)]);
        rows.remove(k);
      } else k++;
    }
  }

  // ------------------------------------------------------------------ stitching

  /** Every boundary span that another boundary span (of any patch) has the same controls as. */
  private List<Edge> sharedEdges() {
    List<Edge> edges = new ArrayList<>();
    for (Grid grid : grids.values()) {
      for (int side : new int[] {FIRST_ROW, LAST_ROW}) {
        int row = side == FIRST_ROW ? 0 : grid.height - 1;
        for (int bx = 0; bx < grid.blocksX; bx++)
          edges.add(
              edge(
                  grid,
                  side,
                  bx,
                  grid.control(row, 2 * bx),
                  grid.control(row, 2 * bx + 1),
                  grid.control(row, 2 * bx + 2)));
      }
      for (int side : new int[] {FIRST_COLUMN, LAST_COLUMN}) {
        int column = side == FIRST_COLUMN ? 0 : grid.width - 1;
        for (int by = 0; by < grid.blocksY; by++)
          edges.add(
              edge(
                  grid,
                  side,
                  by,
                  grid.control(2 * by, column),
                  grid.control(2 * by + 1, column),
                  grid.control(2 * by + 2, column)));
      }
    }
    Map<SpanKey, Integer> uses = new HashMap<>();
    for (Edge edge : edges) uses.merge(edge.key(), 1, Integer::sum);
    edges.removeIf(edge -> uses.get(edge.key()) < 2);
    return edges;
  }

  /**
   * Step 3: gives patches that share a boundary span the same samples along it, until no patch
   * gains one. Samples are dyadic fractions of a block, so mapping them across a reversed span
   * ({@code t -> 1 - t}) is exact.
   */
  private void shareSpans(List<Edge> edges) {
    boolean changed = !edges.isEmpty();
    while (changed) {
      Map<SpanKey, TreeSet<Double>> shared = new HashMap<>();
      for (Edge edge : edges) {
        TreeSet<Double> canonical = shared.computeIfAbsent(edge.key(), k -> new TreeSet<>());
        TreeSet<Double> samples = edge.grid().samples(edge.side());
        for (double global :
            samples.subSet((double) edge.block(), true, edge.block() + 1.0, true)) {
          double t = global - edge.block();
          canonical.add(edge.reversed() ? 1 - t : t);
        }
      }
      changed = false;
      for (Edge edge : edges) {
        TreeSet<Double> samples = edge.grid().samples(edge.side());
        for (double t : shared.get(edge.key()))
          changed |= samples.add(edge.block() + (edge.reversed() ? 1 - t : t));
      }
    }
  }

  private Edge edge(Grid grid, int side, int block, int c0, int c1, int c2) {
    boolean reversed = reversed(c0, c2);
    int s = reversed ? c2 : c0, e = reversed ? c0 : c2;
    SpanKey key =
        new SpanKey(
            bits(s, 0),
            bits(s, 1),
            bits(s, 2),
            bits(c1, 0),
            bits(c1, 1),
            bits(c1, 2),
            bits(e, 0),
            bits(e, 1),
            bits(e, 2));
    return new Edge(grid, side, block, key, reversed);
  }

  private int bits(int vertex, int axis) {
    return Float.floatToIntBits(p[vertex * 3 + axis] + 0f);
  }

  /** Whether a span's canonical order runs from {@code c2} to {@code c0}. */
  private boolean reversed(int c0, int c2) {
    for (int axis = 0; axis < 3; axis++) {
      int order = Float.compare(p[c0 * 3 + axis] + 0f, p[c2 * 3 + axis] + 0f);
      if (order != 0) return order > 0;
    }
    return false;
  }

  /**
   * The border vertex of {@code grid} at {@code sample} on {@code side}, from the edge's own span
   * so that a neighbour sharing the span computes the same bits.
   */
  private void borderPoint(Grid grid, int side, double sample, double[] out) {
    if (side == FIRST_ROW || side == LAST_ROW) {
      int row = side == FIRST_ROW ? 0 : grid.height - 1;
      int bx = block(sample, grid.blocksX);
      edgePoint(
          grid.control(row, 2 * bx),
          grid.control(row, 2 * bx + 1),
          grid.control(row, 2 * bx + 2),
          sample - bx,
          out);
    } else {
      int column = side == FIRST_COLUMN ? 0 : grid.width - 1;
      int by = block(sample, grid.blocksY);
      edgePoint(
          grid.control(2 * by, column),
          grid.control(2 * by + 1, column),
          grid.control(2 * by + 2, column),
          sample - by,
          out);
    }
  }

  // ------------------------------------------------------------------ emission

  /** Appends {@code face}'s planned grid to {@code mesh}; does nothing for faces not planned. */
  void emit(int face, MeshBuffer mesh) {
    Grid grid = grids.get(face);
    if (grid == null) return;
    double[] us = toArray(grid.u), vs = toArray(grid.v);
    int columns = us.length, rows = vs.length;
    mesh.reserveVertices(Math.multiplyExact(columns, rows));
    mesh.reserveIndices(Math.multiplyExact((columns - 1) * (rows - 1), 6));
    int base = mesh.vertexCount();
    float[] n = raw.normals(), uv = raw.texCoords(), lm = raw.lightmapCoords();
    double[] a = new double[3], b = new double[3], position = new double[3];
    for (int row = 0; row < rows; row++) {
      int by = block(vs[row], grid.blocksY);
      double tv = vs[row] - by;
      basis(tv, b);
      for (int column = 0; column < columns; column++) {
        int bx = block(us[column], grid.blocksX);
        double tu = us[column] - bx;
        basis(tu, a);
        double px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0;
        double s = 0, t = 0, ls = 0, lt = 0, red = 0, green = 0, blue = 0, alpha = 0;
        for (int j = 0; j < 3; j++)
          for (int i = 0; i < 3; i++) {
            int c = grid.control(2 * by + j, 2 * bx + i);
            double w = a[i] * b[j];
            px += p[c * 3] * w;
            py += p[c * 3 + 1] * w;
            pz += p[c * 3 + 2] * w;
            nx += n[c * 3] * w;
            ny += n[c * 3 + 1] * w;
            nz += n[c * 3 + 2] * w;
            s += uv[c * 2] * w;
            t += uv[c * 2 + 1] * w;
            ls += lm[c * 2] * w;
            lt += lm[c * 2 + 1] * w;
            int rgba = raw.rgba(c);
            red += (rgba >>> 24) * w;
            green += ((rgba >>> 16) & 255) * w;
            blue += ((rgba >>> 8) & 255) * w;
            alpha += (rgba & 255) * w;
          }
        // Border vertices come from the edge's own control points, so a neighbour sharing the
        // edge computes the same bits.
        if (row == 0 || row == rows - 1)
          borderPoint(grid, row == 0 ? FIRST_ROW : LAST_ROW, us[column], position);
        else if (column == 0 || column == columns - 1)
          borderPoint(grid, column == 0 ? FIRST_COLUMN : LAST_COLUMN, vs[row], position);
        else {
          position[0] = px;
          position[1] = py;
          position[2] = pz;
        }
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1e-12) {
          double inverse = 1 / length;
          nx *= inverse;
          ny *= inverse;
          nz *= inverse;
        }
        int color = channel(red) << 24 | channel(green) << 16 | channel(blue) << 8 | channel(alpha);
        mesh.addVertex(
            (float) position[0],
            (float) position[1],
            (float) position[2],
            (float) nx,
            (float) ny,
            (float) nz,
            (float) s,
            (float) t,
            (float) ls,
            (float) lt,
            StageEvaluator.restoreMapLightColor(color, WorldGeometry.MAP_OVERBRIGHT_BITS));
      }
    }
    // Q3 mesh indices wind opposite the control grid; reverse the grid traversal so patches share
    // the culling convention of indexed faces.
    for (int row = 0; row < rows - 1; row++)
      for (int column = 0; column < columns - 1; column++) {
        int v00 = base + row * columns + column, v01 = v00 + 1;
        int v10 = v00 + columns, v11 = v10 + 1;
        mesh.addTriangle(v00, v10, v01);
        mesh.addTriangle(v01, v10, v11);
      }
  }

  /** A point of the quadratic span {@code c0 c1 c2}, evaluated in the span's canonical order. */
  private void edgePoint(int c0, int c1, int c2, double t, double[] out) {
    if (reversed(c0, c2)) {
      int swap = c0;
      c0 = c2;
      c2 = swap;
      t = 1 - t;
    }
    double w0 = (1 - t) * (1 - t), w1 = 2 * t * (1 - t), w2 = t * t;
    for (int axis = 0; axis < 3; axis++)
      out[axis] = p[c0 * 3 + axis] * w0 + p[c1 * 3 + axis] * w1 + p[c2 * 3 + axis] * w2;
  }

  // ------------------------------------------------------------------ helpers

  private void copy(int vertex, double[] out, int at) {
    out[at] = p[vertex * 3];
    out[at + 1] = p[vertex * 3 + 1];
    out[at + 2] = p[vertex * 3 + 2];
  }

  private static int block(double global, int blocks) {
    return Math.min((int) global, blocks - 1);
  }

  private static double[] toArray(TreeSet<Double> samples) {
    double[] out = new double[samples.size()];
    int i = 0;
    for (double value : samples) out[i++] = value;
    return out;
  }

  /** The quadratic Bernstein point of {@code curves[c..c+9)} at {@code t}, into {@code out[at]}. */
  private static void bezier(double[] curves, int c, double t, double[] out, int at) {
    double w0 = (1 - t) * (1 - t), w1 = 2 * t * (1 - t), w2 = t * t;
    for (int axis = 0; axis < 3; axis++)
      out[at + axis] =
          curves[c + axis] * w0 + curves[c + 3 + axis] * w1 + curves[c + 6 + axis] * w2;
  }

  /**
   * Squared distance from the point at {@code q} to the line through {@code a} and {@code b} (all
   * offsets into {@code v}); to {@code a} itself when the two coincide.
   */
  private static double lineDistanceSquared(double[] v, int q, int a, int b) {
    double dx = v[b] - v[a], dy = v[b + 1] - v[a + 1], dz = v[b + 2] - v[a + 2];
    double qx = v[q] - v[a], qy = v[q + 1] - v[a + 1], qz = v[q + 2] - v[a + 2];
    double length = dx * dx + dy * dy + dz * dz;
    if (length > 0) {
      double along = (qx * dx + qy * dy + qz * dz) / length;
      qx -= dx * along;
      qy -= dy * along;
      qz -= dz * along;
    }
    return qx * qx + qy * qy + qz * qz;
  }

  private static void basis(double t, double[] out) {
    out[0] = (1 - t) * (1 - t);
    out[1] = 2 * t * (1 - t);
    out[2] = t * t;
  }

  private static int channel(double value) {
    return Math.clamp((int) Math.round(value), 0, 255);
  }
}
