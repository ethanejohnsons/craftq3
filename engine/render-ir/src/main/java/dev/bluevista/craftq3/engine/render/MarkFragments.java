package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Arrays;

/**
 * {@code trap_CM_MarkFragments}: clips world triangles to the prism a decal polygon sweeps along a
 * projection vector. The cgame owns mark textures and lifetimes; this only finds the fragments.
 * Surfaces flagged sky, no-impact or no-marks never receive marks.
 */
public final class MarkFragments {
  public static final int MAX_POLYGON_POINTS = 64;
  public static final int MAX_POINTS = 65536;
  public static final int MAX_FRAGMENTS = 8192;
  private static final int SKIP_FLAGS =
      WorldGeometry.SURF_SKY | WorldGeometry.SURF_NOIMPACT | WorldGeometry.SURF_NOMARKS;
  private static final double EPSILON = .05;
  private static final double CAP_MARGIN = .5;
  private static final float BOUNDS_MARGIN = .55f;
  private static final int MAX_TRIANGLES_VISITED = 1_000_000;
  private static final int MAX_CLIP_POINTS = MAX_POLYGON_POINTS + 8;

  private final WorldGeometry world;
  private final int[] surfaces;

  /** Fragments found by {@link #project}; reusable across calls. */
  public static final class Result {
    private float[] points = new float[64 * 3];
    private int[] fragmentFirst = new int[16];
    private int[] fragmentPoints = new int[16];
    private int pointCount;
    private int fragmentCount;

    void clear() {
      pointCount = 0;
      fragmentCount = 0;
    }

    void add(double[] polygon, int count) {
      if ((pointCount + count) * 3 > points.length)
        points = Arrays.copyOf(points, Math.max(points.length * 2, (pointCount + count) * 3));
      if (fragmentCount == fragmentFirst.length) {
        fragmentFirst = Arrays.copyOf(fragmentFirst, fragmentCount * 2);
        fragmentPoints = Arrays.copyOf(fragmentPoints, fragmentCount * 2);
      }
      fragmentFirst[fragmentCount] = pointCount;
      fragmentPoints[fragmentCount] = count;
      fragmentCount++;
      for (int i = 0; i < count * 3; i++) points[pointCount * 3 + i] = (float) polygon[i];
      pointCount += count;
    }

    public int pointCount() {
      return pointCount;
    }

    public int fragmentCount() {
      return fragmentCount;
    }

    /** First point of fragment {@code f}. */
    public int fragmentFirst(int f) {
      return fragmentFirst[f];
    }

    public int fragmentPoints(int f) {
      return fragmentPoints[f];
    }

    /** Coordinate {@code axis} of point {@code p}. */
    public float point(int p, int axis) {
      return points[p * 3 + axis];
    }

    public Vec3f point(int p) {
      return new Vec3f(points[p * 3], points[p * 3 + 1], points[p * 3 + 2]);
    }
  }

  public MarkFragments(WorldGeometry world) {
    this.world = world;
    int[] ids = new int[world.surfaceCount()];
    int count = 0;
    for (int s = 0; s < world.surfaceCount(); s++)
      if (world.surfaceModel(s) == 0
          && world.surfaceIndexCount(s) > 0
          && (world.surfaceFlags(s) & SKIP_FLAGS) == 0) ids[count++] = s;
    surfaces = Arrays.copyOf(ids, count);
  }

