package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SnapshotVisibilityTest {
  private static final Vec3 LEFT = new Vec3(-5, 0, 0), RIGHT = new Vec3(15, 0, 0);
  private static final BspMap.Bounds WORLD = box(-100, 100);

  @Test
  void excludesTheViewedPlayerAndAppliesAudienceFlagsBeforeBroadcast() {
    var map = map(new byte[] {7, 7, 7}, false);
    var selector = new SnapshotVisibility(map, new AreaConnectivity(map));
    var candidates =
        List.of(
            entity(7, -5), // Following player 7 excludes 7, while entity 0 remains visible.
            entity(0, -5),
            flagged(8, SnapshotVisibility.BROADCAST | SnapshotVisibility.NOCLIENT, -1),
            flagged(9, SnapshotVisibility.SINGLECLIENT, 7),
            flagged(10, SnapshotVisibility.SINGLECLIENT, 0),
            flagged(11, SnapshotVisibility.NOTSINGLECLIENT, 7),
            flagged(12, SnapshotVisibility.NOTSINGLECLIENT, 0),
            flagged(13, SnapshotVisibility.CLIENTMASK, 1 << 7),
            flagged(14, SnapshotVisibility.CLIENTMASK, 1));
    assertEquals(List.of(0, 9, 12, 13), selector.select(LEFT, 7, candidates).entities());
    assertEquals(
        List.of(),
        selector
            .select(LEFT, 32, List.of(flagged(13, SnapshotVisibility.CLIENTMASK, -1)))
            .entities());
  }

  @Test
  void usesBspSplitsAndAllTouchedClustersRatherThanOverlappingLeafBounds() {
    var map = map(new byte[] {1, 2, 4}, false);
    var selector = new SnapshotVisibility(map, new AreaConnectivity(map));
    var crossing = new SnapshotVisibility.Candidate(4, box(-1, 1), 0, -1, LEFT, LEFT, 0);
    // Every fixture leaf deliberately has the same broad bounds; the split planes decide.
    var candidates = List.of(entity(3, 15), entity(2, 5), crossing, entity(1, -5));
    assertEquals(List.of(1, 4), selector.select(LEFT, 0, candidates).entities());
    assertEquals(List.of(2, 4), selector.select(new Vec3(5, 0, 0), 0, candidates).entities());
    // Reusing an entity number after movement must invalidate only its cached membership.
    assertEquals(List.of(), selector.select(LEFT, 0, List.of(entity(1, 15))).entities());
  }

  @Test
  void areaDoorsAreLiveAndEntitiesMayStraddleMultipleAreas() {
    var map = map(new byte[] {7, 7, 7}, true);
    var areas = new AreaConnectivity(map);
    var selector = new SnapshotVisibility(map, areas);
    var crossing = new SnapshotVisibility.Candidate(4, box(9, 11), 0, -1, RIGHT, RIGHT, 0);
    var candidates = List.of(entity(3, 15), entity(1, -5), crossing);
    var closed = selector.select(LEFT, 0, candidates);
    assertEquals(List.of(1, 4), closed.entities());
    assertFalse(hidden(closed, 0));
    assertTrue(hidden(closed, 1));
    areas.adjust(0, 1, true);
    areas.adjust(0, 1, true);
    assertEquals(List.of(1, 3, 4), selector.select(LEFT, 0, candidates).entities());
    areas.adjust(1, 0, false);
    assertFalse(hidden(selector.select(LEFT, 0, candidates), 1));
    areas.adjust(0, 1, false);
    assertEquals(closed.entities(), selector.select(LEFT, 0, candidates).entities());
  }

  @Test
  void visiblePortalsUnionRemotePvsAndAreaMasksWithoutDuplicatesOrCycles() {
    var map = map(new byte[] {1, 2, 4}, true);
    var selector = new SnapshotVisibility(map, new AreaConnectivity(map));
    var candidates =
        List.of(
            entity(3, 15),
            entity(2, 5),
            entity(1, -5),
            portal(10, LEFT, RIGHT, 0, 0),
            portal(11, RIGHT, LEFT, 0, 0));
    var selected = selector.select(LEFT, 0, candidates);
    assertEquals(List.of(1, 3, 10, 11), selected.entities());
    assertEquals(4, selected.visibleEntities());
    assertFalse(hidden(selected, 0));
    assertFalse(hidden(selected, 1));
  }

  @Test
  void portalRangeRestrictsRemoteViewAndBroadcastDoesNotOpenIt() {
    var map = map(new byte[] {1, 2, 4}, true);
    var selector = new SnapshotVisibility(map, new AreaConnectivity(map));
    var atRange =
        new SnapshotVisibility.Candidate(
            10, box(-6, -4), SnapshotVisibility.PORTAL, -1, new Vec3(0, 0, 0), RIGHT, 5);
    assertEquals(
        List.of(3, 10), selector.select(LEFT, 0, List.of(entity(3, 15), atRange)).entities());
    var tooFar =
        new SnapshotVisibility.Candidate(
            10, box(-6, -4), SnapshotVisibility.PORTAL, -1, new Vec3(1, 0, 0), RIGHT, 5);
    var limited = selector.select(LEFT, 0, List.of(entity(3, 15), tooFar));
    assertEquals(List.of(10), limited.entities());
    assertTrue(hidden(limited, 1));
    var broadcastPortal = portal(10, RIGHT, RIGHT, 0, SnapshotVisibility.BROADCAST);
    var broadcast = selector.select(LEFT, 0, List.of(entity(3, 15), broadcastPortal));
    assertEquals(List.of(10), broadcast.entities());
    assertTrue(hidden(broadcast, 1));
  }

  @Test
  void absentPvsAndUnknownViewpointsAreConservative() {
    var map = map(null, false);
    var selector = new SnapshotVisibility(map, new AreaConnectivity(map));
    assertEquals(
        List.of(1, 2, 3),
        selector.select(LEFT, 0, List.of(entity(1, -5), entity(2, 5), entity(3, 15))).entities());
    var closedMap = map(new byte[] {1, 2, 4}, true);
    var closed = new SnapshotVisibility(closedMap, new AreaConnectivity(closedMap));
    var outside =
        closed.select(
            new Vec3(-200, 0, 0),
            0,
            List.of(entity(3, 15), flagged(4, SnapshotVisibility.NOCLIENT, -1)));
    assertEquals(List.of(3), outside.entities());
    assertArrayEquals(new byte[32], outside.areaMask().copy());
    var noTree =
        new BspMap(
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
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0])));
    var missing = new SnapshotVisibility(noTree, new AreaConnectivity(noTree));
    assertEquals(List.of(3), missing.select(LEFT, 0, List.of(entity(3, 15))).entities());
    assertArrayEquals(new byte[32], missing.select(LEFT, 0, List.of()).areaMask().copy());
  }

  @Test
  void snapshotCapacityIsStableAndReportsEveryOmittedEntity() {
    var map = map(new byte[] {7, 7, 7}, false);
    var selector = new SnapshotVisibility(map, new AreaConnectivity(map));
    var candidates = new ArrayList<SnapshotVisibility.Candidate>();
    for (int i = 0; i < 301; i++) candidates.add(entity(i, -5));
    Collections.reverse(candidates);
    var selected = selector.select(LEFT, 0, candidates);
    assertEquals(300, selected.visibleEntities());
    assertEquals(44, selected.omittedEntities());
    assertEquals(256, selected.entities().size());
    assertEquals(1, selected.entities().getFirst());
    assertEquals(256, selected.entities().getLast());
    assertThrows(UnsupportedOperationException.class, () -> selected.entities().add(999));
    assertThrows(
        IllegalArgumentException.class,
        () -> selector.select(LEFT, 0, List.of(entity(1, -5), entity(1, 15))));
  }

  @Test
  void snapshotByteStorageDoesNotAliasProducerOrConsumerArrays() {
    byte[] bytes = new byte[208], mask = new byte[32];
    bytes[0] = 3;
    var snapshot = new Q3Server.EntitySnapshot(List.of(bytes), new BspMap.Bytes(mask), 1, 0);
    bytes[0] = 4;
    snapshot.entities().getFirst()[0] = 5;
    snapshot.areaMask().copy()[0] = 1;
    assertEquals(3, snapshot.entities().getFirst()[0]);
    assertEquals(0, snapshot.areaMask().unsigned(0));
  }

  private static boolean hidden(SnapshotVisibility.Selection selected, int area) {
    return (selected.areaMask().unsigned(area / 8) & (1 << (area & 7))) != 0;
  }

  private static SnapshotVisibility.Candidate flagged(int number, int flags, int client) {
    return new SnapshotVisibility.Candidate(number, box(-6, -4), flags, client, LEFT, LEFT, 0);
  }

  private static SnapshotVisibility.Candidate entity(int number, double x) {
    Vec3 origin = new Vec3(x, 0, 0);
    return new SnapshotVisibility.Candidate(number, box(x - .5, x + .5), 0, -1, origin, origin, 0);
  }

  private static SnapshotVisibility.Candidate portal(
      int number, Vec3 origin, Vec3 target, int range, int additionalFlags) {
    return new SnapshotVisibility.Candidate(
        number,
        box(origin.x() - .5, origin.x() + .5),
        SnapshotVisibility.PORTAL | additionalFlags,
        -1,
        origin,
        target,
        range);
  }

  private static BspMap.Bounds box(double left, double right) {
    return new BspMap.Bounds(new Vec3(left, -100, -100), new Vec3(right, 100, 100));
  }

  private static BspMap map(byte[] pvs, boolean separateAreas) {
    var planes =
        List.of(new BspMap.Plane(new Vec3(1, 0, 0), 0), new BspMap.Plane(new Vec3(1, 0, 0), 10));
    var nodes = List.of(new BspMap.Node(0, 1, -1, WORLD), new BspMap.Node(1, -3, -2, WORLD));
    var leaves =
        List.of(
            new BspMap.Leaf(0, 0, WORLD, 0, 0, 0, 0),
            new BspMap.Leaf(1, 0, WORLD, 0, 0, 0, 0),
            new BspMap.Leaf(2, separateAreas ? 1 : 0, WORLD, 0, 0, 0, 0));
    return new BspMap(
        List.of(),
        List.of(),
        planes,
        nodes,
        leaves,
        List.of(),
        List.of(),
        List.of(new BspMap.Model(WORLD, 0, 0, 0, 0)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        pvs == null
            ? new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0]))
            : new BspMap.Visibility(3, 1, new BspMap.Bytes(pvs)));
  }
}
