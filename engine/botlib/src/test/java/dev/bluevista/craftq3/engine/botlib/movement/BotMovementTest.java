package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class BotMovementTest {
  @Test
  void movementStatesMatchNativeCapacityAndFreeSlotReuse() {
    var diagnostics = new ArrayList<String>();
    try (var movement = new BotMovement(diagnostics::add)) {
      for (int handle = 1; handle <= 64; handle++) assertEquals(handle, movement.allocate());
      assertEquals(0, movement.allocate());
      movement.initialize(17, input(4));
      movement.free(17);
      assertEquals(17, movement.allocate());
      assertTrue(movement.snapshot(17).orElseThrow().input().isEmpty());
      movement.free(1000);
      assertTrue(movement.snapshot(-1).isEmpty());
      assertEquals(64, movement.allocatedCount());
      assertFalse(diagnostics.isEmpty());
    }
  }

  @Test
  void initializationPreservesAvoidSpotsAndResetClearsOwnedState() {
    try (var movement = new BotMovement(ignored -> {})) {
      int handle = movement.allocate();
      var spot = new BotMovement.AvoidSpot(new Vec3f(1, 2, 3), 40, BotMovement.AVOID_ALWAYS);
      movement.addAvoidSpot(handle, spot);
      movement.initialize(handle, input(1));
      var before = movement.snapshot(handle).orElseThrow();
      assertEquals(input(1), before.input().orElseThrow());
      assertEquals(spot, before.avoidSpots().getFirst());
      movement.initialize(handle, input(2));
      assertEquals(1, movement.snapshot(handle).orElseThrow().avoidSpots().size());
      movement.reset(handle);
      var after = movement.snapshot(handle).orElseThrow();
      assertTrue(after.input().isEmpty());
      assertTrue(after.avoidSpots().isEmpty());
      assertEquals(1, before.avoidSpots().size());
      assertThrows(UnsupportedOperationException.class, () -> before.avoidSpots().clear());
    }
  }

  @Test
  void reachAttemptsUseDifferentStrictExpiryRulesForRefreshAndReplacement() {
    try (var movement = new BotMovement(ignored -> {})) {
      int handle = movement.allocate();
      movement.recordReachAttempt(handle, 7, 10, 0);
      assertEquals(
          BotMovement.ReachAvoidance.EMPTY,
          movement.snapshot(handle).orElseThrow().reachAvoidance());
      movement.recordReachAttempt(handle, 7, 10, 1);
      movement.recordReachAttempt(handle, 7, 10, 2);
      assertEquals(
          new BotMovement.ReachAvoidance(7, 12, 2),
          movement.snapshot(handle).orElseThrow().reachAvoidance());
      movement.recordReachAttempt(handle, 8, 10, 12);
      assertEquals(7, movement.snapshot(handle).orElseThrow().reachAvoidance().reachability());
      movement.recordReachAttempt(handle, 7, 10, 12);
      assertEquals(
          new BotMovement.ReachAvoidance(7, 22, 1),
          movement.snapshot(handle).orElseThrow().reachAvoidance());
      movement.recordReachAttempt(handle, 8, -1, 23);
      assertEquals(
          new BotMovement.ReachAvoidance(8, 22, 1),
          movement.snapshot(handle).orElseThrow().reachAvoidance());
      assertThrows(
          IllegalArgumentException.class,
          () -> movement.recordReachAttempt(handle, 8, Float.MAX_VALUE, Float.MAX_VALUE));
      assertEquals(
          new BotMovement.ReachAvoidance(8, 22, 1),
          movement.snapshot(handle).orElseThrow().reachAvoidance());
    }
  }

  @Test
  void reachResetsPreserveInputSpotsAndLastResetRefundsAtMostOneAttempt() {
    try (var movement = new BotMovement(ignored -> {})) {
      int handle = movement.allocate();
      movement.initialize(handle, input(1));
      var spot = new BotMovement.AvoidSpot(new Vec3f(1, 2, 3), 40, 1);
      movement.addAvoidSpot(handle, spot);
      movement.recordReachAttempt(handle, 7, 10, 1);
      movement.recordReachAttempt(handle, 7, 10, 2);
      var before = movement.snapshot(handle).orElseThrow();
      movement.resetLastAvoidReach(handle);
      assertEquals(
          new BotMovement.ReachAvoidance(7, 0, 1),
          movement.snapshot(handle).orElseThrow().reachAvoidance());
      movement.resetLastAvoidReach(handle);
      assertEquals(1, movement.snapshot(handle).orElseThrow().reachAvoidance().tries());
      movement.resetAvoidReach(handle);
      var after = movement.snapshot(handle).orElseThrow();
      assertEquals(BotMovement.ReachAvoidance.EMPTY, after.reachAvoidance());
      assertEquals(before.input(), after.input());
      assertEquals(before.avoidSpots(), after.avoidSpots());
      assertEquals(2, before.reachAvoidance().tries());
      movement.recordReachAttempt(handle, 7, 10, 3);
      movement.resetLastAvoidReach(handle);
      assertEquals(
          new BotMovement.ReachAvoidance(7, 0, 0),
          movement.snapshot(handle).orElseThrow().reachAvoidance());
    }
  }

  @Test
  void initializationAcceptsOnlyPublishedPlayerFlagsAndPreservesReachState() {
    try (var movement = new BotMovement(ignored -> {})) {
      int handle = movement.allocate();
      movement.recordReachAttempt(handle, 7, 10, 1);
      for (int flags = 0; flags < 2048; flags++) {
        var data = input(1);
        movement.initialize(
            handle,
            new MovementInit(
                data.origin(),
                data.velocity(),
                data.viewOffset(),
                data.entity(),
                data.client(),
                data.thinkTime(),
                data.presenceType(),
                data.viewAngles(),
                flags));
        assertEquals(flags & 626, movement.snapshot(handle).orElseThrow().movementFlags());
        assertEquals(
            new BotMovement.ReachAvoidance(7, 11, 1),
            movement.snapshot(handle).orElseThrow().reachAvoidance());
      }
      movement.reset(handle);
      assertEquals(0, movement.snapshot(handle).orElseThrow().movementFlags());
      assertEquals(
          BotMovement.ReachAvoidance.EMPTY,
          movement.snapshot(handle).orElseThrow().reachAvoidance());
    }
  }

  @Test
  void avoidSpotStorageCapsAt32AndClearDoesNotDiscardInitialization() {
    try (var movement = new BotMovement(ignored -> {})) {
      int handle = movement.allocate();
      movement.initialize(handle, input(1));
      for (int i = 0; i < 40; i++)
        movement.addAvoidSpot(handle, new BotMovement.AvoidSpot(new Vec3f(i, 0, 0), i, 1));
      var spots = movement.snapshot(handle).orElseThrow().avoidSpots();
      assertEquals(32, spots.size());
      assertEquals(31, spots.getLast().origin().x());
      movement.clearAvoidSpots(handle);
      assertTrue(movement.snapshot(handle).orElseThrow().avoidSpots().isEmpty());
      assertTrue(movement.snapshot(handle).orElseThrow().input().isPresent());
      assertThrows(
          IllegalArgumentException.class,
          () -> new BotMovement.AvoidSpot(new Vec3f(0, 0, 0), Float.NaN, 1));
    }
    var movement = new BotMovement(ignored -> {});
    movement.close();
    assertThrows(IllegalStateException.class, movement::allocate);
  }

  @Test
  void navigationHistorySurvivesInitializationAndAvoidResetsButNotFullReset() {
    try (var movement = new BotMovement(ignored -> {})) {
      int handle = movement.allocate();
      var history = new BotMovement.History(3, 4, 5, 6, 7, 8, 9.5f, new Vec3f(10, 11, 12));
      movement.updateHistory(handle, history);
      movement.updateMovementFlags(handle, 8 | 128 | 512);
      movement.initialize(handle, input(1));
      movement.recordReachAttempt(handle, 6, 5, 1);
      movement.resetLastAvoidReach(handle);
      movement.resetAvoidReach(handle);
      var before = movement.snapshot(handle).orElseThrow();
      assertEquals(history, before.history());
      assertEquals(8 | 128 | (input(1).moveFlags() & 626), before.movementFlags());
      movement.reset(handle);
      assertEquals(BotMovement.History.EMPTY, movement.snapshot(handle).orElseThrow().history());
      assertEquals(history, before.history());
      assertThrows(
          IllegalArgumentException.class,
          () -> new BotMovement.History(65536, 0, 0, 0, 0, 0, 0, new Vec3f(0, 0, 0)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new BotMovement.History(0, 0, 0, 0, 0, 0, Float.NaN, new Vec3f(0, 0, 0)));
    }
  }

  private static MovementInit input(int client) {
    return new MovementInit(
        new Vec3f(10, 20, 30),
        new Vec3f(40, 50, 60),
        new Vec3f(70, 80, 90),
        123,
        client,
        .05f,
        2,
        new Vec3f(10, 20, 30),
        0x1234);
  }
}
