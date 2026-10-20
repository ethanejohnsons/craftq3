package dev.bluevista.craftq3.engine.collision;

import static dev.bluevista.craftq3.engine.collision.TestMap.v;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspBoxLeaves;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Leaves, clusters, PVS and area portals. */
class VisibilityTest {
  /**
   * Two rooms split by x = 0 and a solid slab split off by y = 100: leaf 0 (x >= 0, cluster 0, area
   * 0), leaf 1 (x < 0, cluster 1, area 1), leaf 2 (y >= 100, solid: cluster and area -1). Cluster 0
   * sees both clusters; cluster 1 sees only itself.
   */
  private static BspMap roomsMap() {
    TestMap map = new TestMap();
    map.texture("textures/none", 0, 0);
    int wall = map.plane(0, 1, 0, 100);
    int split = map.plane(1, 0, 0, 0);
    map.node(wall, -3, 1);
    map.node(split, -1, -2);
    map.leaf(0, 0, new int[0], new int[0]);
    map.leaf(1, 1, new int[0], new int[0]);
    map.leaf(-1, -1, new int[0], new int[0]);
    map.model(TestMap.WORLD_MIN, TestMap.WORLD_MAX, 0, 0, 0, 0);
    map.visibility(2, new byte[] {0b11, 0b10});
    return map.build();
  }

  private static CollisionModel rooms() {
    return CollisionModel.build(roomsMap());
  }

  @Test
  void leavesClustersAndAreas() {
    var model = rooms();
    assertEquals(3, model.leafCount());
    assertEquals(2, model.clusterCount());
    assertEquals(2, model.areaCount());
    assertEquals(0, model.pointLeafnum(v(10, 0, 0)));
    assertEquals(0, model.pointLeafnum(v(0, 0, 0)), "points on a plane go to its front");
    assertEquals(1, model.pointLeafnum(v(-10, 0, 0)));
    assertEquals(2, model.pointLeafnum(v(0, 150, 0)));
    assertEquals(1, model.leafCluster(1));
    assertEquals(-1, model.leafArea(2));
    assertThrows(IllegalArgumentException.class, () -> model.leafCluster(3));
    assertTrue(model.clusterVisible(0, 1));
    assertFalse(model.clusterVisible(1, 0));
    assertTrue(model.clusterVisible(-1, 0), "a negative source cluster sees everything");
  }

  @Test
  void boxLeafnumsMatchesTheFormatQuery() {
    BspMap map = roomsMap();
    var model = CollisionModel.build(map);
    int[] list = new int[8];
    int[] last = new int[1];
    float[][][] boxes = {
      {v(-10, -10, -10), v(10, 10, 10)},
      {v(5, 5, 5), v(6, 6, 6)},
      {v(-50, 90, 0), v(-40, 120, 0)},
      {v(0, 0, 0), v(0, 0, 0)},
      {v(-1, 100, -1), v(1, 100, 1)}
    };
    for (float[][] box : boxes) {
      int count = model.boxLeafnums(box[0], box[1], list, list.length, last);
      var expected = BspBoxLeaves.query(map, Vec3f.of(box[0]), Vec3f.of(box[1]), list.length);
      assertArrayEquals(expected.toArray(), Arrays.copyOf(list, count));
      assertEquals(expected.lastLeaf(), last[0]);
    }
    // Past the capacity the last clustered leaf is still reported.
    int count = model.boxLeafnums(v(-10, -10, -10), v(10, 10, 10), list, 1, last);
    assertEquals(1, count);
    assertEquals(1, last[0]);
  }

  @Test
  void pvsRespectsClustersAndOpenPortals() {
    var model = rooms();
    AreaPortalState portals = model.newAreaPortalState();
    float[] east = {10, 0, 0};
    float[] west = {-10, 0, 0};
    assertTrue(model.inPVSIgnorePortals(east, west));
    assertFalse(model.inPVSIgnorePortals(west, east), "PVS rows need not be symmetric");
    assertFalse(model.inPVS(east, west, portals), "a closed door blocks sight");
    portals.adjust(0, 1, true);
    assertTrue(model.inPVS(east, west, portals));
    assertFalse(model.inPVS(west, east, portals));
    // Points in solid have area -1, which is connected to nothing.
    assertFalse(model.inPVS(east, v(0, 150, 0), portals));
    assertTrue(model.inPVS(east, east, portals));
  }

  @Test
  void portalsAreReferenceCountedAndFloodFilled() {
    AreaPortalState portals = new AreaPortalState(4);
    assertTrue(portals.connected(2, 2));
    assertFalse(portals.connected(0, 2));
    portals.adjust(0, 1, true);
    portals.adjust(1, 2, true);
    assertTrue(portals.connected(0, 2), "connectivity is transitive");
    assertFalse(portals.connected(0, 3));
    // Two doors share one portal: it stays open until both close.
    portals.adjust(1, 2, true);
    portals.adjust(2, 1, false);
    assertTrue(portals.connected(0, 2));
    assertEquals(1, portals.portalCount(1, 2));
    portals.adjust(1, 2, false);
    assertFalse(portals.connected(0, 2));
    assertTrue(portals.connected(0, 1));
    assertThrows(IllegalStateException.class, () -> portals.adjust(1, 2, false));
    assertThrows(IllegalArgumentException.class, () -> portals.adjust(0, 4, true));
    portals.adjust(-1, 2, true); // ignored, as the original does
    assertFalse(portals.connected(-1, 2));
    assertFalse(portals.connected(0, -1));

    byte[] bits = new byte[1];
    assertEquals(1, portals.writeAreaBits(bits, 0));
    assertEquals(0b0011, bits[0]);
    bits[0] = 0;
    portals.writeAreaBits(bits, 3);
    assertEquals(0b1000, bits[0]);
    portals.writeAreaBits(bits, -1);
    assertEquals((byte) 0xff, bits[0]);
    portals.reset();
    assertFalse(portals.connected(0, 1));
  }
}
