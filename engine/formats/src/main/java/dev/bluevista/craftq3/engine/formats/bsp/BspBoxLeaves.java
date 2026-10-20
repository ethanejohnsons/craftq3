package dev.bluevista.craftq3.engine.formats.bsp;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Arrays;
import java.util.Objects;

/**
 * Ordered, bounded enumeration of the BSP leaves a box touches, following the measured box/plane
 * boundary conventions of the original leaf query: front before back, duplicates kept, and the last
 * non-solid leaf reported even past the capacity.
 */
public final class BspBoxLeaves {
  public static final int DEFAULT_WORK_BUDGET = 1_000_000;
  public static final int MAX_CAPACITY = 1_000_000;
  public static final int MAX_WORK_BUDGET = 10_000_000;

  private BspBoxLeaves() {}

  /** The leaves found (up to the capacity) and the last leaf with a cluster other than -1. */
  public static final class Result {
    private final int[] leaves;
    private final int lastLeaf;

    public Result(int[] leaves, int lastLeaf) {
      this.leaves = leaves.clone();
      this.lastLeaf = lastLeaf;
    }

    public int count() {
      return leaves.length;
    }

    public int leaf(int index) {
      return leaves[index];
    }

    public int[] toArray() {
      return leaves.clone();
    }

    public int lastLeaf() {
      return lastLeaf;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Result result
          && lastLeaf == result.lastLeaf
          && Arrays.equals(leaves, result.leaves);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(leaves) + lastLeaf;
    }

    @Override
    public String toString() {
      return "Result[leaves=" + Arrays.toString(leaves) + ", lastLeaf=" + lastLeaf + "]";
    }
  }

  public static Result query(BspMap map, Vec3f mins, Vec3f maxs, int capacity) {
    return query(map, mins, maxs, capacity, DEFAULT_WORK_BUDGET);
  }

  public static Result query(BspMap map, Vec3f mins, Vec3f maxs, int capacity, int workBudget) {
    Objects.requireNonNull(map);
    if (capacity < 0 || capacity > MAX_CAPACITY)
      throw new IllegalArgumentException("Invalid BSP leaf list capacity " + capacity);
    if (workBudget < 1 || workBudget > MAX_WORK_BUDGET)
      throw new IllegalArgumentException("Invalid BSP query work budget " + workBudget);
    float[] min = finite(mins);
    float[] max = finite(maxs);
    for (int axis = 0; axis < 3; axis++)
      if (min[axis] > max[axis]) throw new IllegalArgumentException("Inverted BSP query bounds");
    int[] pending = new int[64];
    int top = 0;
    if (map.nodes().count() > 0) pending[top++] = 0;
    else if (map.leaves().count() == 1) pending[top++] = -1;
    else throw new IllegalArgumentException("BSP query requires a root node or one leaf");
    int[] found = new int[Math.min(capacity, 128)];
    int foundCount = 0;
    int lastLeaf = 0;
    int work = 0;
    BspMap.Nodes nodes = map.nodes();
    while (top > 0) {
      if (++work > workBudget)
        throw new IllegalStateException("BSP leaf query work budget exceeded");
      int index = pending[--top];
      if (index < 0) {
        int leaf = ~index;
        if (leaf >= map.leaves().count()) throw new IllegalArgumentException("Invalid BSP leaf");
        if (foundCount < capacity) {
          if (foundCount == found.length) found = Arrays.copyOf(found, found.length * 2);
          found[foundCount++] = leaf;
        }
        // The native observation keeps walking after capacity, including capacity zero.
        if (map.leaves().cluster(leaf) != -1) lastLeaf = leaf;
        continue;
      }
      if (index >= nodes.count()) throw new IllegalArgumentException("Invalid BSP node child");
      int plane = nodes.plane(index);
      if (plane < 0 || plane >= map.planes().count())
        throw new IllegalArgumentException("Invalid BSP node plane");
      int sides = sides(min, max, map.planes(), plane);
      if (top + 2 > pending.length) pending = Arrays.copyOf(pending, pending.length * 2);
      // LIFO work preserves the observed front-before-back order, including duplicate leaves.
      if ((sides & 2) != 0) pending[top++] = nodes.back(index);
      if ((sides & 1) != 0) pending[top++] = nodes.front(index);
    }
    return new Result(Arrays.copyOf(found, foundCount), lastLeaf);
  }

  /** Bit 1: the box touches the front; bit 2: it touches the back. */
  private static int sides(float[] min, float[] max, BspMap.Planes planes, int plane) {
    float distance = planes.distance(plane);
    float nx = planes.normal(plane, 0);
    float ny = planes.normal(plane, 1);
    float nz = planes.normal(plane, 2);
    // The original classifies a plane as axial by its first unit component.
    int axial = nx == 1 ? 0 : ny == 1 ? 1 : nz == 1 ? 2 : -1;
    if (axial >= 0) {
      if (min[axial] >= distance) return 1;
      if (max[axial] <= distance) return 2;
      return 3;
    }
    float highX = nx * (nx < 0 ? min[0] : max[0]);
    float highY = ny * (ny < 0 ? min[1] : max[1]);
    float highZ = nz * (nz < 0 ? min[2] : max[2]);
    float lowX = nx * (nx < 0 ? max[0] : min[0]);
    float lowY = ny * (ny < 0 ? max[1] : min[1]);
    float lowZ = nz * (nz < 0 ? max[2] : min[2]);
    float high = (highX + highY) + highZ;
    float low = (lowX + lowY) + lowZ;
    if (!Float.isFinite(high) || !Float.isFinite(low))
      throw new IllegalArgumentException("BSP query projection exceeds finite float range");
    return (high >= distance ? 1 : 0) | (low < distance ? 2 : 0);
  }

  private static float[] finite(Vec3f vector) {
    Objects.requireNonNull(vector);
    if (!vector.isFinite()) throw new IllegalArgumentException("BSP query needs finite floats");
    return vector.toArray();
  }
}
