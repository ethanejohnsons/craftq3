package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded diagnostic line geometry in Q3 coordinates; generated only when a debug mode changes. */
public final class DebugGeometry {
  private static final int MAX_LINES = 150_000;
  private static final int MAX_CLIP_STEPS = 2_000_000;

  private DebugGeometry() {}

  public enum Mode {
    NODES,
    LEAVES,
    NORMALS,
    BRUSHES
  }

  public record Line(Vec3 a, Vec3 b, int rgba) {}

  public record Result(List<Line> lines, boolean truncated) {
    public Result {
      lines = List.copyOf(lines);
    }
  }

  public static Result build(RenderScene scene, Mode mode) {
    List<Line> lines = new ArrayList<>();
    BspMap map = scene.bsp();
    if (map == null) return new Result(lines, false);
    boolean truncated = false;
    switch (mode) {
      case NODES -> {
        for (int i = 0; i < map.nodes().size(); i++) {
          if (lines.size() + 12 > MAX_LINES) {
            truncated = true;
            break;
          }
          box(lines, map.nodes().get(i).bounds(), color(i));
        }
      }
      case LEAVES -> {
        for (BspMap.Leaf leaf : map.leaves()) {
          if (lines.size() + 12 > MAX_LINES) {
            truncated = true;
            break;
          }
          box(lines, leaf.bounds(), color(leaf.cluster()));
        }
      }
      case NORMALS -> {
        List<BspMap.Vertex> vertices = scene.vertices();
        for (int i = 0; i + 2 < vertices.size(); i += 3) {
          if (lines.size() >= MAX_LINES) {
            truncated = true;
            break;
          }
          BspMap.Vertex a = vertices.get(i), b = vertices.get(i + 1), c = vertices.get(i + 2);
          Vec3 center = a.position().add(b.position()).add(c.position()).scale(1.0 / 3);
          Vec3 normal = unit(a.normal().add(b.normal()).add(c.normal()));
          lines.add(new Line(center, center.add(normal.scale(12)), 0x33ccffff));
        }
      }
      case BRUSHES -> {
        int first = map.models().isEmpty() ? 0 : map.models().getFirst().firstBrush();
        int count =
            map.models().isEmpty() ? map.brushes().size() : map.models().getFirst().brushCount();
        double extent = worldExtent(map);
        int clipSteps = 0;
        outer:
        for (int id = first; id < first + count; id++) {
          BspMap.Brush brush = map.brushes().get(id);
          Set<Edge> edges = new HashSet<>();
          for (int side = 0; side < brush.sideCount(); side++) {
            BspMap.Plane plane = plane(map, brush, side);
            List<Vec3> winding = winding(plane, extent);
            for (int clip = 0; clip < brush.sideCount() && !winding.isEmpty(); clip++) {
              if (clip == side) continue;
              clipSteps += winding.size();
              if (clipSteps > MAX_CLIP_STEPS) {
                truncated = true;
                break outer;
              }
              winding = clip(winding, plane(map, brush, clip));
            }
            for (int edge = 0; edge < winding.size(); edge++) {
              Vec3 a = winding.get(edge), b = winding.get((edge + 1) % winding.size());
              if (!edges.add(Edge.of(a, b))) continue;
              if (lines.size() >= MAX_LINES) {
                truncated = true;
                break outer;
              }
              lines.add(new Line(a, b, color(id)));
            }
          }
        }
      }
    }
    return new Result(lines, truncated);
  }

  /** Stable, bright pseudo-colors for surface, brush, leaf and lightmap diagnostics. */
  public static int color(int id) {
    int hash = id * 0x9e3779b9;
    hash ^= hash >>> 16;
    return (80 + (hash & 127)) << 24
        | (80 + ((hash >>> 8) & 127)) << 16
        | (80 + ((hash >>> 16) & 127)) << 8
        | 255;
  }

  private static void box(List<Line> lines, BspMap.Bounds bounds, int color) {
    Vec3[] corners = new Vec3[8];
    for (int i = 0; i < 8; i++)
      corners[i] =
          new Vec3(
              (i & 1) == 0 ? bounds.min().x() : bounds.max().x(),
              (i & 2) == 0 ? bounds.min().y() : bounds.max().y(),
              (i & 4) == 0 ? bounds.min().z() : bounds.max().z());
    for (int i = 0; i < 8; i++)
      for (int axis = 1; axis <= 4; axis <<= 1)
        if ((i & axis) == 0) lines.add(new Line(corners[i], corners[i | axis], color));
  }

  private static BspMap.Plane plane(BspMap map, BspMap.Brush brush, int side) {
    return map.planes().get(map.brushSides().get(brush.firstSide() + side).plane());
  }

  private static double worldExtent(BspMap map) {
    if (map.models().isEmpty()) return 131072;
    BspMap.Bounds bounds = map.models().getFirst().bounds();
    double size = Math.max(Math.max(length(bounds.min()), length(bounds.max())), 1);
    return size * 4 + 64;
  }

  private static List<Vec3> winding(BspMap.Plane plane, double extent) {
    double lengthSquared = dot(plane.normal(), plane.normal());
    if (lengthSquared < 1e-20) return List.of();
    Vec3 normal = unit(plane.normal());
    Vec3 center = plane.normal().scale(plane.distance() / lengthSquared);
    Vec3 axis = Math.abs(normal.z()) > 0.9 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
    Vec3 u = unit(cross(axis, normal)).scale(extent);
    Vec3 v = cross(normal, unit(u)).scale(extent);
    return List.of(
        center.add(u).add(v),
        center.add(u.scale(-1)).add(v),
        center.add(u.scale(-1)).add(v.scale(-1)),
        center.add(u).add(v.scale(-1)));
  }

  private static List<Vec3> clip(List<Vec3> polygon, BspMap.Plane plane) {
    List<Vec3> result = new ArrayList<>();
    for (int i = 0; i < polygon.size(); i++) {
      Vec3 a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
      double da = dot(a, plane.normal()) - plane.distance();
      double db = dot(b, plane.normal()) - plane.distance();
      boolean insideA = da <= 0.001, insideB = db <= 0.001;
      if (insideA) result.add(a);
      if (insideA != insideB) {
        double t = da / (da - db);
        result.add(a.scale(1 - t).add(b.scale(t)));
      }
    }
    return result;
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private static double length(Vec3 value) {
    return Math.sqrt(dot(value, value));
  }

  private static Vec3 unit(Vec3 value) {
    double length = length(value);
    return length < 1e-12 ? new Vec3(0, 0, 0) : value.scale(1 / length);
  }

  private static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }

  private record Quantized(long x, long y, long z) implements Comparable<Quantized> {
    static Quantized of(Vec3 value) {
      return new Quantized(
          Math.round(value.x() * 10000),
          Math.round(value.y() * 10000),
          Math.round(value.z() * 10000));
    }

    @Override
    public int compareTo(Quantized other) {
      int cmp = Long.compare(x, other.x);
      if (cmp == 0) cmp = Long.compare(y, other.y);
      if (cmp == 0) cmp = Long.compare(z, other.z);
      return cmp;
    }
  }

  private record Edge(Quantized a, Quantized b) {
    static Edge of(Vec3 a, Vec3 b) {
      Quantized qa = Quantized.of(a), qb = Quantized.of(b);
      return qa.compareTo(qb) <= 0 ? new Edge(qa, qb) : new Edge(qb, qa);
    }
  }
}