  /**
   * Clips against the polygon ({@code pointCount} xyz triples) swept along {@code projection}.
   * Output fragments keep the world triangle winding; stops once {@code maxPoints} or {@code
   * maxFragments} would be exceeded (they are capacities, never allocation sizes).
   */
  public Result project(
      float[] polygon,
      int pointCount,
      Vec3f projection,
      int maxPoints,
      int maxFragments,
      Result out) {
    out.clear();
    if (pointCount < 3
        || pointCount > MAX_POLYGON_POINTS
        || polygon.length < pointCount * 3
        || maxPoints < 0
        || maxPoints > MAX_POINTS
        || maxFragments < 0
        || maxFragments > MAX_FRAGMENTS)
      throw new IllegalArgumentException("Invalid mark fragment capacity");
    double length = projection.length();
    if (length < 1e-8 || maxPoints < 3 || maxFragments == 0) return out;
    if (length > 131072) throw new IllegalArgumentException("Mark projection too long");
    double dx = projection.x() / length, dy = projection.y() / length;
    double dz = projection.z() / length;
    double cx = 0, cy = 0, cz = 0;
    for (int i = 0; i < pointCount; i++) {
      cx += polygon[i * 3] * (1.0 / pointCount);
      cy += polygon[i * 3 + 1] * (1.0 / pointCount);
      cz += polygon[i * 3 + 2] * (1.0 / pointCount);
    }
    double[] planes = new double[(pointCount + 2) * 4];
    double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
    float[] box = {
      Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
      Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
    };
    for (int i = 0; i < pointCount; i++) {
      int next = (i + 1) % pointCount;
      double px = polygon[i * 3], py = polygon[i * 3 + 1], pz = polygon[i * 3 + 2];
      double ex = polygon[next * 3] - px, ey = polygon[next * 3 + 1] - py;
      double ez = polygon[next * 3 + 2] - pz;
      double nx = ey * dz - ez * dy, ny = ez * dx - ex * dz, nz = ex * dy - ey * dx;
      double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (nl < 1e-8) return out;
      nx /= nl;
      ny /= nl;
      nz /= nl;
      double distance = nx * px + ny * py + nz * pz;
      if (nx * cx + ny * cy + nz * cz > distance) {
        nx = -nx;
        ny = -ny;
        nz = -nz;
        distance = -distance;
      }
      planes[i * 4] = nx;
      planes[i * 4 + 1] = ny;
      planes[i * 4 + 2] = nz;
      planes[i * 4 + 3] = distance;
      double along = dx * px + dy * py + dz * pz;
      low = Math.min(low, along);
      high = Math.max(high, along + length);
      for (int axis = 0; axis < 3; axis++) {
        float value = polygon[i * 3 + axis];
        float swept = value + projection.get(axis);
        box[axis] = Math.min(box[axis], Math.min(value, swept));
        box[axis + 3] = Math.max(box[axis + 3], Math.max(value, swept));
      }
    }
    // Contact traces stop slightly in front of the surface; keep a narrow cap margin.
    int caps = pointCount * 4;
    planes[caps] = -dx;
    planes[caps + 1] = -dy;
    planes[caps + 2] = -dz;
    planes[caps + 3] = -low + CAP_MARGIN;
    planes[caps + 4] = dx;
    planes[caps + 5] = dy;
    planes[caps + 6] = dz;
    planes[caps + 7] = high + CAP_MARGIN;
    for (int axis = 0; axis < 3; axis++) {
      box[axis] -= BOUNDS_MARGIN;
      box[axis + 3] += BOUNDS_MARGIN;
    }
    float[] p = world.vertexArrays().positions(), n = world.vertexArrays().normals();
    int[] indices = world.indices();
    double[] a = new double[MAX_CLIP_POINTS * 3], b = new double[MAX_CLIP_POINTS * 3];
    int points = 0, visited = 0;
    for (int surface : surfaces) {
      if (!overlaps(box, world, surface)) continue;
      int first = world.surfaceFirstIndex(surface), end = first + world.surfaceIndexCount(surface);
      for (int t = first; t + 2 < end; t += 3) {
        if (++visited > MAX_TRIANGLES_VISITED)
          throw new IllegalStateException("Mark clipping work budget exceeded");
        int i0 = indices[t], i1 = indices[t + 1], i2 = indices[t + 2];
        double ax = p[i0 * 3], ay = p[i0 * 3 + 1], az = p[i0 * 3 + 2];
        double ux = p[i1 * 3] - ax, uy = p[i1 * 3 + 1] - ay, uz = p[i1 * 3 + 2] - az;
        double vx = p[i2 * 3] - ax, vy = p[i2 * 3 + 1] - ay, vz = p[i2 * 3 + 2] - az;
        double tx = uy * vz - uz * vy, ty = uz * vx - ux * vz, tz = ux * vy - uy * vx;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1e-10) continue;
        tx /= tl;
        ty /= tl;
        tz /= tl;
        double sx = n[i0 * 3] + n[i1 * 3] + n[i2 * 3];
        double sy = n[i0 * 3 + 1] + n[i1 * 3 + 1] + n[i2 * 3 + 1];
        double sz = n[i0 * 3 + 2] + n[i1 * 3 + 2] + n[i2 * 3 + 2];
        if (tx * sx + ty * sy + tz * sz < 0) {
          tx = -tx;
          ty = -ty;
          tz = -tz;
        }
        if (dx * tx + dy * ty + dz * tz >= -.01) continue;
        for (int c = 0; c < 3; c++) {
          int v = indices[t + c];
          a[c * 3] = p[v * 3];
          a[c * 3 + 1] = p[v * 3 + 1];
          a[c * 3 + 2] = p[v * 3 + 2];
        }
        int count = 3;
        double[] current = a, scratch = b;
        for (int plane = 0; plane < pointCount + 2 && count >= 3; plane++) {
          count = clip(current, count, planes, plane * 4, scratch);
          double[] swap = current;
          current = scratch;
          scratch = swap;
        }
        if (count < 3 || area(current, count) < 1e-8) continue;
        if (points + count > maxPoints || out.fragmentCount() >= maxFragments) return out;
        out.add(current, count);
        points += count;
      }
    }
    return out;
  }

  private static boolean overlaps(float[] box, WorldGeometry world, int surface) {
    return box[0] <= world.surfaceBound(surface, 3)
        && box[3] >= world.surfaceBound(surface, 0)
        && box[1] <= world.surfaceBound(surface, 4)
        && box[4] >= world.surfaceBound(surface, 1)
        && box[2] <= world.surfaceBound(surface, 5)
        && box[5] >= world.surfaceBound(surface, 2);
  }

  /** Sutherland-Hodgman clip keeping points with {@code n.p - d <= EPSILON}; returns the count. */
  private static int clip(double[] in, int count, double[] planes, int at, double[] out) {
    double nx = planes[at], ny = planes[at + 1], nz = planes[at + 2], d = planes[at + 3];
    int written = 0;
    int previous = count - 1;
    double before =
        nx * in[previous * 3] + ny * in[previous * 3 + 1] + nz * in[previous * 3 + 2] - d;
    for (int i = 0; i < count; i++) {
      double after = nx * in[i * 3] + ny * in[i * 3 + 1] + nz * in[i * 3 + 2] - d;
      boolean inBefore = before <= EPSILON, inAfter = after <= EPSILON;
      if (inBefore != inAfter && written < MAX_CLIP_POINTS) {
        double fraction = Math.clamp(before / (before - after), 0, 1);
        for (int axis = 0; axis < 3; axis++)
          out[written * 3 + axis] =
              in[previous * 3 + axis] + (in[i * 3 + axis] - in[previous * 3 + axis]) * fraction;
        written++;
      }
      if (inAfter && written < MAX_CLIP_POINTS) {
        out[written * 3] = in[i * 3];
        out[written * 3 + 1] = in[i * 3 + 1];
        out[written * 3 + 2] = in[i * 3 + 2];
        written++;
      }
      previous = i;
      before = after;
    }
    return written;
  }

  private static double area(double[] points, int count) {
    double sx = 0, sy = 0, sz = 0;
    for (int i = 1; i + 1 < count; i++) {
      double ux = points[i * 3] - points[0], uy = points[i * 3 + 1] - points[1];
      double uz = points[i * 3 + 2] - points[2];
      double vx = points[i * 3 + 3] - points[0], vy = points[i * 3 + 4] - points[1];
      double vz = points[i * 3 + 5] - points[2];
      sx += uy * vz - uz * vy;
      sy += uz * vx - ux * vz;
      sz += ux * vy - uy * vx;
    }
    return Math.sqrt(sx * sx + sy * sy + sz * sz) * .5;
  }
}
