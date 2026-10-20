package dev.bluevista.craftq3.engine.botlib.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasGoalLocator;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.testing.TraceRequest;
import dev.bluevista.craftq3.engine.botlib.testing.TraceResult;
import dev.bluevista.craftq3.engine.botlib.testing.TraceWorld;
import dev.bluevista.craftq3.engine.botlib.testing.Vectors;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.AasMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ItemPlacementTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0),
      MIN = new Vec3f(-15, -15, -15),
      MAX = new Vec3f(15, 15, 15);
  private static final ItemInfo ITEM =
      new ItemInfo("item_test", "Test", "", 1, 0, 0, 30, MIN, MAX, 0);

  @Test
  void ordinaryDropUsesOneSuppliedBoxTraceAndAcceptsUnobstructedEnd() {
    var world = new World();
    var placement = new ItemPlacement(navigation(), world, ignored -> {});
    var origin = new Vec3f(10, 20, 200);
    var result = placement.resolve(ITEM, origin, false).orElseThrow();
    assertEquals(new Vec3f(10, 20, 100), result.origin());
    assertEquals(new Vec3f(10, 20, 50), result.goalOrigin());
    assertEquals(1, result.area());
    assertEquals(
        List.of(new TraceRequest(origin, new Vec3f(10, 20, 100), MIN, MAX, 1, 0)), world.traces);
    assertEquals(0, world.contentsQueries);
  }

  @Test
  void failedDropKeepsOriginalPositionWhileLivePlacementDoesNotDropAgain() {
    var world = new World();
    world.startSolid = true;
    world.fraction = .25;
    var placement = new ItemPlacement(navigation(), world, ignored -> {});
    var origin = new Vec3f(10, 20, 200);
    var staticItem = placement.resolve(ITEM, origin, false).orElseThrow();
    assertEquals(origin, staticItem.origin());
    assertEquals(new Vec3f(10, 20, 150), staticItem.goalOrigin());
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
    var observed = new ArrayList<Vec3f>();
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
    var origin = new Vec3f(10, 20, 100);
    var result = placement.resolve(ITEM, origin, true).orElseThrow();
    assertEquals(new ItemRegistry.Placement(origin, origin, 2), result);
    assertEquals(List.of(origin), observed);
    assertEquals(
        List.of(new TraceRequest(origin, new Vec3f(10, 20, 68), MIN, MAX, 65537, -1)),
        world.traces);
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
    var origin = new Vec3f(10, 20, 100);
    var result = placement.resolve(ITEM, origin, true).orElseThrow();
    assertEquals(origin, result.origin());
    assertEquals(new Vec3f(10, 20, 50), result.goalOrigin());
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
      var result = placement.resolve(ITEM, new Vec3f(10, 20, 100), true).orElseThrow();
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
    assertTrue(unsupported.resolve(ITEM, new Vec3f(10, 20, 100), true).isEmpty());
    assertEquals(1, diagnostics.size());
    assertTrue(diagnostics.getFirst().contains("unavailable jump-pad trajectory"));
    var unreachable =
        new ItemPlacement(
            navigation(), world, (origin, mins, maxs) -> OptionalInt.of(0), diagnostics::add);
    assertTrue(unreachable.resolve(ITEM, new Vec3f(10, 20, 100), true).isEmpty());
    assertEquals(1, diagnostics.size());
    var invalid =
        new ItemPlacement(
            navigation(), world, (origin, mins, maxs) -> OptionalInt.of(3), diagnostics::add);
    assertThrows(
        IllegalArgumentException.class, () -> invalid.resolve(ITEM, new Vec3f(10, 20, 100), true));
  }

  @Test
  void solidPointRecoveryUsesObservedGridAndCrouchTraceToFloor() {
    var locator = new AasGoalLocator(navigation());
    var found = locator.bestReachableArea(new Vec3f(10, 20, -8), MIN, MAX);
    assertEquals(1, found.area());
    assertEquals(10, found.origin().x());
    assertEquals(20, found.origin().y());
    assertEquals(.25, found.origin().z(), .00001);
    var clear = locator.bestReachableArea(new Vec3f(10, 20, 60), MIN, MAX);
    assertEquals(new Vec3f(10, 20, 10), clear.origin());
  }

  @Test
  void orderedLinkFallbackPrefersFirstGroundedOrLiquidWithoutRequiringReachabilities() {
    var locator = new AasGoalLocator(navigation());
    var origin = new Vec3f(0, 0, -50);
    var maxs = new Vec3f(15, 15, 100);
    assertEquals(List.of(1, 2), locator.linkedAreas(origin.add(MIN), origin.add(maxs), 4));
    var found = locator.bestReachableArea(origin, MIN, maxs);
    assertEquals(2, found.area());
    assertEquals(origin, found.origin());
    assertEquals(0, navigation().map().areaSettings().get(2).reachabilityCount());
    var absent = locator.bestReachableArea(new Vec3f(0, 0, -200), MIN, MAX);
    assertEquals(0, absent.area());
    assertEquals(new Vec3f(0, 0, -200), absent.origin());
    assertThrows(
        UnsupportedOperationException.class, () -> locator.linkedAreas(MIN, MAX, 4).clear());
  }

  @Test
  void startSolidPresenceTraceKeepsRaisedCandidateAndItsOriginalArea() {
    var source = navigation().map();
    var settings = new ArrayList<>(source.areaSettings());
    var old = settings.get(1);
    settings.set(1, new AasWorld.AreaSettings(old.contents(), old.flags(), 2, 0, 0, 0, 0));
    var locator = new AasGoalLocator(new AasNavigation(copy(source, source.nodes(), settings)));
    assertEquals(
        new AasGoalLocator.GoalArea(1, new Vec3f(10, 20, 100.25f)),
        locator.bestReachableArea(new Vec3f(10, 20, 100), MIN, MAX));
  }

  @Test
  void runtimeCrouchBoundsAndGeometryBudgetsAreIndependentOfStoredCompileBounds() {
    var source = navigation().map();
    var nodes = List.of(new AasWorld.Node(0, 0, 0), new AasWorld.Node(0, -1, -2));
    var locator = new AasGoalLocator(new AasNavigation(copy(source, nodes, source.areaSettings())));
    assertEquals(List.of(1, 2), locator.linkedAreas(new Vec3f(0, 0, 6), new Vec3f(0, 0, 6), 4));
    assertEquals(4, source.boundingBoxes().get(1).maxs().z());
    var bounded = new AasGoalLocator(navigation(), 1);
    assertThrows(IllegalStateException.class, () -> bounded.linkedAreas(MIN, MAX, 4));
    assertThrows(IllegalArgumentException.class, () -> locator.linkedAreas(MAX, MIN, 4));
    assertThrows(IllegalArgumentException.class, () -> locator.linkedAreas(MIN, MAX, 8));
    assertThrows(
        IllegalArgumentException.class,
        () -> locator.bestReachableArea(new Vec3f(1e10f, 0, 0), MIN, MAX));
  }

  private static AasNavigation navigation() {
    var empty = new AasWorld.Indices(new int[0]);
    var planes =
        List.of(
            new AasWorld.Plane(new Vec3f(0, 0, 1), 0, 2),
            new AasWorld.Plane(new Vec3f(0, 0, -1), 0, 2),
            new AasWorld.Plane(new Vec3f(1, 0, 0), 0, 0),
            new AasWorld.Plane(new Vec3f(-1, 0, 0), 0, 0));
    var nodes =
        List.of(
            new AasWorld.Node(0, 0, 0), new AasWorld.Node(0, 2, 0), new AasWorld.Node(2, -1, -2));
    var areas =
        List.of(
            new AasWorld.Area(0, 0, 0, ZERO, ZERO, ZERO),
            new AasWorld.Area(1, 0, 0, ZERO, new Vec3f(100, 100, 100), new Vec3f(50, 50, 50)),
            new AasWorld.Area(
                2, 0, 0, new Vec3f(-100, 0, 0), new Vec3f(0, 100, 100), new Vec3f(-50, 50, 50)));
    var settings =
        List.of(
            new AasWorld.AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AasWorld.AreaSettings(0, 0, 6, 0, 0, 0, 0),
            new AasWorld.AreaSettings(128, 4, 6, 0, 0, 0, 0));
    var boxes =
        List.of(
            new AasMap.BoundingBox(2, 0, new Vec3f(-15, -15, -24), new Vec3f(15, 15, 32)),
            new AasMap.BoundingBox(4, 1, new Vec3f(-15, -15, -24), new Vec3f(15, 15, 4)));
    return new AasNavigation(
        new AasWorld(
            5, 0, List.of(), boxes, List.of(), planes, List.of(), empty, List.of(), empty, areas,
            settings, List.of(), nodes, List.of(), empty, List.of()));
  }

  private static AasWorld copy(
      AasWorld map, List<AasWorld.Node> nodes, List<AasWorld.AreaSettings> settings) {
    return new AasWorld(
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
      Vec3f end = Vectors.lerp(request.start(), request.end(), fraction);
      return new TraceResult(fraction, end, startSolid, false, Optional.empty());
    }

    @Override
    public int pointContents(Vec3f point, int mask, int ignored) {
      contentsQueries++;
      assertEquals(-1, mask);
      assertEquals(-1, ignored);
      return contents;
    }
  }
}
