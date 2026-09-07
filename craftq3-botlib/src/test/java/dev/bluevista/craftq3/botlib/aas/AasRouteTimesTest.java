package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AasRouteTimesTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void includesLocalDistanceAndCachesTheChosenClusterExit() {
    var map = chain(false, false);
    var reaches = new ArrayList<>(map.reachabilities());
    reaches.set(1, new Reachability(2, 0, 0, new Vec3(10, 0, 0), new Vec3(20, 0, 0), 2, 5, 0));
    reaches.set(2, new Reachability(3, 0, 0, new Vec3(50, 0, 0), new Vec3(60, 0, 0), 2, 7, 0));
    var route = new AasRouteTimes(with(map, map.areaSettings(), reaches));
    assertEquals(25, route.travelTime(1, ZERO, 3, TravelFlags.DEFAULT));
    assertEquals(new AasRouteTimes.Route(25, 1), route.route(1, ZERO, 3, TravelFlags.DEFAULT));
    assertEquals(23, route.travelTime(1, new Vec3(10, 0, 0), 3, TravelFlags.DEFAULT));
    assertEquals(1, route.cacheStats().clusterBuilds());
    assertEquals(1, route.travelTime(1, ZERO, 1, 0));
    assertEquals(0, route.travelTime(0, ZERO, 1, TravelFlags.DEFAULT));
    assertEquals(0, route.travelTime(3, ZERO, 1, TravelFlags.DEFAULT));
    assertEquals(new AasRouteTimes.Route(0, 0), route.route(3, ZERO, 1, TravelFlags.DEFAULT));
    assertEquals(new AasRouteTimes.Route(1, 0), route.route(1, ZERO, 1, 0));
  }

  @Test
  void hierarchyAddsPortalMaximaAndClusterBaselines() {
    var route = new AasRouteTimes(chain(true, false));
    assertEquals(44, route.travelTime(1, ZERO, 5, TravelFlags.DEFAULT));
    assertEquals(29, route.travelTime(3, ZERO, 5, TravelFlags.DEFAULT));
    assertEquals(29, route.travelTime(1, ZERO, 4, TravelFlags.DEFAULT));
    assertEquals(13, route.travelTime(3, ZERO, 4, TravelFlags.DEFAULT));
    assertTrue(route.cacheStats().portalBuilds() > 0);
  }

  @Test
  void portalStartsUseOriginOnlyForAnOrdinaryAdjacentClusterGoal() {
    var route = new AasRouteTimes(chain(true, false));
    assertEquals(36, route.travelTime(2, ZERO, 5, TravelFlags.DEFAULT));
    assertEquals(36, route.travelTime(2, new Vec3(1000, 1000, 1000), 5, TravelFlags.DEFAULT));
    assertEquals(15, route.travelTime(4, ZERO, 5, TravelFlags.DEFAULT));
    assertEquals(113, route.travelTime(4, new Vec3(300, 0, 0), 5, TravelFlags.DEFAULT));
    assertEquals(21, route.travelTime(2, ZERO, 4, TravelFlags.DEFAULT));
    assertEquals(21, route.travelTime(2, new Vec3(300, 0, 0), 4, TravelFlags.DEFAULT));
  }

  @Test
  void remotePortalFirstEdgePreservesNativeStoredLinkSemantics() {
    var map = chain(true, false);
    var settings = new ArrayList<>(map.areaSettings());
    var reaches = new ArrayList<>(map.reachabilities());
    int first = reaches.size();
    reaches.add(new Reachability(3, 0, 0, ZERO, ZERO, 2, 100, 0));
    reaches.add(new Reachability(3, 0, 0, ZERO, ZERO, 2, 7, 0));
    settings.set(2, new AreaSettings(0, 1, 6, -1, 0, 2, first));
    var route = new AasRouteTimes(with(map, settings, reaches));
    assertEquals(new AasRouteTimes.Route(36, first), route.route(2, ZERO, 5, TravelFlags.DEFAULT));
    assertEquals(
        new AasRouteTimes.Route(9, first + 1), route.route(2, ZERO, 3, TravelFlags.DEFAULT));
    assertEquals(new AasRouteTimes.Route(21, first), route.route(2, ZERO, 4, TravelFlags.DEFAULT));
  }

  @Test
  void equalTimeExitsFollowStoredClusterPortalOrder() {
    assertEquals(
        new AasRouteTimes.Route(17, 2),
        new AasRouteTimes(parallelPortals(true)).route(1, ZERO, 3, TravelFlags.DEFAULT));
    assertEquals(
        new AasRouteTimes.Route(17, 1),
        new AasRouteTimes(parallelPortals(false)).route(1, ZERO, 3, TravelFlags.DEFAULT));
  }

  @Test
  void unreachableSameClusterRouteCanLeaveAndReenterThroughPortals() {
    var route = new AasRouteTimes(loop());
    // Both ordinary endpoints are in cluster 2, with their only route through cluster 1.
    assertEquals(44, route.travelTime(1, ZERO, 5, TravelFlags.DEFAULT));
    // The destination portal's back side is cluster 2. The remote route enters its front.
    assertEquals(29, route.travelTime(1, ZERO, 4, TravelFlags.DEFAULT));
  }

  @Test
  void availableDirectRouteTakesPriorityOverCheaperPortalDetour() {
    var map = loop();
    var reaches = new ArrayList<>(map.reachabilities());
    var settings = new ArrayList<>(map.areaSettings());
    // Give area 1 two contiguous outgoing links, retaining all other source ranges.
    reaches.add(reaches.get(1));
    reaches.add(new Reachability(4, 0, 0, ZERO, ZERO, 2, 100, 0));
    settings.set(1, new AreaSettings(0, 1, 6, 2, 0, 2, reaches.size() - 2));
    var route = new AasRouteTimes(with(map, settings, reaches));
    assertEquals(102, route.travelTime(1, ZERO, 4, TravelFlags.DEFAULT));
  }

  @Test
  void areaWithoutOutgoingReachabilitiesIsNotARouteGoalEvenWithIncomingLinks() {
    var map = chain(false, false);
    var settings = new ArrayList<>(map.areaSettings());
    settings.set(3, new AreaSettings(0, 1, 6, 1, 2, 0, 3));
    var route = new AasRouteTimes(with(map, settings, map.reachabilities()));
    assertEquals(0, route.travelTime(2, ZERO, 3, TravelFlags.ALL));
    assertEquals(1, route.travelTime(3, ZERO, 3, 0));
  }

  @Test
  void disabledAreasMayBeExitedButNotEnteredAndEndpointDoNotEnterIsAllowed() {
    var route = new AasRouteTimes(chain(false, false));
    var disabled = new TravelPolicy(TravelFlags.DEFAULT, 6, TravelPolicy.Team.ANY, Set.of(2));
    assertEquals(0, route.travelTime(1, ZERO, 3, disabled));
    assertEquals(9, route.travelTime(2, ZERO, 3, disabled));
    assertEquals(0, route.travelTime(1, ZERO, 2, disabled));
    assertEquals(15, route.travelTime(1, ZERO, 3, TravelFlags.DEFAULT));
    var forbidden = new AasRouteTimes(chain(false, true));
    assertEquals(0, forbidden.travelTime(1, ZERO, 3, TravelFlags.DEFAULT));
    assertEquals(7, forbidden.travelTime(1, ZERO, 2, TravelFlags.DEFAULT));
    assertEquals(9, forbidden.travelTime(2, ZERO, 3, TravelFlags.DEFAULT));
  }

  @Test
  void localFloatMetricMatchesNativeNormalLiquidAndCrouchProbes() {
    var map = chain(false, false);
    var settings = new ArrayList<>(map.areaSettings());
    settings.set(2, new AreaSettings(1, 4, 6, 1, 1, 1, 2));
    settings.set(3, new AreaSettings(1, 4, 4, 1, 2, 0, 3));
    var route = new AasRouteTimes(with(map, settings, map.reachabilities()));
    assertEquals(33, route.areaTravelTime(1, ZERO, new Vec3(100, 0, 0)));
    assertEquals(100, route.areaTravelTime(2, ZERO, new Vec3(100, 0, 0)));
    assertEquals(130, route.areaTravelTime(3, ZERO, new Vec3(100, 0, 0)));
    assertEquals(99, route.areaTravelTime(1, ZERO, new Vec3(300, 0, 0)));
    assertEquals(2320, route.areaTravelTime(1, ZERO, new Vec3(1_000_000, 0, 0)));
    assertEquals(1, route.areaTravelTime(1, ZERO, ZERO));
  }

  @Test
  void cacheAndWorkLimitsAreExplicitAndInputIsImmutable() {
    var map = chain(true, false);
    var route = new AasRouteTimes(map, new AasRouteTimes.Limits(1, 1024, 10000));
    assertEquals(44, route.travelTime(1, ZERO, 5, TravelFlags.DEFAULT));
    assertTrue(route.cacheStats().entries() <= 1);
    assertTrue(route.cacheStats().bytes() <= 1024);
    route.clearCaches();
    assertEquals(0, route.cacheStats().entries());
    assertEquals(0, route.cacheStats().bytes());
    var bounded = new AasRouteTimes(map, new AasRouteTimes.Limits(1, 1024, 3));
    assertThrows(
        IllegalStateException.class, () -> bounded.travelTime(1, ZERO, 5, TravelFlags.DEFAULT));
    assertThrows(
        IllegalArgumentException.class,
        () -> route.travelTime(1, new Vec3(1e100, 0, 0), 5, TravelFlags.DEFAULT));
    var reaches = new ArrayList<>(map.reachabilities());
    reaches.set(1, new Reachability(2, 0, 0, ZERO, ZERO, 2, 65535, 0));
    var overlong = new AasRouteTimes(with(map, map.areaSettings(), reaches));
    assertThrows(
        IllegalStateException.class, () -> overlong.travelTime(1, ZERO, 5, TravelFlags.DEFAULT));
  }

  private static AasMap chain(boolean portals, boolean doNotEnter) {
    int count = portals ? 5 : 3;
    var areas = new ArrayList<Area>();
    var settings = new ArrayList<AreaSettings>();
    var reaches = new ArrayList<Reachability>();
    areas.add(new Area(0, 0, 0, ZERO, ZERO, ZERO));
    settings.add(new AreaSettings(0, 0, 0, 0, 0, 0, 0));
    reaches.add(new Reachability(0, 0, 0, ZERO, ZERO, 1, 0, 0));
    for (int i = 1; i <= count; i++) {
      int cluster =
          portals
              ? switch (i) {
                case 1 -> 1;
                case 2 -> -1;
                case 3 -> 2;
                case 4 -> -2;
                default -> 3;
              }
              : 1;
      areas.add(new Area(i, 0, 0, new Vec3(-10, -10, -10), new Vec3(10, 10, 10), ZERO));
      settings.add(new AreaSettings(doNotEnter && i == 2 ? 256 : 0, 1, 6, cluster, i - 1, 1, i));
      reaches.add(
          new Reachability(
              i < count ? i + 1 : i,
              0,
              0,
              ZERO,
              ZERO,
              2,
              i < count ? new int[] {0, 5, 7, 11, 13}[i] : 1,
              0));
    }
    List<Portal> links =
        portals
            ? List.of(
                new Portal(0, 0, 0, 0, 0), new Portal(2, 1, 2, 1, 0), new Portal(4, 2, 3, 2, 0))
            : List.of(new Portal(0, 0, 0, 0, 0));
    List<Cluster> clusters =
        portals
            ? List.of(
                new Cluster(0, 0, 0, 0),
                new Cluster(2, 2, 1, 0),
                new Cluster(3, 3, 2, 1),
                new Cluster(2, 2, 1, 3))
            : List.of(new Cluster(0, 0, 0, 0), new Cluster(3, 3, 0, 0));
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Indices(new int[0]),
        List.of(),
        new Indices(new int[0]),
        areas,
        settings,
        reaches,
        List.of(),
        links,
        new Indices(portals ? new int[] {1, 1, 2, 2} : new int[0]),
        clusters);
  }

  private static AasMap with(AasMap m, List<AreaSettings> s, List<Reachability> r) {
    return new AasMap(
        m.version(),
        m.bspChecksum(),
        m.lumps(),
        m.boundingBoxes(),
        m.vertices(),
        m.planes(),
        m.edges(),
        m.edgeIndices(),
        m.faces(),
        m.faceIndices(),
        m.areas(),
        s,
        r,
        m.nodes(),
        m.portals(),
        m.portalIndices(),
        m.clusters());
  }

  private static AasMap loop() {
    var m = chain(true, false);
    var settings = new ArrayList<>(m.areaSettings());
    for (int a : new int[] {1, 3, 5}) {
      var s = settings.get(a);
      settings.set(
          a,
          new AreaSettings(
              s.contents(),
              s.flags(),
              s.presenceType(),
              a == 3 ? 1 : 2,
              s.clusterArea(),
              s.reachabilityCount(),
              s.firstReachability()));
    }
    var portals =
        List.of(new Portal(0, 0, 0, 0, 0), new Portal(2, 1, 2, 1, 1), new Portal(4, 1, 2, 2, 2));
    var clusters =
        List.of(new Cluster(0, 0, 0, 0), new Cluster(3, 3, 2, 0), new Cluster(4, 4, 2, 2));
    return new AasMap(
        m.version(),
        m.bspChecksum(),
        m.lumps(),
        m.boundingBoxes(),
        m.vertices(),
        m.planes(),
        m.edges(),
        m.edgeIndices(),
        m.faces(),
        m.faceIndices(),
        m.areas(),
        settings,
        m.reachabilities(),
        m.nodes(),
        portals,
        new Indices(new int[] {1, 2, 1, 2}),
        clusters);
  }

  private static AasMap parallelPortals(boolean reverse) {
    var m = loop();
    var settings =
        List.of(
            new AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AreaSettings(0, 1, 6, 1, 0, 2, 1),
            new AreaSettings(0, 1, 6, -1, 0, 1, 3),
            new AreaSettings(0, 1, 6, 2, 0, 1, 4),
            new AreaSettings(0, 1, 6, -2, 0, 1, 5),
            new AreaSettings(0, 1, 6, 2, 3, 1, 6));
    var reaches =
        List.of(
            new Reachability(0, 0, 0, ZERO, ZERO, 1, 0, 0),
            new Reachability(2, 0, 0, ZERO, ZERO, 2, 5, 0),
            new Reachability(4, 0, 0, ZERO, ZERO, 2, 5, 0),
            new Reachability(3, 0, 0, ZERO, ZERO, 2, 7, 0),
            new Reachability(3, 0, 0, ZERO, ZERO, 2, 1, 0),
            new Reachability(3, 0, 0, ZERO, ZERO, 2, 7, 0),
            new Reachability(5, 0, 0, ZERO, ZERO, 2, 1, 0));
    return new AasMap(
        m.version(),
        m.bspChecksum(),
        m.lumps(),
        m.boundingBoxes(),
        m.vertices(),
        m.planes(),
        m.edges(),
        m.edgeIndices(),
        m.faces(),
        m.faceIndices(),
        m.areas(),
        settings,
        reaches,
        m.nodes(),
        m.portals(),
        new Indices(reverse ? new int[] {2, 1, 1, 2} : new int[] {1, 2, 1, 2}),
        m.clusters());
  }
}
