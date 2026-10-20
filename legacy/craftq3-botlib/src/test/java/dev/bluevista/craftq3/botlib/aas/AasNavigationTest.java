package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasNavigation.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AasNavigationTest {
  private final AasNavigation navigation = new AasNavigation(NavigationFixture.map());

  @Test
  void pointQueriesResolveAxesSolidAndPlaneBoundary() {
    assertEquals(1, navigation.pointArea(new Vec3(5, 5, 0)));
    assertEquals(2, navigation.pointArea(new Vec3(5, -5, 0)));
    assertEquals(3, navigation.pointArea(new Vec3(-5, 5, 0)));
    assertEquals(4, navigation.pointArea(new Vec3(-5, -5, 0)));
    assertEquals(0, navigation.pointArea(new Vec3(-11, 0, 0)));
    assertEquals(4, navigation.pointArea(new Vec3(0, 0, 0)));
    assertEquals(0, navigation.pointArea(new Vec3(-10, 0, 0)));
  }

  @Test
  void pointClassificationUsesGuestFloatCoordinatesBeforeTestingThePlane() {
    assertEquals(3, navigation.pointArea(new Vec3(Double.MIN_VALUE, 1, 0)));
    assertEquals(1, navigation.pointArea(new Vec3(Float.MIN_VALUE, 1, 0)));
    assertEquals(0, navigation.pointArea(new Vec3(Math.nextUp(-10.0), 1, 0)));
    assertEquals(3, navigation.pointArea(new Vec3(Math.nextUp(-10.0f), 1, 0)));
    assertThrows(IllegalArgumentException.class, () -> navigation.pointArea(new Vec3(1e100, 0, 0)));
    var stationary =
        navigation.traceAreas(
            new Vec3(Double.MIN_VALUE, 1, 0), new Vec3(Double.MIN_VALUE, 1, 0), 4);
    assertEquals(3, stationary.spans().getFirst().area());
  }

  @Test
  void boxQueriesIncludeTouchingSidesDeduplicateAndReportTruncation() {
    var one = navigation.boxAreas(new Vec3(1, 1, -1), new Vec3(2, 2, 1), 4);
    assertEquals(List.of(1), one.areas());
    assertTrue(one.complete());
    var all = navigation.boxAreas(new Vec3(-5, -5, -1), new Vec3(5, 5, 1), 4);
    assertEquals(List.of(1, 2, 3, 4), all.areas());
    assertTrue(all.complete());
    assertEquals(
        List.of(1, 2, 3, 4), navigation.boxAreas(new Vec3(0, 0, 0), new Vec3(0, 0, 0), 4).areas());
    assertTrue(
        navigation.boxAreas(new Vec3(-20, -1, -1), new Vec3(-11, 1, 1), 4).areas().isEmpty());
    assertTrue(navigation.boxAreas(new Vec3(1, 1, 20), new Vec3(2, 2, 21), 4).areas().isEmpty());
    var limited = navigation.boxAreas(new Vec3(-5, -5, -1), new Vec3(5, 5, 1), 2);
    assertEquals(2, limited.areas().size());
    assertFalse(limited.complete());
    var visited =
        navigation.boxAreas(new Vec3(-5, -5, -1), new Vec3(5, 5, 1), new QueryBudget(4, 1));
    assertFalse(visited.complete());
    assertEquals(1, visited.nodeVisits());
    assertThrows(UnsupportedOperationException.class, () -> all.areas().clear());
  }

  @Test
  void tracesReturnOrderedIntervalsAndContinueThroughSolid() {
    var trace = navigation.traceAreas(new Vec3(-20, 5, 0), new Vec3(10, 5, 0), 4);
    assertTrue(trace.complete());
    assertTrue(trace.startSolid());
    assertEquals(1, trace.endArea());
    assertEquals(List.of(3, 1), trace.spans().stream().map(AreaSpan::area).toList());
    assertEquals(1.0 / 3, trace.spans().getFirst().enterFraction(), 1e-12);
    assertEquals(2.0 / 3, trace.spans().getFirst().exitFraction(), 1e-12);
    assertEquals(-10, trace.spans().getFirst().entry().x(), 1e-12);
    assertEquals(5, trace.spans().getFirst().entry().y(), 1e-12);
    var reversed = navigation.traceAreas(new Vec3(10, 5, 0), new Vec3(-20, 5, 0), 4);
    assertEquals(List.of(1, 3), reversed.spans().stream().map(AreaSpan::area).toList());
    assertFalse(reversed.startSolid());
    assertEquals(0, reversed.endArea());
    assertEquals(0, reversed.spans().getFirst().enterFraction());
    assertFalse(navigation.traceAreas(new Vec3(-20, 5, 0), new Vec3(10, 5, 0), 1).complete());
    assertFalse(
        navigation
            .traceAreas(new Vec3(-20, 5, 0), new Vec3(10, 5, 0), new QueryBudget(4, 1))
            .complete());
    assertThrows(UnsupportedOperationException.class, () -> trace.spans().clear());
  }

  @Test
  void traceZeroLengthAndOnPlaneUseBackWithoutZeroLengthNeighbors() {
    var point = navigation.traceAreas(new Vec3(0, 0, 0), new Vec3(0, 0, 0), 4);
    assertEquals(1, point.spans().size());
    assertEquals(4, point.spans().getFirst().area());
    var fromBoundary = navigation.traceAreas(new Vec3(0, 5, 0), new Vec3(-5, 5, 0), 4);
    assertEquals(List.of(3), fromBoundary.spans().stream().map(AreaSpan::area).toList());
    var onPlane = navigation.traceAreas(new Vec3(-5, 0, 0), new Vec3(5, 0, 0), 4);
    assertEquals(List.of(4, 2), onPlane.spans().stream().map(AreaSpan::area).toList());
  }

  @Test
  void randomTraceMidpointsAgreeWithPointClassification() {
    Random random = new Random(731);
    for (int i = 0; i < 1000; i++) {
      Vec3 a = new Vec3(random.nextDouble(-20, 10), random.nextDouble(-10, 10), 0);
      Vec3 b = new Vec3(random.nextDouble(-20, 10), random.nextDouble(-10, 10), 0);
      AreaTrace trace = navigation.traceAreas(a, b, 8);
      assertTrue(trace.complete());
      double last = 0;
      for (AreaSpan span : trace.spans()) {
        assertTrue(span.enterFraction() >= last);
        assertTrue(span.exitFraction() > span.enterFraction());
        Vec3 mid = span.entry().add(span.exit()).scale(0.5);
        assertEquals(span.area(), navigation.pointArea(mid));
        last = span.exitFraction();
      }
    }
  }

  @Test
  void routesMinimizeStoredCostsWithDeterministicLinksAndMasks() {
    var route = navigation.route(1, 4, TravelPolicy.defaults(), SearchBudget.defaults());
    assertEquals(RouteStatus.FOUND, route.status());
    assertEquals(15, route.travelTime());
    assertEquals(List.of(2, 6, 4), route.links().stream().map(ReachLink::index).toList());
    assertEquals(List.of(1, 3, 2), route.links().stream().map(ReachLink::sourceArea).toList());
    var walking = TravelPolicy.ofFlags(TravelFlags.WALK | TravelFlags.AIR);
    assertEquals(45, navigation.route(1, 4, walking, SearchBudget.defaults()).travelTime());
    assertEquals(2, navigation.reachabilities(1, walking).size());
    assertEquals(0, navigation.route(1, 1, walking, SearchBudget.defaults()).travelTime());
    assertEquals(
        RouteStatus.UNREACHABLE, navigation.route(4, 1, walking, SearchBudget.defaults()).status());
    assertThrows(UnsupportedOperationException.class, () -> route.links().clear());
  }

  @Test
  void routingBudgetsAreExplicitAndTravelTimesDoNotWrapAtUnsignedShort() {
    assertEquals(
        RouteStatus.BUDGET_EXCEEDED,
        navigation
            .route(1, 4, TravelPolicy.defaults(), new SearchBudget(1, 100, Long.MAX_VALUE))
            .status());
    assertEquals(
        RouteStatus.BUDGET_EXCEEDED,
        navigation
            .route(1, 4, TravelPolicy.defaults(), new SearchBudget(10, 1, Long.MAX_VALUE))
            .status());
    assertEquals(
        RouteStatus.BUDGET_EXCEEDED,
        navigation.route(1, 4, TravelPolicy.defaults(), new SearchBudget(10, 100, 14)).status());
    assertEquals(
        RouteStatus.FOUND,
        navigation.route(1, 4, TravelPolicy.defaults(), new SearchBudget(10, 100, 15)).status());
    var map = NavigationFixture.map();
    var reaches = new ArrayList<>(map.reachabilities());
    reaches.set(1, NavigationFixture.link(2, 2, 65535));
    reaches.set(2, NavigationFixture.link(3, 1, 0));
    reaches.set(3, NavigationFixture.link(4, 1, 0));
    reaches.set(4, NavigationFixture.link(4, 2, 65535));
    var modified = new AasNavigation(NavigationFixture.with(map, map.areaSettings(), reaches));
    assertEquals(
        131070,
        modified.route(1, 4, TravelPolicy.defaults(), SearchBudget.defaults()).travelTime());
    reaches.set(1, NavigationFixture.link(2, 2, 0));
    var cycle = new AasNavigation(NavigationFixture.with(map, map.areaSettings(), reaches));
    assertEquals(
        65535, cycle.route(1, 4, TravelPolicy.defaults(), SearchBudget.defaults()).travelTime());
  }

  @Test
  void policyFiltersLiquidsPresenceDisabledAreasTeamsAndUnknownKinds() {
    var basic = TravelPolicy.defaults();
    var all = TravelPolicy.ofFlags(TravelFlags.ALL);
    var ground = new AasMap.AreaSettings(0, 1, 2, 0, 0, 0, 0);
    var water = new AasMap.AreaSettings(1, 4, 2, 0, 0, 0, 0);
    var lava = new AasMap.AreaSettings(2, 4, 2, 0, 0, 0, 0);
    assertTrue(basic.permitsArea(1, ground));
    assertTrue(basic.permitsArea(1, water));
    assertFalse(basic.permitsArea(1, lava));
    assertTrue(all.permitsArea(1, lava));
    assertFalse(basic.permitsArea(1, new AasMap.AreaSettings(0, 8, 2, 0, 0, 0, 0)));
    assertFalse(
        new TravelPolicy(TravelFlags.ALL, 4, TravelPolicy.Team.ANY, Set.of())
            .permitsArea(1, ground));
    var disabled = new TravelPolicy(TravelFlags.ALL, 6, TravelPolicy.Team.ANY, Set.of(3));
    assertEquals(45, navigation.route(1, 4, disabled, SearchBudget.defaults()).travelTime());
    var restricted = NavigationFixture.link(2, 2 | 0x01000000, 1);
    assertFalse(basic.permitsReachability(restricted));
    assertTrue(all.permitsReachability(restricted));
    assertFalse(
        new TravelPolicy(TravelFlags.ALL, 6, TravelPolicy.Team.ONE, Set.of())
            .permitsReachability(restricted));
    assertTrue(
        new TravelPolicy(TravelFlags.ALL, 6, TravelPolicy.Team.TWO, Set.of())
            .permitsReachability(restricted));
    assertFalse(all.permitsReachability(NavigationFixture.link(2, 1, 1)));
    assertFalse(all.permitsReachability(NavigationFixture.link(2, 31, 1)));
    assertFalse(all.permitsReachability(NavigationFixture.link(2, 0x40000002, 1)));
    assertEquals(0x80, TravelFlags.forTravelType(7));
    assertEquals(0x1000000, TravelFlags.forTravelType(19));
    assertEquals(
        TravelFlags.WALK | TravelFlags.NOT_TEAM_1, TravelFlags.forReachability(restricted));
  }

  @Test
  void rejectsInvalidInputsAndCopiesPolicyState() {
    assertThrows(
        IllegalArgumentException.class,
        () -> navigation.boxAreas(new Vec3(1, 0, 0), new Vec3(0, 0, 0), 4));
    assertThrows(IllegalArgumentException.class, () -> new QueryBudget(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new SearchBudget(1, 1, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> navigation.route(0, 1, TravelPolicy.defaults(), SearchBudget.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () -> navigation.reachabilities(99, TravelPolicy.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TravelPolicy(0, 1, TravelPolicy.Team.ANY, Set.of()));
    var mutable = new java.util.HashSet<Integer>();
    mutable.add(3);
    var policy = new TravelPolicy(TravelFlags.ALL, 6, TravelPolicy.Team.ANY, mutable);
    mutable.clear();
    assertEquals(Set.of(3), policy.disabledAreas());
    assertThrows(UnsupportedOperationException.class, () -> policy.disabledAreas().clear());
  }
}
