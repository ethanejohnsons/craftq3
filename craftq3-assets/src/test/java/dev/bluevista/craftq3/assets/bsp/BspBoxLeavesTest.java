package dev.bluevista.craftq3.assets.bsp;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BspBoxLeavesTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final BspMap.Bounds BOX =
      new BspMap.Bounds(new Vec3(-10, -10, -10), new Vec3(10, 10, 10));

  @Test
  void positiveAxialTouchUsesTheMeasuredSingleSideWithFrontWinningForAPoint() {
    var map = fixture(new Vec3(1, 0, 0), 1, 2);
    assertEquals(List.of(1), BspBoxLeaves.query(map, bounds(-1, 0), 10).leaves());
    assertEquals(List.of(0), BspBoxLeaves.query(map, bounds(0, 0), 10).leaves());
    assertEquals(List.of(0), BspBoxLeaves.query(map, bounds(0, 1), 10).leaves());
    assertEquals(List.of(0, 1), BspBoxLeaves.query(map, bounds(-1, 1), 10).leaves());
  }

  @Test
  void negativeAxialAndObliqueMaximumCornerTouchesIncludeBothChildren() {
    var negative = fixture(new Vec3(-1, 0, 0), 1, 2);
    assertEquals(List.of(0, 1), BspBoxLeaves.query(negative, bounds(0, 1), 10).leaves());
    assertEquals(List.of(0), BspBoxLeaves.query(negative, bounds(-1, 0), 10).leaves());
    var oblique = fixture(new Vec3(.6f, .8f, 0), 1, 2);
    assertEquals(List.of(0, 1), BspBoxLeaves.query(oblique, bounds(-1, 0), 10).leaves());
    assertEquals(List.of(0), BspBoxLeaves.query(oblique, bounds(0, 0), 10).leaves());
  }

  @Test
  void nativePlaneTypeUsesTheFirstPositiveUnitComponentEvenForNoncanonicalNormals() {
    Vec3[] normals = {new Vec3(1, .2f, 0), new Vec3(1, 1, 0), new Vec3(0, 1, .2f)};
    Vec3[] points = {new Vec3(0, -1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, -1)};
    for (int i = 0; i < normals.length; i++) {
      var query = new BspMap.Bounds(points[i], points[i]);
      assertEquals(List.of(0), BspBoxLeaves.query(fixture(normals[i], 1, 2), query, 2).leaves());
    }
  }

  @Test
  void capacityTruncatesTheListWhileLastLeafContinuesPastSolidEntries() {
    var map = fixture(new Vec3(1, 0, 0), -1, 2);
    assertEquals(new BspBoxLeaves.Result(List.of(), 1), BspBoxLeaves.query(map, bounds(-1, 1), 0));
    assertEquals(new BspBoxLeaves.Result(List.of(0), 1), BspBoxLeaves.query(map, bounds(-1, 1), 1));
    assertEquals(
        new BspBoxLeaves.Result(List.of(0, 1), 1), BspBoxLeaves.query(map, bounds(-1, 1), 2));
    var solidBack = fixture(new Vec3(1, 0, 0), 2, -1);
    assertEquals(
        new BspBoxLeaves.Result(List.of(1), 0), BspBoxLeaves.query(solidBack, bounds(-2, -1), 2));
    assertThrows(
        UnsupportedOperationException.class,
        () -> BspBoxLeaves.query(map, BOX, 1).leaves().clear());
  }

  @Test
  void traversalPreservesDuplicateLeafVisits() {
    var plane = new BspMap.Plane(new Vec3(1, 0, 0), 0);
    var map = map(List.of(plane), List.of(new BspMap.Node(0, -1, -1, BOX)), List.of(leaf(1)));
    assertEquals(
        new BspBoxLeaves.Result(List.of(0, 0), 0), BspBoxLeaves.query(map, bounds(-1, 1), 8));
  }

  @Test
  void invalidQueriesAndCyclicHandBuiltTreesFailWithinExplicitBounds() {
    var map = fixture(new Vec3(1, 0, 0), 1, 2);
    assertThrows(IllegalArgumentException.class, () -> BspBoxLeaves.query(map, BOX, -1));
    assertThrows(IllegalArgumentException.class, () -> BspBoxLeaves.query(map, bounds(1, -1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BspBoxLeaves.query(map, new BspMap.Bounds(ZERO, new Vec3(Double.MAX_VALUE, 0, 0)), 1));
    var cycle = map(map.planes(), List.of(new BspMap.Node(0, 0, -1, BOX)), map.leaves());
    assertThrows(IllegalStateException.class, () -> BspBoxLeaves.query(cycle, bounds(1, 2), 1, 5));
  }

  private static BspMap.Bounds bounds(double min, double max) {
    return new BspMap.Bounds(new Vec3(min, 0, 0), new Vec3(max, 0, 0));
  }

  private static BspMap fixture(Vec3 normal, int frontCluster, int backCluster) {
    return map(
        List.of(new BspMap.Plane(normal, 0)),
        List.of(new BspMap.Node(0, -1, -2, BOX)),
        List.of(leaf(frontCluster), leaf(backCluster)));
  }

  private static BspMap.Leaf leaf(int cluster) {
    return new BspMap.Leaf(cluster, cluster < 0 ? -1 : 0, BOX, 0, 0, 0, 0);
  }

  private static BspMap map(
      List<BspMap.Plane> planes, List<BspMap.Node> nodes, List<BspMap.Leaf> leaves) {
    return new BspMap(
        List.of(),
        List.of(),
        planes,
        nodes,
        leaves,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0])));
  }
}
