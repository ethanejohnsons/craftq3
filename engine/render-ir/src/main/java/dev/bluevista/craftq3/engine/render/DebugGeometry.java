package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded diagnostic line geometry in Q3 coordinates, generated when a debug mode changes. */
public final class DebugGeometry {
  private static final int MAX_LINES = 150_000;
  private static final int MAX_CLIP_STEPS = 2_000_000;
  private static final float NORMAL_LENGTH = 12;
  private static final int NORMAL_COLOR = 0x33ccffff;
  private static final double QUANTUM = 10000;

  private DebugGeometry() {}

  public enum Mode {
    NODES,
    LEAVES,
    NORMALS,
    BRUSHES
  }

  /** Line segments: endpoints as six floats per line, colours as {@code 0xRRGGBBAA}. */
  public record Lines(float[] endpoints, int[] colors, boolean truncated) {
    public int count() {
      return colors.length;
    }

    public Vec3f start(int line) {
      return new Vec3f(endpoints[line * 6], endpoints[line * 6 + 1], endpoints[line * 6 + 2]);
    }

    public Vec3f end(int line) {
      return new Vec3f(endpoints[line * 6 + 3], endpoints[line * 6 + 4], endpoints[line * 6 + 5]);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Lines l
          && truncated == l.truncated
          && Arrays.equals(endpoints, l.endpoints)
          && Arrays.equals(colors, l.colors);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(colors) * 31 + Arrays.hashCode(endpoints);
    }

    @Override
    public String toString() {
      return "Lines[count=" + count() + ", truncated=" + truncated + "]";
    }
  }

  private static final class Builder {
    float[] endpoints = new float[6 * 64];
    int[] colors = new int[64];
    int count;
    boolean truncated;

    boolean room(int lines) {
      if (count + lines <= MAX_LINES) return true;
      truncated = true;
      return false;
    }

    void add(double ax, double ay, double az, double bx, double by, double bz, int color) {
      if (count == colors.length) {
        colors = Arrays.copyOf(colors, count * 2);
        endpoints = Arrays.copyOf(endpoints, count * 12);
      }
      int at = count * 6;
      endpoints[at] = (float) ax;
      endpoints[at + 1] = (float) ay;
      endpoints[at + 2] = (float) az;
      endpoints[at + 3] = (float) bx;
      endpoints[at + 4] = (float) by;
      endpoints[at + 5] = (float) bz;
      colors[count++] = color;
    }

    Lines build() {
      return new Lines(
          Arrays.copyOf(endpoints, count * 6), Arrays.copyOf(colors, count), truncated);
    }
  }

  public static Lines build(RenderScene scene, Mode mode) {
    BspMap map = scene.bsp();
    Builder lines = new Builder();
    switch (mode) {
      case NODES -> {
        BspMap.Nodes nodes = map.nodes();
        for (int i = 0; i < nodes.count() && lines.room(12); i++)
          box(
              lines,
              new double[] {nodes.mins(i, 0), nodes.mins(i, 1), nodes.mins(i, 2)},
              new double[] {nodes.maxs(i, 0), nodes.maxs(i, 1), nodes.maxs(i, 2)},
              color(i));
      }
      case LEAVES -> {
        BspMap.Leaves leaves = map.leaves();
        for (int i = 0; i < leaves.count() && lines.room(12); i++)
          box(
              lines,
              new double[] {leaves.mins(i, 0), leaves.mins(i, 1), leaves.mins(i, 2)},
              new double[] {leaves.maxs(i, 0), leaves.maxs(i, 1), leaves.maxs(i, 2)},
              color(leaves.cluster(i)));
      }
      case NORMALS -> normals(scene.world(), lines);
      case BRUSHES -> brushes(map, lines);
    }
    return lines.build();
  }

  /** Stable, bright pseudo-colours for surface, brush, leaf and lightmap diagnostics. */
  public static int color(int id) {
    int hash = id * 0x9e3779b9;
    hash ^= hash >>> 16;
    return (80 + (hash & 127)) << 24
        | (80 + ((hash >>> 8) & 127)) << 16
        | (80 + ((hash >>> 16) & 127)) << 8
        | 255;
  }

  private static void normals(WorldGeometry world, Builder lines) {
    float[] p = world.vertexArrays().positions(), n = world.vertexArrays().normals();
    int[] indices = world.indices();
    for (int s = 0; s < world.surfaceCount(); s++) {
      if (world.surfaceModel(s) != 0) continue;
      int first = world.surfaceFirstIndex(s), end = first + world.surfaceIndexCount(s);
      for (int t = first; t + 2 < end; t += 3) {
        if (!lines.room(1)) return;
        double cx = 0, cy = 0, cz = 0, nx = 0, ny = 0, nz = 0;
        for (int c = 0; c < 3; c++) {
          int v = indices[t + c];
          cx += p[v * 3];
          cy += p[v * 3 + 1];
          cz += p[v * 3 + 2];
          nx += n[v * 3];
          ny += n[v * 3 + 1];
          nz += n[v * 3 + 2];
        }
        cx /= 3;
        cy /= 3;
        cz /= 3;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double scale = length < 1e-12 ? 0 : NORMAL_LENGTH / length;
        lines.add(cx, cy, cz, cx + nx * scale, cy + ny * scale, cz + nz * scale, NORMAL_COLOR);
      }
    }
  }

