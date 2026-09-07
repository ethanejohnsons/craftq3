package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AasReachabilityAreaTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void importsTheCrouchGroundTraceAndPreservesItsClientAndMask() {
    var world = new World();
    var service = service(split(-100, Set.of()), world);
    assertEquals(1, service.reachableArea(ZERO, 7));
    assertEquals(
        List.of(
            new TraceRequest(
                ZERO, new Vec3(0, 0, -3), new Vec3(-15, -15, -24), new Vec3(15, 15, 8), 65537, 7)),
        world.requests);
    assertEquals(0, world.contentsCalls);
  }

  @Test
  void onlyNonworldGroundHitsEnableTheStaticEightHundredUnitFallback() {
    var world = new World();
    var service = service(split(-100, Set.of(2)), world);
    assertEquals(1, service.reachableArea(ZERO, 0));
    world.fraction = .5;
    world.entity = 1022;
    assertEquals(1, service.reachableArea(ZERO, 0));
    world.entity = 15;
    assertEquals(2, service.reachableArea(ZERO, 0));
    world.startSolid = true;
    assertEquals(1, service.reachableArea(ZERO, 0));
  }

  @Test
  void anAlreadyReachableOriginWinsBeforeTheUnmatchedEntityFallback() {
    var world = new World();
    world.fraction = .5;
    world.entity = 15;
    assertEquals(1, service(split(-100, Set.of(1, 2)), world).reachableArea(ZERO, 0));
  }

  @Test
  void groundedRecoveryIncludesTheLowerFuzzyLayerAtTheTraceEnd() {
    var world = new World();
    world.fraction = .5;
    world.entity = 15;
    assertEquals(2, service(split(-812, Set.of(2)), world).reachableArea(ZERO, 0));
    assertEquals(1, service(split(-812.01f, Set.of(2)), world).reachableArea(ZERO, 0));
  }

  @Test
  void moverClassAndFirstPackedModelReachOverrideTheCurrentArea() {
    var original = split(-100, Set.of(1, 2));
    var reaches = new ArrayList<>(original.reachabilities());
    reaches.add(new AasMap.Reachability(1, 0x20002, 0, ZERO, ZERO, 19 | 0x01000000, 1, 0));
    reaches.add(new AasMap.Reachability(2, 2, 0, ZERO, ZERO, 11, 1, 0));
    var map = copy(original, reaches);
    var world = new World();
    world.fraction = .5;
    world.entity = 15;
    for (String classname : List.of("func_plat", "FUNC_BOBBING")) {
      var service =
          new AasReachabilityArea(
              new AasNavigation(map),
              List.of(Map.of("classname", classname, "model", "*2")),
              world,
              entity -> Optional.of(new AasReachabilityArea.Entity(2)));
      assertEquals(1, service.reachableArea(new Vec3(0, 0, -200), 0));
    }
    var ordinary =
        new AasReachabilityArea(
            new AasNavigation(map),
            List.of(Map.of("classname", "func_door", "model", "*2")),
            world,
            entity -> Optional.of(new AasReachabilityArea.Entity(2)));
    assertEquals(2, ordinary.reachableArea(new Vec3(0, 0, -200), 0));
  }

  @Test
  void elevatorMetadataUsesTheEntireFaceFieldAndBobbingUsesItsLowSixteenBits() {
    var original = split(-100, Set.of(1, 2));
    var reaches = new ArrayList<>(original.reachabilities());
    reaches.add(new AasMap.Reachability(1, 0x20002, 0, ZERO, ZERO, 11, 1, 0));
    var world = new World();
    world.fraction = .5;
    world.entity = 15;
    var entities = List.of(Map.of("classname", "func_plat", "model", "*2"));
    var service =
        new AasReachabilityArea(
            new AasNavigation(copy(original, reaches)),
            entities,
            world,
            id -> Optional.of(new AasReachabilityArea.Entity(2)));
    assertEquals(2, service.reachableArea(new Vec3(0, 0, -200), 0));
    reaches.add(new AasMap.Reachability(1, 2, 0, ZERO, ZERO, 11, 1, 0));
    service =
        new AasReachabilityArea(
            new AasNavigation(copy(original, reaches)),
            entities,
            world,
            id -> Optional.of(new AasReachabilityArea.Entity(2)));
    assertEquals(1, service.reachableArea(new Vec3(0, 0, -200), 0));
  }

  @Test
  void preliminaryVerticalTraceUsesFirstReachableEntry() {
    var map =
        boxes(
            List.of(new Box(new Vec3(0, 0, 1), .1), new Box(new Vec3(0, 0, 3), .1)), Set.of(1, 2));
    assertEquals(1, service(map, new World()).fuzzyArea(ZERO));
  }

  @Test
  void positiveLayerWinsBeforeCloserHorizontalCandidatesAndTiesKeepRayOrder() {
    var upper = new Box(new Vec3(8, 0, 12), .1);
    var horizontal = new Box(new Vec3(1, 0, 0), .1);
    assertEquals(
        1, service(boxes(List.of(upper, horizontal), Set.of(1, 2)), new World()).fuzzyArea(ZERO));
    var negative = new Box(new Vec3(-8, 0, 12), .1);
    assertEquals(
        2, service(boxes(List.of(negative, upper), Set.of(1, 2)), new World()).fuzzyArea(ZERO));
  }

  @Test
  void nonreachableFallbackUsesTheFirstLayerEntryAndIgnoresThePreliminaryTrace() {
    var preliminary = new Box(new Vec3(0, 0, 2), .1);
    var firstLayer = new Box(new Vec3(8, 0, 12), .1);
    var fixture = boxes(List.of(preliminary, firstLayer), Set.of());
    assertEquals(0, new AasNavigation(fixture).pointArea(ZERO));
    assertEquals(
        List.of(2),
        AasAreaTrace.trace(fixture, ZERO, new Vec3(8, 0, 12), 10, 1000).stream()
            .map(AasAreaTrace.Entry::area)
            .toList());
    assertEquals(2, service(fixture, new World()).fuzzyArea(ZERO));
    assertEquals(
        0,
        service(boxes(List.of(new Box(new Vec3(100, 100, 100), .1)), Set.of(1)), new World())
            .fuzzyArea(ZERO));
  }

  @Test
  void validatesExternalInputsAndFailsExplicitlyOnSpatialWorkExhaustion() {
    var map = boxes(List.of(new Box(new Vec3(8, 0, 12), .1)), Set.of(1));
    var service =
        new AasReachabilityArea(
            new AasNavigation(map), List.of(), new World(), id -> Optional.empty(), 1);
    assertThrows(IllegalStateException.class, () -> service.fuzzyArea(ZERO));
    assertThrows(IllegalArgumentException.class, () -> service.reachableArea(ZERO, 1024));
    assertThrows(IllegalArgumentException.class, () -> service.fuzzyArea(new Vec3(1e10, 0, 0)));
    assertThrows(IllegalArgumentException.class, () -> new AasReachabilityArea.Entity(256));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AasReachabilityArea(
                new AasNavigation(map),
                List.of(Map.of("model", "*bad")),
                new World(),
                id -> Optional.empty()));
  }

  private static AasReachabilityArea service(AasMap map, World world) {
    return new AasReachabilityArea(
        new AasNavigation(map), List.of(), world, id -> Optional.empty());
  }

  private static final class World implements TraceWorld {
    final List<TraceRequest> requests = new ArrayList<>();
    double fraction = 1;
    int entity = 1023, contentsCalls;
    boolean startSolid;

    @Override
    public TraceResult trace(TraceRequest request) {
      requests.add(request);
      var position =
          request.start().add(request.end().add(request.start().scale(-1)).scale(fraction));
      return new TraceResult(
          fraction,
          position,
          startSolid,
          false,
          Optional.of(
              new TraceResult.Hit(
                  new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                  1,
                  0,
                  entity,
                  0,
                  -1,
                  -1,
                  -1,
                  "fixture")));
    }

    @Override
    public int pointContents(Vec3 point, int mask, int ignoreEntity) {
      contentsCalls++;
      return 0;
    }
  }

  private static AasMap split(float boundary, Set<Integer> reachable) {
    return map(
        List.of(
            new AasMap.Plane(new Vec3(0, 0, 1), boundary, 2),
            new AasMap.Plane(new Vec3(0, 0, -1), -boundary, 2)),
        List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, -1, -2)),
        2,
        reachable);
  }

  private record Box(Vec3 center, double radius) {}

  private static AasMap boxes(List<Box> boxes, Set<Integer> reachable) {
    var planes = new ArrayList<AasMap.Plane>();
    var nodes = new ArrayList<AasMap.Node>();
    nodes.add(new AasMap.Node(0, 0, 0));
    buildBox(boxes, 0, planes, nodes);
    return map(planes, nodes, boxes.size(), reachable);
  }

  private static int buildBox(
      List<Box> boxes, int index, List<AasMap.Plane> planes, List<AasMap.Node> nodes) {
    if (index == boxes.size()) return 0;
    int base = nodes.size();
    for (int n = 0; n < 6; n++) nodes.add(null);
    int outside = buildBox(boxes, index + 1, planes, nodes);
    var box = boxes.get(index);
    for (int axis = 0; axis < 3; axis++) {
      Vec3 normal =
          axis == 0 ? new Vec3(1, 0, 0) : axis == 1 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
      double center =
          axis == 0 ? box.center().x() : axis == 1 ? box.center().y() : box.center().z();
      int low = planes.size();
      planes.add(new AasMap.Plane(normal, (float) (center - box.radius()), axis));
      planes.add(new AasMap.Plane(normal.scale(-1), (float) (-center + box.radius()), axis));
      int high = planes.size();
      planes.add(new AasMap.Plane(normal, (float) (center + box.radius()), axis));
      planes.add(new AasMap.Plane(normal.scale(-1), (float) (-center - box.radius()), axis));
      int n = base + 2 * axis;
      nodes.set(n, new AasMap.Node(low, n + 1, outside));
      nodes.set(n + 1, new AasMap.Node(high, outside, axis == 2 ? -(index + 1) : n + 2));
    }
    return base;
  }

  private static AasMap map(
      List<AasMap.Plane> planes, List<AasMap.Node> nodes, int count, Set<Integer> reachable) {
    var areas = new ArrayList<AasMap.Area>();
    var settings = new ArrayList<AasMap.AreaSettings>();
    var reaches = new ArrayList<AasMap.Reachability>();
    reaches.add(new AasMap.Reachability(0, 0, 0, ZERO, ZERO, 0, 0, 0));
    for (int area = 0; area <= count; area++) {
      areas.add(
          new AasMap.Area(
              area,
              0,
              0,
              new Vec3(-100000, -100000, -100000),
              new Vec3(100000, 100000, 100000),
              ZERO));
      settings.add(
          new AasMap.AreaSettings(
              0,
              0,
              6,
              0,
              0,
              reachable.contains(area) ? 1 : 0,
              reachable.contains(area) ? reaches.size() : 0));
      if (reachable.contains(area))
        reaches.add(new AasMap.Reachability(area, 0, 0, ZERO, ZERO, 2, 1, 0));
    }
    return new AasMap(
        5,
        0,
        List.of(),
        List.of(),
        List.of(),
        planes,
        List.of(),
        new AasMap.Indices(new int[0]),
        List.of(),
        new AasMap.Indices(new int[0]),
        areas,
        settings,
        reaches,
        nodes,
        List.of(),
        new AasMap.Indices(new int[0]),
        List.of());
  }

  private static AasMap copy(AasMap m, List<AasMap.Reachability> reaches) {
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
        m.areaSettings(),
        reaches,
        m.nodes(),
        m.portals(),
        m.portalIndices(),
        m.clusters());
  }
}
