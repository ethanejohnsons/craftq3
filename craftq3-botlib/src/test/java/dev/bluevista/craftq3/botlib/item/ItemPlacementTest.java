package dev.bluevista.craftq3.botlib.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasGoalLocator;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ItemPlacementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0),
      MIN = new Vec3(-15, -15, -15),
      MAX = new Vec3(15, 15, 15);
  private static final ItemInfo ITEM =
      new ItemInfo("item_test", "Test", "", 1, 0, 0, 30, MIN, MAX, 0);

  @Test
  void ordinaryDropUsesOneSuppliedBoxTraceAndAcceptsUnobstructedEnd() {
    var world = new World();
    var placement = new ItemPlacement(navigation(), world, ignored -> {});
    var origin = new Vec3(10, 20, 200);
    var result = placement.resolve(ITEM, origin, false).orElseThrow();
    assertEquals(new Vec3(10, 20, 100), result.origin());
    assertEquals(new Vec3(10, 20, 50), result.goalOrigin());
    assertEquals(1, result.area());
    assertEquals(
        List.of(new TraceRequest(origin, new Vec3(10, 20, 100), MIN, MAX, 1, 0)), world.traces);
    assertEquals(0, world.contentsQueries);
  }

  @Test
  void failedDropKeepsOriginalPositionWhileLivePlacementDoesNotDropAgain() {
    var world = new World();
    world.startSolid = true;
    world.fraction = .25;
    var placement = new ItemPlacement(navigation(), world, ignored -> {});
    var origin = new Vec3(10, 20, 200);
    var staticItem = placement.resolve(ITEM, origin, false).orElseThrow();
    assertEquals(origin, staticItem.origin());
    assertEquals(new Vec3(10, 20, 150), staticItem.goalOrigin());
    assertEquals(staticItem, placement.resolve(ITEM, origin));
    assertEquals(1, world.traces.size());
    assertTrue(placement.isJumpPadArea(2));
    assertFalse(placement.isJumpPadArea(1));
    assertFalse(placement.isJumpPadArea(0));
    assertFalse(placement.isJumpPadArea(99));
  }

  @Test
  void airborneSuspendedItemUsesExactShortTraceAndRetainsOriginalGoalPosition() {
    var world = new World();
    world.startSolid = true;
    var observed = new ArrayList<Vec3>();
    var placement =
        new ItemPlacement(
            navigation(),
            world,
            (origin, mins, maxs) -> {
              observed.add(origin);
              assertEquals(MIN, mins);
              assertEquals(MAX, maxs);
              return OptionalInt.of(2);
            },
            ignored -> {});
    var origin = new Vec3(10, 20, 100);
    var result = placement.resolve(ITEM, origin, true).orElseThrow();
    assertEquals(new ItemRegistry.Placement(origin, origin, 2), result);
    assertEquals(List.of(origin), observed);
    assertEquals(
        List.of(new TraceRequest(origin, new Vec3(10, 20, 68), MIN, MAX, 65537, -1)), world.traces);
    assertEquals(1, world.contentsQueries);
  }

  @Test
  void shortTraceObstructionUsesOrdinaryGoalAtOriginalOriginRegardlessOfStartSolid() {
    var world = new World();
    world.fraction = .5;
    world.startSolid = true;
    var placement =
        new ItemPlacement(
            navigation(),
            world,
            (origin, mins, maxs) -> {
              fail("Unexpected jump query");
              return OptionalInt.of(0);
            },
            ignored -> {});
    var origin = new Vec3(10, 20, 100);
    var result = placement.resolve(ITEM, origin, true).orElseThrow();
    assertEquals(origin, result.origin());
    assertEquals(new Vec3(10, 20, 50), result.goalOrigin());
    assertEquals(1, result.area());
  }

  @Test
  void waterAloneBypassesSuspendedTraceAndOtherContentsDoNot() {
    for (int contents : new int[] {1, 8, 16, 32, 56, 64}) {
      var world = new World();
      world.contents = contents;
      var placement =
          new ItemPlacement(
              navigation(), world, (origin, mins, maxs) -> OptionalInt.of(2), ignored -> {});
      var result = placement.resolve(ITEM, new Vec3(10, 20, 100), true).orElseThrow();
      boolean water = (contents & 32) != 0;
      assertEquals(water ? 0 : 1, world.traces.size());
      assertEquals(water ? 1 : 2, result.area());
    }
  }

  @Test
  void unreachableAndUnsupportedJumpQueriesRemainExplicitAndDoNotInventGoals() {
    var diagnostics = new ArrayList<String>();
    var world = new World();
    var unsupported = new ItemPlacement(navigation(), world, diagnostics::add);
    assertTrue(unsupported.resolve(ITEM, new Vec3(10, 20, 100), true).isEmpty());
    assertEquals(1, diagnostics.size());
    assertTrue(diagnostics.getFirst().contains("unavailable jump-pad trajectory"));
    var unreachable =
        new ItemPlacement(
            navigation(), world, (origin, mins, maxs) -> OptionalInt.of(0), diagnostics::add);
    assertTrue(unreachable.resolve(ITEM, new Vec3(10, 20, 100), true).isEmpty());
    assertEquals(1, diagnostics.size());
    var invalid =
        new ItemPlacement(
            navigation(), world, (origin, mins, maxs) -> OptionalInt.of(3), diagnostics::add);
    assertThrows(
        IllegalArgumentException.class, () -> invalid.resolve(ITEM, new Vec3(10, 20, 100), true));
  }

  @Test
  void solidPointRecoveryUsesObservedGridAndCrouchTraceToFloor() {
    var locator = new AasGoalLocator(navigation());
    var found = locator.bestReachableArea(new Vec3(10, 20, -8), MIN, MAX);
    assertEquals(1, found.area());
    assertEquals(10, found.origin().x());
    assertEquals(20, found.origin().y());
    assertEquals(.25, found.origin().z(), .00001);
    var clear = locator.bestReachableArea(new Vec3(10, 20, 60), MIN, MAX);
    assertEquals(new Vec3(10, 20, 10), clear.origin());
  }

  @Test
  void orderedLinkFallbackPrefersFirstGroundedOrLiquidWithoutRequiringReachabilities() {
    var locator = new AasGoalLocator(navigation());
    var origin = new Vec3(0, 0, -50);
    var maxs = new Vec3(15, 15, 100);
    assertEquals(List.of(1, 2), locator.linkedAreas(origin.add(MIN), origin.add(maxs), 4));
    var found = locator.bestReachableArea(origin, MIN, maxs);
    assertEquals(2, found.area());
    assertEquals(origin, found.origin());
    assertEquals(0, navigation().map().areaSettings().get(2).reachabilityCount());
    var absent = locator.bestReachableArea(new Vec3(0, 0, -200), MIN, MAX);
    assertEquals(0, absent.area());
    assertEquals(new Vec3(0, 0, -200), absent.origin());
    assertThrows(
        UnsupportedOperationException.class, () -> locator.linkedAreas(MIN, MAX, 4).clear());
  }

  @Test
  void startSolidPresenceTraceKeepsRaisedCandidateAndItsOriginalArea() {
    var source = navigation().map();
    var settings = new ArrayList<>(source.areaSettings());
    var old = settings.get(1);
    settings.set(1, new AasMap.AreaSettings(old.contents(), old.flags(), 2, 0, 0, 0, 0));
    var locator = new AasGoalLocator(new AasNavigation(copy(source, source.nodes(), settings)));
    assertEquals(
        new AasGoalLocator.GoalArea(1, new Vec3(10, 20, 100.25)),
        locator.bestReachableArea(new Vec3(10, 20, 100), MIN, MAX));
  }

  @Test
  void runtimeCrouchBoundsAndGeometryBudgetsAreIndependentOfStoredCompileBounds() {
    var source = navigation().map();
    var nodes = List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, -1, -2));
    var locator = new AasGoalLocator(new AasNavigation(copy(source, nodes, source.areaSettings())));
    assertEquals(List.of(1, 2), locator.linkedAreas(new Vec3(0, 0, 6), new Vec3(0, 0, 6), 4));
    assertEquals(4, source.boundingBoxes().get(1).max().z());
    var bounded = new AasGoalLocator(navigation(), 1);
    assertThrows(IllegalStateException.class, () -> bounded.linkedAreas(MIN, MAX, 4));
    assertThrows(IllegalArgumentException.class, () -> locator.linkedAreas(MAX, MIN, 4));
    assertThrows(IllegalArgumentException.class, () -> locator.linkedAreas(MIN, MAX, 8));
    assertThrows(
        IllegalArgumentException.class,
        () -> locator.bestReachableArea(new Vec3(1e10, 0, 0), MIN, MAX));
  }

  private static AasNavigation navigation() {
    var empty = new AasMap.Indices(new int[0]);
    var planes =
        List.of(
            new AasMap.Plane(new Vec3(0, 0, 1), 0, 2),
            new AasMap.Plane(new Vec3(0, 0, -1), 0, 2),
            new AasMap.Plane(new Vec3(1, 0, 0), 0, 0),
            new AasMap.Plane(new Vec3(-1, 0, 0), 0, 0));
    var nodes =
        List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, 2, 0), new AasMap.Node(2, -1, -2));
    var areas =
        List.of(
            new AasMap.Area(0, 0, 0, ZERO, ZERO, ZERO),
            new AasMap.Area(1, 0, 0, ZERO, new Vec3(100, 100, 100), new Vec3(50, 50, 50)),
            new AasMap.Area(
                2, 0, 0, new Vec3(-100, 0, 0), new Vec3(0, 100, 100), new Vec3(-50, 50, 50)));
    var settings =
        List.of(
            new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AasMap.AreaSettings(0, 0, 6, 0, 0, 0, 0),
            new AasMap.AreaSettings(128, 4, 6, 0, 0, 0, 0));
    var boxes =
        List.of(
            new AasMap.BoundingBox(2, 0, new Vec3(-15, -15, -24), new Vec3(15, 15, 32)),
            new AasMap.BoundingBox(4, 1, new Vec3(-15, -15, -24), new Vec3(15, 15, 4)));
    return new AasNavigation(
        new AasMap(
            5, 0, List.of(), boxes, List.of(), planes, List.of(), empty, List.of(), empty, areas,
            settings, List.of(), nodes, List.of(), empty, List.of()));
  }

  private static AasMap copy(
      AasMap map, List<AasMap.Node> nodes, List<AasMap.AreaSettings> settings) {
    return new AasMap(
        map.version(),
        map.bspChecksum(),
        map.lumps(),
        map.boundingBoxes(),
        map.vertices(),
        map.planes(),
        map.edges(),
        map.edgeIndices(),
        map.faces(),
        map.faceIndices(),
        map.areas(),
        settings,
        map.reachabilities(),
        nodes,
        map.portals(),
        map.portalIndices(),
        map.clusters());
  }

  private static final class World implements TraceWorld {
    final List<TraceRequest> traces = new ArrayList<>();
    double fraction = 1;
    boolean startSolid;
    int contents, contentsQueries;

    @Override
    public TraceResult trace(TraceRequest request) {
      traces.add(request);
      Vec3 end = request.start().add(request.end().add(request.start().scale(-1)).scale(fraction));
      return new TraceResult(fraction, end, startSolid, false, Optional.empty());
    }

    @Override
    public int pointContents(Vec3 point, int mask, int ignored) {
      contentsQueries++;
      assertEquals(-1, mask);
      assertEquals(-1, ignored);
      return contents;
    }
  }
}
