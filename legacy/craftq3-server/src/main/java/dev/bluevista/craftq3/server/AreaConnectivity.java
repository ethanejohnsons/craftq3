package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** Reference-counted area portals are distinct from static cluster PVS. */
final class AreaConnectivity {
  private final BspMap map;
  private final int areas;
  private final Map<Long, Integer> links = new HashMap<>();

  AreaConnectivity(BspMap map) {
    this.map = map;
    areas =
        map.leaves().stream()
                .mapToInt(BspMap.Leaf::area)
                .filter(area -> area >= 0 && area < 256)
                .max()
                .orElse(-1)
            + 1;
  }

  void adjust(int first, int second, boolean open) {
    check(first);
    check(second);
    if (first == second) return;
    long key = ((long) Math.min(first, second) << 32) | Math.max(first, second);
    int value = links.getOrDefault(key, 0) + (open ? 1 : -1);
    if (value < 0) throw new IllegalStateException("Area portal reference count underflow");
    if (value == 0) links.remove(key);
    else links.put(key, value);
  }

  void reset() {
    links.clear();
  }

  boolean connected(int first, int second) {
    check(first);
    check(second);
    return reachable(first).get(second);
  }

  boolean valid(int area) {
    return area >= 0 && area < areas;
  }

  BitSet reachable(int first) {
    BitSet visited = new BitSet();
    if (!valid(first)) {
      visited.set(0, areas);
      return visited;
    }
    var pending = new ArrayDeque<Integer>();
    pending.add(first);
    visited.set(first);
    while (!pending.isEmpty()) {
      int current = pending.removeFirst();
      for (long key : links.keySet()) {
        int a = (int) (key >>> 32), b = (int) key, next = a == current ? b : b == current ? a : -1;
        if (next >= 0 && !visited.get(next)) {
          visited.set(next);
          pending.add(next);
        }
      }
    }
    return visited;
  }

  boolean visible(Vec3 first, Vec3 second, boolean ignorePortals) {
    BspMap.Leaf a = leaf(first), b = leaf(second);
    if (a == null || b == null || a.cluster() < 0 || b.cluster() < 0) return true;
    if (!map.visibility().visible(a.cluster(), b.cluster())) return false;
    return ignorePortals || !valid(a.area()) || !valid(b.area()) || connected(a.area(), b.area());
  }

  BspMap.Leaf leaf(Vec3 point) {
    if (map.nodes().isEmpty()) return map.leaves().size() == 1 ? map.leaves().getFirst() : null;
    int node = 0, remaining = map.nodes().size() + 1;
    while (node >= 0) {
      if (node >= map.nodes().size() || --remaining < 0) return null;
      var split = map.nodes().get(node);
      if (split.plane() < 0 || split.plane() >= map.planes().size()) return null;
      var plane = map.planes().get(split.plane());
      double distance =
          point.x() * plane.normal().x()
              + point.y() * plane.normal().y()
              + point.z() * plane.normal().z()
              - plane.distance();
      node = distance >= 0 ? split.front() : split.back();
    }
    int index = -node - 1;
    return index < 0 || index >= map.leaves().size() ? null : map.leaves().get(index);
  }

  private void check(int area) {
    if (area < 0 || area >= areas) throw new IllegalArgumentException("Invalid area " + area);
  }
}
