package dev.bluevista.craftq3.assets.bsp;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered, bounded BSP leaf enumeration following measured box/plane boundary conventions. */
public final class BspBoxLeaves {
  public static final int DEFAULT_WORK_BUDGET = 1_000_000;

  public record Result(List<Integer> leaves, int lastLeaf) {
    public Result {
      leaves = List.copyOf(leaves);
    }
  }

  private BspBoxLeaves() {}

  public static Result query(BspMap map, BspMap.Bounds bounds, int capacity) {
    return query(map, bounds, capacity, DEFAULT_WORK_BUDGET);
  }

  public static Result query(BspMap map, BspMap.Bounds bounds, int capacity, int workBudget) {
    Objects.requireNonNull(map);
    Objects.requireNonNull(bounds);
    if (capacity < 0 || capacity > 1_000_000)
      throw new IllegalArgumentException("Invalid BSP leaf list capacity");
    if (workBudget < 1 || workBudget > 10_000_000)
      throw new IllegalArgumentException("Invalid BSP query work budget");
    float[] min = floats(bounds.min()), max = floats(bounds.max());
    for (int axis = 0; axis < 3; axis++)
      if (min[axis] > max[axis]) throw new IllegalArgumentException("Inverted BSP query bounds");
    var pending = new ArrayDeque<Integer>();
    if (!map.nodes().isEmpty()) pending.add(0);
    else if (map.leaves().size() == 1) pending.add(-1);
    else throw new IllegalArgumentException("BSP query requires a root node or one leaf");
    var leaves = new ArrayList<Integer>(Math.min(capacity, 128));
    int lastLeaf = 0, work = 0;
    while (!pending.isEmpty()) {
      if (++work > workBudget)
        throw new IllegalStateException("BSP leaf query work budget exceeded");
      int index = pending.removeLast();
      if (index < 0) {
        int leaf = ~index;
        if (leaf >= map.leaves().size())
          throw new IllegalArgumentException("Invalid BSP leaf child");
        if (leaves.size() < capacity) leaves.add(leaf);
        // The native observation keeps walking after capacity, including capacity zero.
        if (map.leaves().get(leaf).cluster() != -1) lastLeaf = leaf;
        continue;
      }
      if (index >= map.nodes().size()) throw new IllegalArgumentException("Invalid BSP node child");
      var node = map.nodes().get(index);
      if (node.plane() < 0 || node.plane() >= map.planes().size())
        throw new IllegalArgumentException("Invalid BSP node plane");
      var plane = map.planes().get(node.plane());
      float[] normal = floats(plane.normal());
      if (!Float.isFinite(plane.distance()))
        throw new IllegalArgumentException("Invalid BSP plane");
      int sides = sides(min, max, normal, plane.distance());
      // LIFO work preserves the observed front-before-back order, including duplicate leaves.
      if ((sides & 2) != 0) pending.add(node.back());
      if ((sides & 1) != 0) pending.add(node.front());
    }
    return new Result(leaves, lastLeaf);
  }

  private static int sides(float[] min, float[] max, float[] normal, float distance) {
    for (int axis = 0; axis < 3; axis++) {
      if (normal[axis] == 1) {
        if (min[axis] >= distance) return 1;
        if (max[axis] <= distance) return 2;
        return 3;
      }
    }
    float highX = normal[0] * (normal[0] < 0 ? min[0] : max[0]);
    float highY = normal[1] * (normal[1] < 0 ? min[1] : max[1]);
    float highZ = normal[2] * (normal[2] < 0 ? min[2] : max[2]);
    float lowX = normal[0] * (normal[0] < 0 ? max[0] : min[0]);
    float lowY = normal[1] * (normal[1] < 0 ? max[1] : min[1]);
    float lowZ = normal[2] * (normal[2] < 0 ? max[2] : min[2]);
    float high = (highX + highY) + highZ, low = (lowX + lowY) + lowZ;
    if (!Float.isFinite(high) || !Float.isFinite(low))
      throw new IllegalArgumentException("BSP query projection exceeds finite float range");
    return (high >= distance ? 1 : 0) | (low < distance ? 2 : 0);
  }

  private static float[] floats(Vec3 vector) {
    Objects.requireNonNull(vector);
    float[] result = {(float) vector.x(), (float) vector.y(), (float) vector.z()};
    for (float value : result)
      if (!Float.isFinite(value))
        throw new IllegalArgumentException("BSP query needs finite floats");
    return result;
  }
}
