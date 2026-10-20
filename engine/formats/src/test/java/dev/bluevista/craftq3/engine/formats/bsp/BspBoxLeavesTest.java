package dev.bluevista.craftq3.engine.formats.bsp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

final class BspBoxLeavesTest {
  private static final int[] BOX = {-10, -10, -10, 10, 10, 10};

  @Test
  void positiveAxialTouchUsesTheMeasuredSingleSideWithFrontWinningForAPoint() {
    BspMap map = fixture(new Vec3f(1, 0, 0), 1, 2);
    assertLeaves(map, -1, 0, 10, 1);
    assertLeaves(map, 0, 0, 10, 0);
    assertLeaves(map, 0, 1, 10, 0);
    assertLeaves(map, -1, 1, 10, 0, 1);
  }

  @Test
  void negativeAxialAndObliqueMaximumCornerTouchesIncludeBothChildren() {
    BspMap negative = fixture(new Vec3f(-1, 0, 0), 1, 2);
    assertLeaves(negative, 0, 1, 10, 0, 1);
    assertLeaves(negative, -1, 0, 10, 0);
    BspMap oblique = fixture(new Vec3f(.6f, .8f, 0), 1, 2);
    assertLeaves(oblique, -1, 0, 10, 0, 1);
    assertLeaves(oblique, 0, 0, 10, 0);
  }

  @Test
  void nativePlaneTypeUsesTheFirstPositiveUnitComponentEvenForNoncanonicalNormals() {
    Vec3f[] normals = {new Vec3f(1, .2f, 0), new Vec3f(1, 1, 0), new Vec3f(0, 1, .2f)};
    Vec3f[] points = {new Vec3f(0, -1, 0), new Vec3f(0, -1, 0), new Vec3f(0, 0, -1)};
    for (int i = 0; i < normals.length; i++) {
      var result = BspBoxLeaves.query(fixture(normals[i], 1, 2), points[i], points[i], 2);
      assertArrayEquals(new int[] {0}, result.toArray());
    }
  }

  @Test
  void capacityTruncatesTheListWhileLastLeafContinuesPastSolidEntries() {
    BspMap map = fixture(new Vec3f(1, 0, 0), -1, 2);
    assertEquals(new BspBoxLeaves.Result(new int[0], 1), query(map, -1, 1, 0));
    assertEquals(new BspBoxLeaves.Result(new int[] {0}, 1), query(map, -1, 1, 1));
    assertEquals(new BspBoxLeaves.Result(new int[] {0, 1}, 1), query(map, -1, 1, 2));
    BspMap solidBack = fixture(new Vec3f(1, 0, 0), 2, -1);
    assertEquals(new BspBoxLeaves.Result(new int[] {1}, 0), query(solidBack, -2, -1, 2));
  }

  @Test
  void traversalPreservesDuplicateLeafVisits() {
    BspMap map = map(new float[] {1, 0, 0, 0}, node(-1, -1), leaves(1));
    assertEquals(new BspBoxLeaves.Result(new int[] {0, 0}, 0), query(map, -1, 1, 8));
  }

  @Test
  void invalidQueriesAndCyclicHandBuiltTreesFailWithinExplicitBounds() {
    BspMap map = fixture(new Vec3f(1, 0, 0), 1, 2);
    Vec3f low = new Vec3f(-10, -10, -10);
    Vec3f high = new Vec3f(10, 10, 10);
    assertThrows(IllegalArgumentException.class, () -> BspBoxLeaves.query(map, low, high, -1));
    assertThrows(IllegalArgumentException.class, () -> query(map, 1, -1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> BspBoxLeaves.query(map, Vec3f.ZERO, new Vec3f(Float.NaN, 0, 0), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> BspBoxLeaves.query(map, Vec3f.ZERO, new Vec3f(Float.POSITIVE_INFINITY, 0, 0), 1));
    BspMap cycle = map(new float[] {1, 0, 0, 0}, node(0, -1), leaves(1, 2));
    assertThrows(IllegalStateException.class, () -> BspBoxLeaves.query(cycle, x(1), x(2), 1, 5));
  }

  private static void assertLeaves(BspMap map, float min, float max, int capacity, int... leaves) {
    assertArrayEquals(leaves, query(map, min, max, capacity).toArray());
  }

  private static BspBoxLeaves.Result query(BspMap map, float min, float max, int capacity) {
    return BspBoxLeaves.query(map, x(min), x(max), capacity);
  }

  private static Vec3f x(float value) {
    return new Vec3f(value, 0, 0);
  }

  private static BspMap fixture(Vec3f normal, int frontCluster, int backCluster) {
    return map(
        new float[] {normal.x(), normal.y(), normal.z(), 0},
        node(-1, -2),
        leaves(frontCluster, backCluster));
  }

  private static int[] node(int front, int back) {
    int[] node = new int[BspMap.Nodes.STRIDE];
    node[1] = front;
    node[2] = back;
    System.arraycopy(BOX, 0, node, 3, 6);
    return node;
  }

  private static int[] leaves(int... clusters) {
    int[] data = new int[clusters.length * BspMap.Leaves.STRIDE];
    for (int i = 0; i < clusters.length; i++) {
      data[i * BspMap.Leaves.STRIDE] = clusters[i];
      data[i * BspMap.Leaves.STRIDE + 1] = clusters[i] < 0 ? -1 : 0;
      System.arraycopy(BOX, 0, data, i * BspMap.Leaves.STRIDE + 2, 6);
    }
    return data;
  }

  /** Hand-built maps bypass the reader so that invalid trees reach the query's own guards. */
  private static BspMap map(float[] planes, int[] nodes, int[] leaves) {
    BspMap.Parts parts = new BspMap.Parts();
    parts.planes = new BspMap.Planes(planes);
    parts.nodes = new BspMap.Nodes(nodes);
    parts.leaves = new BspMap.Leaves(leaves);
    return new BspMap(parts);
  }
}