  private static void brushes(BspMap map, Builder lines) {
    int first = map.models().isEmpty() ? 0 : map.models().getFirst().firstBrush();
    int count =
        map.models().isEmpty() ? map.brushes().count() : map.models().getFirst().brushCount();
    double extent = worldExtent(map);
    int clipSteps = 0;
    for (int id = first; id < first + count; id++) {
      int sides = map.brushes().sideCount(id), firstSide = map.brushes().firstSide(id);
      Set<String> edges = new HashSet<>();
      for (int side = 0; side < sides; side++) {
        double[] plane = plane(map, firstSide + side);
        List<double[]> winding = winding(plane, extent);
        for (int clip = 0; clip < sides && !winding.isEmpty(); clip++) {
          if (clip == side) continue;
          clipSteps += winding.size();
          if (clipSteps > MAX_CLIP_STEPS) {
            lines.truncated = true;
            return;
          }
          winding = clip(winding, plane(map, firstSide + clip));
        }
        for (int edge = 0; edge < winding.size(); edge++) {
          double[] a = winding.get(edge), b = winding.get((edge + 1) % winding.size());
          if (!edges.add(edgeKey(a, b))) continue;
          if (!lines.room(1)) return;
          lines.add(a[0], a[1], a[2], b[0], b[1], b[2], color(id));
        }
      }
    }
  }

  private static void box(Builder lines, double[] min, double[] max, int color) {
    for (int i = 0; i < 8; i++)
      for (int axis = 1; axis <= 4; axis <<= 1)
        if ((i & axis) == 0) {
          int j = i | axis;
          lines.add(
              (i & 1) == 0 ? min[0] : max[0],
              (i & 2) == 0 ? min[1] : max[1],
              (i & 4) == 0 ? min[2] : max[2],
              (j & 1) == 0 ? min[0] : max[0],
              (j & 2) == 0 ? min[1] : max[1],
              (j & 4) == 0 ? min[2] : max[2],
              color);
        }
  }

  private static double[] plane(BspMap map, int side) {
    int plane = map.brushSides().plane(side);
    return new double[] {
      map.planes().normal(plane, 0),
      map.planes().normal(plane, 1),
      map.planes().normal(plane, 2),
      map.planes().distance(plane)
    };
  }

  private static double worldExtent(BspMap map) {
    if (map.models().isEmpty()) return 131072;
    BspMap.Model world = map.models().getFirst();
    double size = Math.max(Math.max(world.mins().length(), world.maxs().length()), 1);
    return size * 4 + 64;
  }

  /** A large square on the plane, wound consistently. */
  private static List<double[]> winding(double[] plane, double extent) {
    double lengthSquared = plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2];
    if (lengthSquared < 1e-20) return List.of();
    double length = Math.sqrt(lengthSquared);
    double nx = plane[0] / length, ny = plane[1] / length, nz = plane[2] / length;
    double scale = plane[3] / lengthSquared;
    double cx = plane[0] * scale, cy = plane[1] * scale, cz = plane[2] * scale;
    double ax = 0, ay = Math.abs(nz) > 0.9 ? 1 : 0, az = Math.abs(nz) > 0.9 ? 0 : 1;
    double ux = ay * nz - az * ny, uy = az * nx - ax * nz, uz = ax * ny - ay * nx;
    double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
    ux /= ul;
    uy /= ul;
    uz /= ul;
    double vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
    ux *= extent;
    uy *= extent;
    uz *= extent;
    vx *= extent;
    vy *= extent;
    vz *= extent;
    List<double[]> result = new ArrayList<>(4);
    result.add(new double[] {cx + ux + vx, cy + uy + vy, cz + uz + vz});
    result.add(new double[] {cx - ux + vx, cy - uy + vy, cz - uz + vz});
    result.add(new double[] {cx - ux - vx, cy - uy - vy, cz - uz - vz});
    result.add(new double[] {cx + ux - vx, cy + uy - vy, cz + uz - vz});
    return result;
  }

  private static List<double[]> clip(List<double[]> polygon, double[] plane) {
    List<double[]> result = new ArrayList<>();
    for (int i = 0; i < polygon.size(); i++) {
      double[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
      double da = a[0] * plane[0] + a[1] * plane[1] + a[2] * plane[2] - plane[3];
      double db = b[0] * plane[0] + b[1] * plane[1] + b[2] * plane[2] - plane[3];
      boolean insideA = da <= 0.001, insideB = db <= 0.001;
      if (insideA) result.add(a);
      if (insideA != insideB) {
        double t = da / (da - db);
        result.add(
            new double[] {
              a[0] * (1 - t) + b[0] * t, a[1] * (1 - t) + b[1] * t, a[2] * (1 - t) + b[2] * t
            });
      }
    }
    return result;
  }

  private static String edgeKey(double[] a, double[] b) {
    String first = quantized(a), second = quantized(b);
    return first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first;
  }

  private static String quantized(double[] point) {
    return Math.round(point[0] * QUANTUM)
        + ","
        + Math.round(point[1] * QUANTUM)
        + ","
        + Math.round(point[2] * QUANTUM);
  }
}
