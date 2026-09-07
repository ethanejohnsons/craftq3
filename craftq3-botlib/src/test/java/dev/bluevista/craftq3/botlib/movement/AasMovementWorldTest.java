package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AasMovementWorldTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final Vec3 START = new Vec3(10, 0, 0), END = new Vec3(-10, 0, 0);

  @Test
  void entityClippingUsesEachLeafSegmentAndRuntimeHullInsteadOfTheWholeRay() {
    var requests = new ArrayList<TraceRequest>();
    var world =
        world(
            (entity, request) -> {
              requests.add(request);
              return requests.size() == 1 ? TraceResult.clear(request) : hit(request, .5f, false);
            });
    world.link(3, new Vec3(-20, -20, -20), new Vec3(20, 20, 20));
    var result = world.trace(START, END, 4, 0);
    assertEquals(2, requests.size());
    assertEquals(START, requests.getFirst().start());
    assertEquals(.125, requests.getFirst().end().x());
    assertEquals(requests.getFirst().end(), requests.getLast().start());
    assertEquals(END, requests.getLast().end());
    assertEquals(new Vec3(-15, -15, -24), requests.getLast().mins());
    assertEquals(new Vec3(15, 15, 8), requests.getLast().maxs());
    assertEquals(65537, requests.getLast().contentsMask());
    assertEquals(0, requests.getLast().ignoreEntity());
    assertEquals(-4.9375, result.endPosition().x());
    assertEquals(.746875f, result.fraction());
    assertEquals(1, result.lastArea());
    assertEquals(0, result.area());
    assertEquals(0, result.planeNumber());
    assertEquals(3, result.entity());
  }

  @Test
  void newestLinksWinEqualHitsAndEveryUpdateRelinksWhilePassAndNullRemoveCandidates() {
    var seen = new ArrayList<Integer>();
    var world =
        world(
            (entity, request) -> {
              seen.add(entity);
              return hit(request, .5f, false);
            });
    for (int entity : new int[] {3, 4}) world.link(entity, ZERO, ZERO);
    assertEquals(4, world.trace(START, END, 2, 0).entity());
    assertEquals(List.of(4, 3), seen);
    world.link(3, ZERO, ZERO);
    assertEquals(3, world.trace(START, END, 2, 0).entity());
    assertEquals(4, world.trace(START, END, 2, 3).entity());
    seen.clear();
    assertEquals(1, world.trace(START, END, 2, -1).fraction());
    assertTrue(seen.isEmpty());
    world.unlink(4);
    assertEquals(1, world.trace(START, END, 2, 3).fraction());
    world.clear();
    assertEquals(0, world.linkCount());
    assertEquals(1, world.trace(START, END, 2, 0).fraction());
  }

  @Test
  void laterStartSolidUsesZeroGlobalFractionAndClosestCandidateIsNotFirstCandidate() {
    int[] count = {0};
    var world =
        world(
            (entity, request) ->
                ++count[0] <= 2
                    ? TraceResult.clear(request)
                    : hit(request, entity == 3 ? 0 : .5f, entity == 3));
    world.link(3, ZERO, ZERO);
    world.link(4, ZERO, ZERO);
    var result = world.trace(START, END, 2, 0);
    assertEquals(4, count[0]);
    assertTrue(result.startSolid());
    assertEquals(0, result.fraction());
    assertEquals(new Vec3(.125, 0, 0), result.endPosition());
    assertEquals(1, result.lastArea());
    assertEquals(3, result.entity());
  }

  @Test
  void dynamicMaskIncludesPlayerClipButExcludesBodyAndLinksArePresenceExpanded() {
    var body = new BoxTraceWorld(new Vec3(20, -1, -1), new Vec3(25, 1, 1), 0x2000000, 0, 3);
    var playerClip = new BoxTraceWorld(new Vec3(20, -1, -1), new Vec3(25, 1, 1), 65536, 0, 4);
    var world = world((entity, request) -> (entity == 3 ? body : playerClip).trace(request));
    world.link(3, new Vec3(20, -1, -1), new Vec3(25, 1, 1));
    assertEquals(1, world.trace(new Vec3(1, 0, 0), new Vec3(10, 0, 0), 2, 0).fraction());
    world.link(4, new Vec3(20, -1, -1), new Vec3(25, 1, 1));
    assertEquals(4, world.trace(new Vec3(1, 0, 0), new Vec3(10, 0, 0), 2, 0).entity());
    assertEquals(7, world.contents(ZERO));
  }

  @Test
  void startSolidRetainsCallbackEndpointAndDoesNotOverrideFractionSelection() {
    var world = world((entity, request) -> hit(request, entity == 3 ? 1 : .5f, true));
    world.link(3, ZERO, ZERO);
    assertEquals(1, world.trace(START, END, 2, 0).fraction());
    world.link(4, ZERO, ZERO);
    var result = world.trace(START, END, 2, 0);
    assertTrue(result.startSolid());
    assertEquals(0, result.fraction());
    assertEquals(new Vec3(5.0625, 0, 0), result.endPosition());
    assertEquals(4, result.entity());
  }

  @Test
  void invalidLinkUpdatesPreservePreviousMembershipAndBoundsAreChecked() {
    var world = world((entity, request) -> hit(request, .5f, false));
    world.link(3, ZERO, ZERO);
    int links = world.linkCount();
    assertThrows(IllegalArgumentException.class, () -> world.link(3, new Vec3(1, 0, 0), ZERO));
    assertEquals(links, world.linkCount());
    assertEquals(3, world.trace(START, END, 2, 0).entity());
    assertThrows(IllegalArgumentException.class, () -> world.link(1024, ZERO, ZERO));
    assertThrows(IllegalArgumentException.class, () -> world.trace(START, END, 2, 1024));
  }

  @Test
  void areaContentsRetainsAasBitsSeparatelyFromEnginePointContents() {
    var world = world((entity, request) -> TraceResult.clear(request), 6);
    assertEquals(6, world.areaContents(world.area(START)));
    assertEquals(7, world.contents(START));
    assertEquals(0, world.areaContents(0));
    assertEquals(0, world.areaContents(2));
    assertThrows(IllegalArgumentException.class, () -> world.areaContents(-1));
    assertThrows(IllegalArgumentException.class, () -> world.areaContents(3));
  }

  private static AasMovementWorld world(AasMovementWorld.EntityTrace trace) {
    return world(trace, 0);
  }

  private static AasMovementWorld world(AasMovementWorld.EntityTrace trace, int areaContents) {
    var indices = new AasMap.Indices(new int[0]);
    var map =
        new AasMap(
            4,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new AasMap.Plane(new Vec3(1, 0, 0), 0, 0),
                new AasMap.Plane(new Vec3(-1, 0, 0), 0, 0)),
            List.of(),
            indices,
            List.of(),
            indices,
            List.of(
                new AasMap.Area(0, 0, 0, ZERO, ZERO, ZERO),
                new AasMap.Area(1, 0, 0, ZERO, ZERO, ZERO),
                new AasMap.Area(2, 0, 0, ZERO, ZERO, ZERO)),
            List.of(
                new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0),
                new AasMap.AreaSettings(areaContents, 0, 6, 0, 0, 0, 0),
                new AasMap.AreaSettings(0, 0, 6, 0, 0, 0, 0)),
            List.of(),
            List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, -1, -2)),
            List.of(),
            indices,
            List.of());
    return new AasMovementWorld(new AasNavigation(map), trace, point -> 7);
  }

  private static TraceResult hit(TraceRequest request, float fraction, boolean solid) {
    return new TraceResult(
        fraction,
        request.start().add(request.end().add(request.start().scale(-1)).scale(fraction)),
        solid,
        false,
        Optional.empty());
  }
}
