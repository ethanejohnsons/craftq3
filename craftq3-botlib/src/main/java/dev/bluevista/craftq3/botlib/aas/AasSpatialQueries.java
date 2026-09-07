package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasMap.Plane;
import dev.bluevista.craftq3.botlib.aas.AasNavigation.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Objects;

final class AasSpatialQueries {
  private AasSpatialQueries() {}

  static int point(AasMap map, Vec3 point) {
    Objects.requireNonNull(point);
    int node = root(map), remaining = map.nodes().size();
    while (node > 0) {
      if (remaining-- <= 0) throw new IllegalArgumentException("AAS map has a cyclic node path");
      var split = map.nodes().get(node);
      node =
          pointDistance(map.planes().get(split.plane()), point) > 0 ? split.front() : split.back();
    }
    return -node;
  }

  static AreaQuery box(AasMap map, Vec3 min, Vec3 max, QueryBudget budget) {
    Objects.requireNonNull(min);
    Objects.requireNonNull(max);
    Objects.requireNonNull(budget);
    if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
      throw new IllegalArgumentException("Inverted query bounds");
    var pending = new ArrayDeque<Integer>();
    var visited = new BitSet(map.nodes().size());
    var areas = new BitSet(map.areas().size());
    var result = new ArrayList<Integer>();
    pending.push(root(map));
    int count = 0;
    boolean complete = true;
    while (!pending.isEmpty()) {
      if (count == budget.maxNodeVisits()) {
        complete = false;
        break;
      }
      int node = pending.pop();
      count++;
      if (node == 0) continue;
      if (node < 0) {
        int area = -node;
        var bounds = map.areas().get(area);
        if (areas.get(area) || !overlap(min, max, bounds.min(), bounds.max())) continue;
        areas.set(area);
        if (result.size() == budget.maxResults()) {
          complete = false;
          break;
        }
        result.add(area);
      } else if (!visited.get(node)) {
        visited.set(node);
        var split = map.nodes().get(node);
        Plane plane = map.planes().get(split.plane());
        Vec3 n = plane.normal();
        var low =
            new Vec3(
                n.x() >= 0 ? min.x() : max.x(),
                n.y() >= 0 ? min.y() : max.y(),
                n.z() >= 0 ? min.z() : max.z());
        var high =
            new Vec3(
                n.x() >= 0 ? max.x() : min.x(),
                n.y() >= 0 ? max.y() : min.y(),
                n.z() >= 0 ? max.z() : min.z());
        // Closed boxes touching a split conservatively include both sides.
        if (distance(plane, low) <= 0) pending.push(split.back());
        if (distance(plane, high) >= 0) pending.push(split.front());
      }
    }
    Collections.sort(result);
    return new AreaQuery(result, complete, count);
  }

  static AreaTrace trace(AasMap map, Vec3 start, Vec3 end, QueryBudget budget) {
    Objects.requireNonNull(start);
    Objects.requireNonNull(end);
    Objects.requireNonNull(budget);
    boolean startSolid = point(map, start) == 0;
    int endArea = point(map, end), count = 0;
    var pending = new ArrayDeque<Segment>();
    var spans = new ArrayList<AreaSpan>();
    pending.push(new Segment(root(map), 0, 1));
    boolean complete = true;
    while (!pending.isEmpty()) {
      if (count == budget.maxNodeVisits()) {
        complete = false;
        break;
      }
      Segment part = pending.pop();
      count++;
      if (part.node() == 0) continue;
      if (part.node() < 0) {
        int area = -part.node();
        if (!spans.isEmpty()) {
          AreaSpan previous = spans.getLast();
          if (previous.area() == area && previous.exitFraction() == part.from()) {
            spans.set(
                spans.size() - 1,
                new AreaSpan(
                    area,
                    previous.enterFraction(),
                    part.to(),
                    previous.entry(),
                    interpolate(start, end, part.to())));
            continue;
          }
        }
        if (spans.size() == budget.maxResults()) {
          complete = false;
          break;
        }
        spans.add(
            new AreaSpan(
                area,
                part.from(),
                part.to(),
                interpolate(start, end, part.from()),
                interpolate(start, end, part.to())));
        continue;
      }
      var split = map.nodes().get(part.node());
      Plane plane = map.planes().get(split.plane());
      double d0 =
          start.equals(end)
              ? pointDistance(plane, start)
              : distance(plane, interpolate(start, end, part.from()));
      double d1 = start.equals(end) ? d0 : distance(plane, interpolate(start, end, part.to()));
      if (d0 > 0 && d1 > 0) pending.push(new Segment(split.front(), part.from(), part.to()));
      else if (d0 <= 0 && d1 <= 0) pending.push(new Segment(split.back(), part.from(), part.to()));
      else {
        double scale = Math.max(Math.abs(d0), Math.abs(d1));
        double fraction = (d0 / scale) / (d0 / scale - d1 / scale);
        double middle = part.from() * (1 - fraction) + part.to() * fraction;
        int near = d0 > 0 ? split.front() : split.back();
        int far = d0 > 0 ? split.back() : split.front();
        if (middle < part.to()) pending.push(new Segment(far, middle, part.to()));
        if (middle > part.from()) pending.push(new Segment(near, part.from(), middle));
      }
    }
    return new AreaTrace(spans, complete, startSolid, endArea, count);
  }

  private static int root(AasMap map) {
    return map.nodes().size() > 1 ? 1 : 0;
  }

  private static float pointDistance(Plane plane, Vec3 point) {
    Vec3 n = plane.normal();
    float x = (float) point.x(), y = (float) point.y(), z = (float) point.z();
    float result = (float) n.x() * x + (float) n.y() * y + (float) n.z() * z - plane.distance();
    if (!Float.isFinite(result))
      throw new IllegalArgumentException("Point exceeds finite AAS float geometry range");
    return result;
  }

  private static double distance(Plane plane, Vec3 point) {
    Vec3 n = plane.normal();
    double result = n.x() * point.x() + n.y() * point.y() + n.z() * point.z() - plane.distance();
    if (!Double.isFinite(result))
      throw new IllegalArgumentException("Query exceeds finite geometry range");
    return result;
  }

  private static Vec3 interpolate(Vec3 a, Vec3 b, double fraction) {
    return new Vec3(
        a.x() * (1 - fraction) + b.x() * fraction,
        a.y() * (1 - fraction) + b.y() * fraction,
        a.z() * (1 - fraction) + b.z() * fraction);
  }

  private static boolean overlap(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
    return a.x() <= d.x()
        && b.x() >= c.x()
        && a.y() <= d.y()
        && b.y() >= c.y()
        && a.z() <= d.z()
        && b.z() >= c.z();
  }

  private record Segment(int node, double from, double to) {}
}
