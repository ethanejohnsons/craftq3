package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WeaponMoveToGoalTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final TravelPolicy POLICY = TravelPolicy.ofFlags(TravelFlags.ALL);

  @Test
  void freshRocketAndBfgLaunchPublishEffectsAndSixSecondDeadline() {
    for (int kind : new int[] {12, 13})
      try (var f = new Fixture(kind, true)) {
        f.input(ZERO, ZERO, kind == 12 ? new Vec3(90, 90, 75) : ZERO, 2);
        var out = f.service.execute(f.handle, goal(2), POLICY, 10);
        assertEquals(52, out.writtenBytes());
        assertEquals(kind, out.result().travelType());
        assertEquals(24, out.result().flags());
        assertEquals(kind == 12 ? 5 : 9, out.weapon().orElseThrow());
        assertEquals(new Vec3(90, 0, 0), out.view().orElseThrow());
        assertEquals(new Vec3(1, 0, 0), out.command().orElseThrow().direction());
        assertEquals(400, out.command().orElseThrow().speed());
        assertEquals(17, out.actionFlags());
        assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 1, 16, ZERO), f.snapshot().history());
        assertEquals(new BotMovement.ReachAvoidance(1, 16, 1), f.snapshot().reachAvoidance());
      }
  }

  @Test
  void cachedWeaponReachRetainsAvoidanceThroughInclusiveDeadlineThenReselects() {
    try (var f = new Fixture(12, true)) {
      f.input(ZERO, ZERO, new Vec3(90, 90, 0), 2);
      f.service.execute(f.handle, goal(2), POLICY, 10);
      var before = f.snapshot();
      f.service.execute(f.handle, goal(2), POLICY, 16);
      assertEquals(before.history(), f.snapshot().history());
      assertEquals(before.reachAvoidance(), f.snapshot().reachAvoidance());
      float next = Math.nextUp(16f);
      f.service.execute(f.handle, goal(2), POLICY, next);
      assertEquals(next + 6, f.snapshot().history().reachDeadline());
      assertEquals(new BotMovement.ReachAvoidance(1, next + 6, 1), f.snapshot().reachAvoidance());
    }
  }

  @Test
  void cachedAirUsesStoredJumpAndPreservesAbsentViewWeaponAndUnrelatedHistory() {
    for (int kind : new int[] {12, 13})
      try (var f = new Fixture(kind, true)) {
        var origin = new Vec3(70, 0, 0);
        f.input(origin, new Vec3(200, 200, 300), new Vec3(90, 10, 0), 0);
        var prior = new BotMovement.History(1, 77, 88, 1, 99, 1, -5, new Vec3(7, 8, 9));
        f.states.updateHistory(f.handle, prior);
        var out = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 100);
        assertEquals(52, out.writtenBytes());
        assertEquals(kind, out.result().travelType());
        assertEquals(
            new Vec3(.7196931838989258, .6942922472953796, 0),
            out.command().orElseThrow().direction());
        assertEquals(400, out.command().orElseThrow().speed());
        assertEquals(0, out.result().flags());
        assertEquals(0, out.actionFlags());
        assertTrue(out.view().isEmpty());
        assertTrue(out.weapon().isEmpty());
        assertEquals(
            new BotMovement.History(1, 77, 88, 1, 99, 1, -5, origin), f.snapshot().history());
      }
  }

  @Test
  void airWithoutActiveJumpClearsResultButDoesNotReplaceAccumulatedCommands() {
    try (var f = new Fixture(12, true)) {
      f.input(ZERO, ZERO, ZERO, 0);
      f.states.updateHistory(f.handle, new BotMovement.History(1, 1, 2, 1, 1, 0, -5, ZERO));
      var out = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 100);
      assertEquals(52, out.writtenBytes());
      assertTrue(out.command().isEmpty());
      assertTrue(out.view().isEmpty());
      assertTrue(out.weapon().isEmpty());
      assertEquals(0, out.actionFlags());
      assertEquals(new MovementResult(0, 0, 0, 0, 12, 0, 0, ZERO, ZERO), out.result());
    }
  }

  @Test
  void deniedCachedWeaponTravelClearsJumpAndOnlyWritesTheResultPrefix() {
    try (var f = new Fixture(12, true)) {
      f.input(ZERO, ZERO, new Vec3(90, 90, 0), 2);
      f.states.updateHistory(f.handle, new BotMovement.History(1, 1, 2, 1, 1, 1, 99, ZERO));
      var out = f.service.execute(f.handle, goal(2), TravelPolicy.ofFlags(0), 10);
      assertEquals(24, out.writtenBytes());
      assertTrue(out.command().isEmpty());
      assertTrue(out.view().isEmpty());
      assertTrue(out.weapon().isEmpty());
      assertEquals(0, f.snapshot().history().jumpReachability());
      var bytes = ByteBuffer.allocate(52);
      Arrays.fill(bytes.array(), (byte) 0xff);
      out.writeTo(bytes, 0);
      for (int i = 24; i < 52; i++) assertEquals((byte) 0xff, bytes.get(i));
    }
  }

  @Test
  void unregisteredWeaponTravelStillFailsWithoutChangingMovementState() {
    try (var f = new Fixture(12, false)) {
      f.input(ZERO, ZERO, ZERO, 2);
      var before = f.snapshot();
      assertThrows(
          GroundMoveToGoal.UnsupportedMovement.class,
          () -> f.service.execute(f.handle, goal(2), POLICY, 10));
      assertEquals(before, f.snapshot());
    }
  }

  private static Goal goal(int area) {
    return new Goal(new Vec3(100, 0, 130), area, ZERO, ZERO, 0, 0, 0, 0);
  }

  private static final class Fixture implements AutoCloseable {
    final BotMovement states = new BotMovement(ignored -> {});
    final int handle = states.allocate();
    final GroundMoveToGoal service;

    Fixture(int kind, boolean registered) {
      var map = map(kind);
      var weapon = new WeaponJumpMovement();
      var world =
          new TraceWorld() {
            public TraceResult trace(TraceRequest request) {
              return TraceResult.clear(request);
            }

            public int pointContents(Vec3 point, int mask, int ignore) {
              return 0;
            }
          };
      service =
          new GroundMoveToGoal(
              states,
              new AasNavigation(map),
              p -> 1,
              new AasMovementRoutes(map),
              world,
              (p, presence, entity) -> false,
              e -> false,
              (input, flags, area, reach) -> {
                throw new AssertionError("Unexpected ordinary reach");
              },
              (input, flags, area, point) -> {
                throw new AssertionError("Unexpected same-area move");
              },
              Map.of(),
              Map.of(),
              null,
              registered ? Map.of(kind, weapon::execute) : Map.of(),
              registered ? Map.of(kind, weapon::finish) : Map.of());
    }

    void input(Vec3 origin, Vec3 velocity, Vec3 view, int flags) {
      states.initialize(
          handle, new MovementInit(origin, velocity, ZERO, 0, 0, .1f, 2, view, flags));
      states.updateMovementFlags(handle, flags);
    }

    BotMovement.Snapshot snapshot() {
      return states.snapshot(handle).orElseThrow();
    }

    public void close() {
      states.close();
    }
  }

  private static AasMap map(int kind) {
    var areas =
        List.of(
            new AasMap.Area(0, 0, 0, ZERO, ZERO, ZERO),
            new AasMap.Area(1, 0, 0, ZERO, ZERO, ZERO),
            new AasMap.Area(2, 0, 0, ZERO, ZERO, ZERO));
    var settings =
        List.of(
            new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AasMap.AreaSettings(0, 1, 6, 1, 0, 1, 1),
            new AasMap.AreaSettings(0, 1, 6, 1, 1, 1, 2));
    var reaches =
        List.of(
            new AasMap.Reachability(0, 0, 0, ZERO, ZERO, 1, 0, 0),
            new AasMap.Reachability(2, 0, 0, new Vec3(0, 1, 0), new Vec3(100, 0, 130), kind, 1, 0),
            new AasMap.Reachability(2, 0, 0, ZERO, ZERO, 2, 1, 0));
    var empty = new AasMap.Indices(new int[0]);
    return new AasMap(
        5,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        empty,
        List.of(),
        empty,
        areas,
        settings,
        reaches,
        List.of(),
        List.of(new AasMap.Portal(0, 0, 0, 0, 0)),
        empty,
        List.of(new AasMap.Cluster(0, 0, 0, 0), new AasMap.Cluster(2, 2, 0, 0)));
  }
}
