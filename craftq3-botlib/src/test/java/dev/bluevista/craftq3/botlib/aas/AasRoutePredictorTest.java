package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AasRoutePredictorTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void accumulatesTheOriginalAreaMetricAndOriginAcrossLaterAreas() {
    var predictor = predictor(chain());
    var result = predictor.predict(request(0, 0, 0, 0, 0, 0));
    assertTrue(result.success());
    assertEquals(
        new AasRoutePredictor.Prediction(new Vec3(35, 0, 0), 4, 0, 0, TravelFlags.WALK, 81),
        result.prediction());
    // Area one is crouch-only. Later areas are standing: changing the metric after entry
    // would incorrectly charge 7 and 10 instead of the observed 27 and 40.
  }

  @Test
  void strictTimeLimitStopsAfterTheWholeLinkAndGoalSuccessWinsTheLimit() {
    var predictor = predictor(chain());
    assertEquals(14, predictor.predict(request(1, 0, 0, 0, 0, 0)).prediction().time());
    var equal = predictor.predict(request(0, 14, 0, 0, 0, 0));
    assertFalse(equal.success());
    assertEquals(41, equal.prediction().time());
    assertEquals(3, equal.prediction().endArea());
    assertEquals(14, predictor.predict(request(0, 13, 0, 0, 0, 0)).prediction().time());
    assertTrue(predictor.predict(request(0, 80, 0, 0, 0, 0)).success());
    var negative = predictor.predict(request(-1, 0, 0, 0, 0, 0));
    assertFalse(negative.success());
    assertEquals(new AasRoutePredictor.Prediction(ZERO, 4, 0, 0, 0, 0), negative.prediction());
  }

  @Test
  void stopPrecedenceAndPositionsMatchTheObservedPartialTraversal() {
    var predictor = predictor(chain());
    var travel = predictor.predict(request(0, 0, 14, -1, TravelFlags.WALK | TravelFlags.WATER, 2));
    assertEquals(
        new AasRoutePredictor.Prediction(new Vec3(10, 0, 0), 1, 2, 0, TravelFlags.WALK, 0),
        travel.prediction());
    var water = predictor.predict(request(0, 0, 14, -1, TravelFlags.WATER, 2));
    assertEquals(
        new AasRoutePredictor.Prediction(new Vec3(15, 0, 0), 2, 2, 1, TravelFlags.WATER, 14),
        water.prediction());
    var contents = predictor.predict(request(0, 0, 12, 1, 0, 2));
    assertEquals(
        new AasRoutePredictor.Prediction(new Vec3(15, 0, 0), 2, 4, 1, 0, 14),
        contents.prediction());
    var area = predictor.predict(request(0, 0, 8, 0, 0, 3));
    assertEquals(
        new AasRoutePredictor.Prediction(new Vec3(20, 0, 0), 3, 8, 2, TravelFlags.WALK, 14),
        area.prediction());
    assertTrue(travel.success());
    assertTrue(contents.success());
  }

  @Test
  void invalidAndUnreachableRoutesPreserveInitializationWhileSameAreaSucceeds() {
    var predictor = predictor(chain());
    var bad =
        new AasRoutePredictor.Request(
            0, new Vec3(1, 2, 3), 4, TravelPolicy.defaults(), 0, 0, 0, 0, 0, 0);
    assertEquals(
        new AasRoutePredictor.Result(
            false, new AasRoutePredictor.Prediction(new Vec3(1, 2, 3), 4, 1, 0, 0, 0)),
        predictor.predict(bad));
    var same =
        new AasRoutePredictor.Request(1, ZERO, 1, TravelPolicy.ofFlags(0), 1, -1, 15, -1, -1, 1);
    assertEquals(
        new AasRoutePredictor.Result(true, new AasRoutePredictor.Prediction(ZERO, 1, 0, 0, 0, 0)),
        predictor.predict(same));
    var disabled =
        new AasRoutePredictor.Request(
            1,
            ZERO,
            4,
            new TravelPolicy(TravelFlags.ALL, 6, TravelPolicy.Team.ANY, Set.of(2)),
            0,
            0,
            0,
            0,
            0,
            0);
    assertEquals(1, predictor.predict(disabled).prediction().stopEvent());
  }

  @Test
  void portalZeroTimeCanStillSelectTheFirstStoredLinkWithoutChangingTravelTimes() {
    var map = portalChain();
    var times = new AasRouteTimes(map);
    assertEquals(new AasRouteTimes.Route(0, 0), times.route(2, ZERO, 3, 0));
    var predictor = new AasRoutePredictor(new AasNavigation(map), times);
    var request =
        new AasRoutePredictor.Request(2, ZERO, 3, TravelPolicy.ofFlags(0), 1, 0, 0, 0, 0, 0);
    var result = predictor.predict(request);
    assertTrue(result.success());
    assertEquals(3, result.prediction().endArea());
    assertEquals(TravelFlags.WALK, result.prediction().endTravelFlags());
    var invalid =
        new AasRoutePredictor.Request(2, ZERO, 0, TravelPolicy.ofFlags(0), 1, 0, 0, 0, 0, 0);
    assertEquals(1, predictor.predict(invalid).prediction().stopEvent());
  }

  @Test
  void cyclicPortalFallbackIsCappedByMapAreaCountEvenWhenTheCallerAsksForMore() {
    var original = portalChain();
    var reaches = new ArrayList<>(original.reachabilities());
    reaches.set(2, new Reachability(2, 0, 0, new Vec3(20, 0, 0), new Vec3(25, 0, 0), 2, 1, 0));
    var map =
        map(
            original.areas(),
            original.areaSettings(),
            reaches,
            List.of(),
            List.of(),
            original.portals(),
            new int[] {1, 1},
            original.clusters());
    var predictor = new AasRoutePredictor(new AasNavigation(map), new AasRouteTimes(map), 5);
    for (int limit : new int[] {0, 5, 6, Integer.MAX_VALUE}) {
      var request =
          new AasRoutePredictor.Request(2, ZERO, 4, TravelPolicy.ofFlags(0), limit, 0, 0, 0, 0, 0);
      var result = predictor.predict(request);
      assertFalse(result.success());
      assertEquals(35, result.prediction().time());
      assertEquals(2, result.prediction().endArea());
      assertEquals(0, result.prediction().stopEvent());
    }
  }

  @Test
  void crossingStopsInspectBarrierVerticalAreasBeforeTheReachDestination() {
    var map = crossedChain();
    var predictor = predictor(map);
    var request =
        new AasRoutePredictor.Request(
            1, new Vec3(4, 0, -10), 3, TravelPolicy.ofFlags(TravelFlags.ALL), 1, 0, 12, 1, 0, 3);
    var result = predictor.predict(request);
    assertTrue(result.success());
    assertEquals(2, result.prediction().endArea());
    assertEquals(4, result.prediction().stopEvent());
    assertEquals(new Vec3(6, 0, 20), result.prediction().endPosition());
    assertEquals(0, result.prediction().endTravelFlags());
  }

  @Test
  void waterJumpCrossingUsesTheStartHorizontalPositionIncludingItsWaterArea() {
    var predictor = predictor(crossedChain(9));
    var request =
        new AasRoutePredictor.Request(
            1, new Vec3(4, 0, -10), 3, TravelPolicy.ofFlags(TravelFlags.ALL), 1, 0, 12, 1, 0, 3);
    var result = predictor.predict(request);
    assertTrue(result.success());
    assertEquals(2, result.prediction().endArea());
    assertEquals(AasRoutePredictor.ENTER_CONTENTS, result.prediction().stopEvent());
    assertEquals(new Vec3(6, 0, 20), result.prediction().endPosition());
    assertEquals(11, result.prediction().time());
  }

  @Test
  void abiWriterPreservesUnassignedNumAreasAndCallerBufferState() {
    var result = predictor(chain()).predict(request(0, 0, 0, 0, 0, 0)).prediction();
    var bytes = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte) 0xa5);
    bytes.position(3);
    bytes.limit(44);
    result.writeTo(bytes, 4);
    assertEquals(3, bytes.position());
    assertEquals(ByteOrder.BIG_ENDIAN, bytes.order());
    var view = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(35, view.getFloat(4));
    assertEquals(4, view.getInt(16));
    assertEquals(0xa5a5a5a5, view.getInt(32));
    assertEquals(81, view.getInt(36));
    assertEquals((byte) 0xa5, bytes.get(40));
    byte[] before = bytes.array().clone();
    assertThrows(IndexOutOfBoundsException.class, () -> result.writeTo(bytes, 9));
    assertArrayEquals(before, bytes.array());
  }

  @Test
  void workBoundsAndFiniteInputAreExplicit() {
    var map = chain();
    var predictor = new AasRoutePredictor(new AasNavigation(map), new AasRouteTimes(map), 2);
    assertThrows(IllegalStateException.class, () -> predictor.predict(request(0, 0, 0, 0, 0, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AasRoutePredictor.Request(
                1, new Vec3(1e100, 0, 0), 4, TravelPolicy.defaults(), 0, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AasRoutePredictor(new AasNavigation(map), new AasRouteTimes(chain())));
  }

  private static AasRoutePredictor predictor(AasMap map) {
    return new AasRoutePredictor(new AasNavigation(map), new AasRouteTimes(map));
  }

  private static AasRoutePredictor.Request request(
      int maxAreas, int maxTime, int events, int contents, int travel, int area) {
    return new AasRoutePredictor.Request(
        1,
        ZERO,
        4,
        TravelPolicy.ofFlags(TravelFlags.ALL),
        maxAreas,
        maxTime,
        events,
        contents,
        travel,
        area);
  }

  private static AasMap chain() {
    var areas = new ArrayList<Area>();
    var settings = new ArrayList<AreaSettings>();
    var reaches = new ArrayList<Reachability>();
    areas.add(new Area(0, 0, 0, ZERO, ZERO, ZERO));
    settings.add(new AreaSettings(0, 0, 0, 0, 0, 0, 0));
    reaches.add(new Reachability(0, 0, 0, ZERO, ZERO, 1, 0, 0));
    for (int i = 1; i <= 4; i++) {
      areas.add(new Area(i, 0, 0, new Vec3(-100, -100, -100), new Vec3(100, 100, 100), ZERO));
      settings.add(
          new AreaSettings(i == 2 ? 1 : i == 3 ? 2 : 0, 1, i == 1 ? 4 : 6, 1, i - 1, 1, i));
      reaches.add(
          new Reachability(
              Math.min(i + 1, 4),
              0,
              0,
              new Vec3(i * 10, 0, 0),
              new Vec3(i * 10 + 5, 0, 0),
              2,
              1,
              0));
    }
    return map(
        areas,
        settings,
        reaches,
        List.of(),
        List.of(),
        List.of(new Portal(0, 0, 0, 0, 0)),
        new int[0],
        List.of(new Cluster(0, 0, 0, 0), new Cluster(4, 4, 0, 0)));
  }

  private static AasMap portalChain() {
    var m = chain();
    var settings = new ArrayList<>(m.areaSettings());
    settings.set(1, new AreaSettings(0, 1, 6, 1, 0, 1, 1));
    settings.set(2, new AreaSettings(8, 1, 6, -1, 0, 1, 2));
    settings.set(3, new AreaSettings(0, 1, 6, 2, 1, 1, 3));
    settings.set(4, new AreaSettings(0, 1, 6, 2, 2, 1, 4));
    return map(
        m.areas(),
        settings,
        m.reachabilities(),
        List.of(),
        List.of(),
        List.of(new Portal(0, 0, 0, 0, 0), new Portal(2, 1, 2, 1, 0)),
        new int[] {1, 1},
        List.of(new Cluster(0, 0, 0, 0), new Cluster(2, 2, 1, 0), new Cluster(3, 3, 1, 1)));
  }

  private static AasMap crossedChain() {
    return crossedChain(4);
  }

  private static AasMap crossedChain(int type) {
    var m = chain();
    var reaches = new ArrayList<>(m.reachabilities());
    reaches.set(1, new Reachability(3, 0, 0, new Vec3(4, 0, -10), new Vec3(6, 0, 20), type, 10, 0));
    return map(
        m.areas(),
        m.areaSettings(),
        reaches,
        List.of(
            new Plane(new Vec3(0, 0, 1), 0, 2),
            new Plane(new Vec3(0, 0, 1), 10, 2),
            new Plane(new Vec3(1, 0, 0), 5, 0)),
        List.of(new Node(0, 0, 0), new Node(2, -4, 2), new Node(0, 3, -1), new Node(1, -3, -2)),
        m.portals(),
        new int[0],
        m.clusters());
  }

  private static AasMap map(
      List<Area> areas,
      List<AreaSettings> settings,
      List<Reachability> reaches,
      List<Plane> planes,
      List<Node> nodes,
      List<Portal> portals,
      int[] portalIndices,
      List<Cluster> clusters) {
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        List.of(),
        planes,
        List.of(),
        new Indices(new int[0]),
        List.of(),
        new Indices(new int[0]),
        areas,
        settings,
        reaches,
        nodes,
        portals,
        new Indices(portalIndices),
        clusters);
  }
}
