package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BotVisiblePositionTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final TravelPolicy POLICY = TravelPolicy.ofFlags(0xffffff);

  @Test
  void missingSameAndUnroutableAreasDoNotIssueQueriesOrTargets() {
    var world = new World();
    var calls = new ArrayList<Integer>();
    var service =
        new BotVisiblePosition(
            map(reach(2, 10, 20)),
            (area, origin, goal, flags, context) -> {
              calls.add(area);
              return 0;
            },
            world);
    for (int[] pair : new int[][] {{0, 2}, {1, 0}, {1, 1}})
      assertTrue(service.predict(ZERO, pair[0], goal(pair[1]), POLICY).isEmpty());
    assertTrue(calls.isEmpty());
    assertTrue(service.predict(ZERO, 1, goal(2), POLICY).isEmpty());
    assertEquals(List.of(1), calls);
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void testsApproachThenEndpointFromGoalWithExactTraceContract() {
    var world = new World(0, 1);
    var service = new BotVisiblePosition(map(reach(2, 10, 20)), (a, o, g, p, c) -> 1, world);
    assertEquals(Optional.of(new Vec3(20, 0, 0)), service.predict(ZERO, 1, goal(3), POLICY));
    assertEquals(2, world.calls.size());
    for (var request : world.calls) {
      assertEquals(goal(3).origin(), request.start());
      assertEquals(ZERO, request.mins());
      assertEquals(ZERO, request.maxs());
      assertEquals(42, request.ignoreEntity());
      assertEquals(0x10001, request.contentsMask());
    }
    assertEquals(new Vec3(10, 0, 0), world.calls.getFirst().end());
    assertEquals(new Vec3(20, 0, 0), world.calls.getLast().end());
  }

  @Test
  void clearFractionWinsEvenWhenSolidFlagsAreSetAndArrivalStillTracesBothPoints() {
    var clear = new World(1);
    var map = map(reach(2, 10, 20));
    assertEquals(
        Optional.of(new Vec3(10, 0, 0)),
        new BotVisiblePosition(map, (a, o, g, p, c) -> 1, clear).predict(ZERO, 1, goal(2), POLICY));
    assertEquals(1, clear.calls.size());
    var blocked = new World();
    assertEquals(
        Optional.of(new Vec3(20, 0, 0)),
        new BotVisiblePosition(map, (a, o, g, p, c) -> 1, blocked)
            .predict(ZERO, 1, goal(2), POLICY));
    assertEquals(2, blocked.calls.size());
  }

  @Test
  void failedContinuationPreservesOutputAndUsesImmediatePreviousAreaWithoutAvoidance() {
    var world = new World();
    var contexts = new ArrayList<AasMovementRoutes.Context>();
    var origins = new ArrayList<Vec3>();
    var policy = new TravelPolicy(71, 6, TravelPolicy.Team.ANY, Set.of(8));
    var service =
        new BotVisiblePosition(
            map(reach(2, 10, 20), reach(3, 30, 40)),
            (area, origin, goal, actual, context) -> {
              assertSame(policy, actual);
              contexts.add(context);
              origins.add(origin);
              return area < 3 ? area : 0;
            },
            world);
    assertTrue(service.predict(ZERO, 1, goal(4), policy).isEmpty());
    assertEquals(List.of(ZERO, new Vec3(20, 0, 0), new Vec3(40, 0, 0)), origins);
    assertEquals(
        List.of(0, 1, 2), contexts.stream().map(AasMovementRoutes.Context::previousArea).toList());
    assertTrue(contexts.stream().allMatch(c -> c.previousGoalArea() == 4 && c.avoided().isEmpty()));
    assertEquals(4, world.calls.size());
  }

  @Test
  void nativeTwentyReachLimitAllowsTheFortiethTraceAndThenReturnsUnchangedOutput() {
    var map = map(reach(2, 10, 20));
    var blocked = new World();
    assertTrue(
        new BotVisiblePosition(map, (a, o, g, p, c) -> 1, blocked)
            .predict(ZERO, 1, goal(3), POLICY)
            .isEmpty());
    assertEquals(40, blocked.calls.size());
    double[] fractions = new double[40];
    fractions[39] = 1;
    var last = new World(fractions);
    assertEquals(
        Optional.of(new Vec3(20, 0, 0)),
        new BotVisiblePosition(map, (a, o, g, p, c) -> 1, last).predict(ZERO, 1, goal(3), POLICY));
    assertEquals(40, last.calls.size());
  }

  @Test
  void invalidInputsAndRouteResultsFailBeforeCollision() {
    var world = new World();
    var service = new BotVisiblePosition(map(reach(2, 10, 20)), (a, o, g, p, c) -> 9, world);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.predict(new Vec3(Double.MAX_VALUE, 0, 0), 1, goal(2), POLICY));
    assertThrows(IllegalStateException.class, () -> service.predict(ZERO, 1, goal(2), POLICY));
    assertTrue(world.calls.isEmpty());
  }

  private static final class World implements TraceWorld {
    final List<TraceRequest> calls = new ArrayList<>();
    final double[] fractions;

    World(double... fractions) {
      this.fractions = fractions.clone();
    }

    @Override
    public TraceResult trace(TraceRequest request) {
      int index = calls.size();
      calls.add(request);
      double fraction = index < fractions.length ? fractions[index] : 0;
      // Solid flags are deliberately independent from the fraction accepted by this operation.
      return new TraceResult(fraction, request.end(), true, true, Optional.empty());
    }

    @Override
    public int pointContents(Vec3 p, int model, int ignored) {
      throw new AssertionError("Unexpected contents query");
    }
  }

  private static Goal goal(int area) {
    return new Goal(new Vec3(123, 456, 789), area, ZERO, ZERO, 42, 0, 0, 0);
  }

  private static AasMap.Reachability reach(int area, float start, float end) {
    return new AasMap.Reachability(area, 0, 0, new Vec3(start, 0, 0), new Vec3(end, 0, 0), 2, 1, 0);
  }

  private static AasMap map(AasMap.Reachability... entries) {
    var reaches = new ArrayList<AasMap.Reachability>();
    reaches.add(reach(0, 0, 0));
    reaches.addAll(List.of(entries));
    var empty = new AasMap.Indices(new int[0]);
    return new AasMap(
        5, 0, List.of(), List.of(), List.of(), List.of(), List.of(), empty, List.of(), empty,
        List.of(), List.of(), reaches, List.of(), List.of(), empty, List.of());
  }
}
