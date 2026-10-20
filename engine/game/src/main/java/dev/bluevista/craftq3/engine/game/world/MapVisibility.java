package dev.bluevista.craftq3.engine.game.world;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspBoxLeaves;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.Arrays;
import java.util.Objects;

/**
 * The BSP's leaf, cluster and area structure and the area-portal connectivity the server keeps on
 * top of it ({@code CM_PointLeafnum}, {@code CM_ClusterPVS}, {@code CM_AdjustAreaPortalState},
 * {@code CM_AreasConnected}, {@code CM_WriteAreaBits}).
 *
 * <p>Areas are separated unless an area portal between them has a positive open count; every change
 * refloods the connectivity. Owned by the simulation thread.
 */
public final class MapVisibility {
  /** {@code MAX_MAP_AREA_BYTES}. */
  public static final int MAX_AREA_BYTES = 32;

  private final BspMap map;
  private final int areas;
  private final int[] portals;
  private final int[] flood;
  private final int[] work;
  private int floodCount;

  public MapVisibility(BspMap map) {
    this.map = Objects.requireNonNull(map);
    int count = 0;
    BspMap.Leaves leaves = map.leaves();
    for (int leaf = 0; leaf < leaves.count(); leaf++)
      count = Math.max(count, leaves.area(leaf) + 1);
    areas = count;
    portals = new int[areas * areas];
    flood = new int[areas];
    work = new int[Math.max(1, areas)];
    reflood();
  }

  public BspMap map() {
    return map;
  }

  public int areaCount() {
    return areas;
  }

  /** {@code CM_PointLeafnum}: the leaf containing {@code point}. */
  public int pointLeaf(float x, float y, float z) {
    BspMap.Nodes nodes = map.nodes();
    if (nodes.count() == 0) return 0;
    BspMap.Planes planes = map.planes();
    int node = 0;
    int guard = nodes.count() + 1;
    while (node >= 0) {
      if (--guard < 0) return 0;
      int plane = nodes.plane(node);
      float d =
          planes.normal(plane, 0) * x
              + planes.normal(plane, 1) * y
              + planes.normal(plane, 2) * z
              - planes.distance(plane);
      node = d < 0 ? nodes.back(node) : nodes.front(node);
    }
    return ~node;
  }

  public int pointLeaf(float[] point) {
    return pointLeaf(point[0], point[1], point[2]);
  }

  public int leafCluster(int leaf) {
    return leaf < 0 || leaf >= map.leaves().count() ? -1 : map.leaves().cluster(leaf);
  }

  public int leafArea(int leaf) {
    return leaf < 0 || leaf >= map.leaves().count() ? -1 : map.leaves().area(leaf);
  }

  /** Whether {@code to} is in the potentially visible set of {@code from}. */
  public boolean clusterVisible(int from, int to) {
    return map.visibility().visible(from, to);
  }

  /** {@code CM_BoxLeafnums}: the leaves the box touches (up to {@code capacity}). */
  public BspBoxLeaves.Result boxLeaves(float[] mins, float[] maxs, int capacity) {
    return BspBoxLeaves.query(
        map, new Vec3f(mins[0], mins[1], mins[2]), new Vec3f(maxs[0], maxs[1], maxs[2]), capacity);
  }

  /**
   * {@code CM_AdjustAreaPortalState}: counts an area portal between two areas open or closed. A
   * negative area is ignored; an area outside the map or a count dropping below zero is refused.
   */
  public void adjustAreaPortalState(int first, int second, boolean open) {
    if (first < 0 || second < 0) return;
    if (first >= areas || second >= areas)
      throw new IllegalArgumentException("Bad area number " + first + "/" + second);
    int delta = open ? 1 : -1;
    if (portals[first * areas + second] + delta < 0 || portals[second * areas + first] + delta < 0)
      throw new IllegalStateException(
          "Negative area portal reference count between " + first + " and " + second);
    portals[first * areas + second] += delta;
    portals[second * areas + first] += delta;
    reflood();
  }

  /** {@code CM_AreasConnected}: false for a negative area. */
  public boolean areasConnected(int first, int second) {
    if (first < 0 || second < 0) return false;
    if (first >= areas || second >= areas)
      throw new IllegalArgumentException("Bad area number " + first + "/" + second);
    return flood[first] == flood[second];
  }

  /** Every portal closed again (a fresh map or a cleared world). */
  public void closeAllPortals() {
    Arrays.fill(portals, 0);
    reflood();
  }

  /**
   * {@code CM_WriteAreaBits}: ORs into {@code bits} one set bit per area connected to {@code area};
   * area -1 sees every area. Returns the byte count {@code (areas + 7) / 8}.
   */
  public int writeAreaBits(byte[] bits, int area) {
    int bytes = (areas + 7) >> 3;
    if (area < 0 || area >= areas) {
      Arrays.fill(bits, 0, Math.min(bytes, bits.length), (byte) 255);
      return bytes;
    }
    int mine = flood[area];
    for (int i = 0; i < areas && (i >> 3) < bits.length; i++)
      if (flood[i] == mine) bits[i >> 3] |= (byte) (1 << (i & 7));
    return bytes;
  }

  private void reflood() {
    Arrays.fill(flood, 0);
    floodCount = 0;
    for (int start = 0; start < areas; start++) {
      if (flood[start] != 0) continue;
      int id = ++floodCount;
      int top = 0;
      work[top++] = start;
      flood[start] = id;
      while (top > 0) {
        int area = work[--top];
        int row = area * areas;
        for (int other = 0; other < areas; other++) {
          if (portals[row + other] > 0 && flood[other] == 0) {
            flood[other] = id;
            work[top++] = other;
          }
        }
      }
    }
  }
}
