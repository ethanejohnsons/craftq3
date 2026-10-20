package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BotMovementViewTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final AasMovementRoutes.Context EMPTY = AasMovementRoutes.Context.EMPTY;

  @Test
  void missingReachAndNonpositiveLookaheadLeaveOutputUntouched() {
    var view = view(reach(2, 10, 20, 2));
    var untouched = new BotMovementView.Result(false, Optional.empty());
    assertEquals(untouched, view.target(ZERO, 0, goal(2, 30), 0, 50, EMPTY));
    assertEquals(untouched, view.target(ZERO, 1, goal(2, 30), 0, 0, EMPTY));
    assertEquals(untouched, view.target(ZERO, 1, goal(2, 30), 0, -1, EMPTY));
  }

  @Test
  void followsApproachTraversalAndFinalGoalWithoutTestingFirstLinkPermissions() {
    var view = view(reach(2, 10, 20, 2));
    for (float ahead : new float[] {5, 10, 15, 20, 25, 30, 100})
      assertEquals(ok(Math.min(ahead, 30)), view.target(ZERO, 1, goal(2, 30), 0, ahead, EMPTY));
  }

  @Test
  void teleportAndWeaponJumpsStopAtStartIncludingTeamTaggedTravel() {
    for (int type : new int[] {10, 12, 13, 10 | 0x1000000, 12 | 0x2000000}) {
      var view = view(reach(2, 10, 20, type));
      assertEquals(ok(5), view.target(ZERO, 1, goal(2, 30), 0, 5, EMPTY));
      assertEquals(ok(10), view.target(ZERO, 1, goal(2, 30), 0, 100, EMPTY));
    }
  }

  @Test
  void elevatorsJumpPadsAndBobbingPlatformsSkipTheirTraversalDistance() {
    for (int type : new int[] {11, 18, 19, 19 | 0x1000000}) {
      var view = view(reach(2, 10, 20, type));
      assertEquals(ok(10), view.target(ZERO, 1, goal(2, 30), 0, 10, EMPTY));
      assertEquals(ok(25), view.target(ZERO, 1, goal(2, 30), 0, 15, EMPTY));
      assertEquals(ok(30), view.target(ZERO, 1, goal(2, 30), 0, 100, EMPTY));
    }
  }

  @Test
  void failedContinuationPreservesTheLastActualWrite() {
    assertEquals(
        new BotMovementView.Result(false, Optional.of(new Vec3(20, 0, 0))),
        view(reach(2, 10, 20, 2)).target(ZERO, 1, goal(3, 30), 0, 100, EMPTY));
    // Skipping a platform traversal changes the route origin without writing that endpoint.
    assertEquals(
        new BotMovementView.Result(false, Optional.of(new Vec3(10, 0, 0))),
        view(reach(2, 10, 20, 18)).target(ZERO, 1, goal(3, 30), 0, 100, EMPTY));
  }

  @Test
  void continuationAdvancesPreviousAreaButRetainsOriginalGoalAndAvoidance() {
    var contexts = new ArrayList<AasMovementRoutes.Context>();
    var origins = new ArrayList<Vec3>();
    var view =
        new BotMovementView(
            map(reach(2, 10, 20, 2), reach(3, 30, 40, 2)),
            (area, origin, goal, flags, context) -> {
              assertEquals(71, flags.travelFlags());
              contexts.add(context);
              origins.add(origin);
              return area == 2 ? 2 : 0;
            },
            10);
    var context =
        new AasMovementRoutes.Context(
            7, 8, 10, List.of(new AasMovementRoutes.AvoidReach(9, 100, 5)));
    var result = view.target(ZERO, 1, goal(4, 50), 71, 100, context);
    assertFalse(result.success());
    assertEquals(List.of(new Vec3(20, 0, 0), new Vec3(40, 0, 0)), origins);
    assertEquals(
        List.of(context, new AasMovementRoutes.Context(7, 2, 10, context.avoided())), contexts);
    assertEquals(8, context.previousArea());
  }

  @Test
  void continuationReceivesDisabledAreaPolicyWhileTheExistingReachStillFinishes() {
    var policy = new TravelPolicy(71, 6, TravelPolicy.Team.ANY, Set.of(3));
    var view =
        new BotMovementView(
            map(reach(2, 10, 20, 2), reach(3, 30, 40, 2)),
            (area, origin, goal, actual, context) -> {
              assertSame(policy, actual);
              assertEquals(Set.of(3), actual.disabledAreas());
              return 0;
            },
            10);
    assertEquals(
        new BotMovementView.Result(false, Optional.of(new Vec3(20, 0, 0))),
        view.target(ZERO, 1, goal(3, 50), policy, 100, EMPTY));
  }

  @Test
  void normalizedLengthRetainsNativeInterpolationAtTheRoundedRootBoundary() {
    var endpoint = new Vec3(-3940.458984375, -497.609130859375, -1388.7962646484375);
    var reach = new AasMap.Reachability(2, 0, 0, endpoint, endpoint, 2, 1, 0);
    var result = view(reach).target(ZERO, 1, goal(2, 0), 0, 4207.56298828125f, EMPTY);
    assertEquals(
        new BotMovementView.Result(
            true, Optional.of(new Vec3(-3940.45898f, -497.6091f, -1388.79626f))),
        result);
    assertNotEquals(endpoint.y(), result.target().orElseThrow().y());
  }

  @Test
  void nativeFloatDistanceAccumulationAndInterpolationArePreserved() {
    var r =
        new AasMap.Reachability(2, 0, 0, new Vec3(10.25, 4.75, 0), new Vec3(30, -5.5, 2), 2, 1, 0);
    var target =
        view(r)
            .target(
                ZERO, 1, new Goal(new Vec3(60, 10, 1), 2, ZERO, ZERO, 0, 0, 0, 0), 0, 50, EMPTY);
    assertEquals(
        new BotMovementView.Result(
            true, Optional.of(new Vec3(44.52985f, 2.00708961f, 1.51567161f))),
        target);
  }

  @Test
  void invalidInputsAndCyclicWorkFailExplicitly() {
    var map = map(reach(2, 0, 0, 2));
    var cycle = new BotMovementView(map, (area, origin, goal, flags, context) -> 1, 3);
    assertThrows(
        IllegalStateException.class, () -> cycle.target(ZERO, 1, goal(3, 0), 0, 10, EMPTY));
    assertThrows(
        IllegalArgumentException.class, () -> cycle.target(ZERO, 2, goal(2, 0), 0, 10, EMPTY));
    assertThrows(
        IllegalArgumentException.class,
        () -> cycle.target(ZERO, 1, goal(2, 0), 0, Float.NaN, EMPTY));
    assertThrows(
        IllegalArgumentException.class, () -> new BotMovementView.Result(true, Optional.empty()));
    var invalid = new BotMovementView(map, (area, origin, goal, flags, context) -> 999, 3);
    assertThrows(
        IllegalStateException.class, () -> invalid.target(ZERO, 1, goal(3, 0), 0, 10, EMPTY));
  }

  private static BotMovementView view(AasMap.Reachability reach) {
    return new BotMovementView(map(reach), (area, origin, goal, flags, context) -> 0, 10);
  }

  private static BotMovementView.Result ok(float x) {
    return new BotMovementView.Result(true, Optional.of(new Vec3(x, 0, 0)));
  }

  private static Goal goal(int area, float x) {
    return new Goal(new Vec3(x, 0, 0), area, ZERO, ZERO, 0, 0, 0, 0);
  }

  private static AasMap.Reachability reach(int area, float start, float end, int type) {
    return new AasMap.Reachability(
        area, 0, 0, new Vec3(start, 0, 0), new Vec3(end, 0, 0), type, 1, 0);
  }

  private static AasMap map(AasMap.Reachability... entries) {
    var reaches = new ArrayList<AasMap.Reachability>();
    reaches.add(reach(0, 0, 0, 0));
    reaches.addAll(List.of(entries));
    var empty = new AasMap.Indices(new int[0]);
    return new AasMap(
        5, 0, List.of(), List.of(), List.of(), List.of(), List.of(), empty, List.of(), empty,
        List.of(), List.of(), reaches, List.of(), List.of(), empty, List.of());
  }
}
