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
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GroundMoveToGoalTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final TravelPolicy POLICY = TravelPolicy.ofFlags(TravelFlags.ALL);

  @Test
  void newGroundLinkRecordsAttemptAndRetainsItThroughInclusiveDeadline() {
    var f = new Fixture(2);
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(52, output.writtenBytes());
    assertEquals(
        new GroundMoveToGoal.Command(new Vec3(1, 0, 0), 400, 0), output.command().orElseThrow());
    var before = f.snapshot();
    assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 6, ZERO), before.history());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), before.reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 6);
    assertEquals(before.history(), f.snapshot().history());
    assertEquals(before.reachAvoidance(), f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, Math.nextUp(6f));
    assertEquals(Math.nextUp(6f) + 5, f.snapshot().history().reachDeadline());
    assertEquals(2, f.snapshot().reachAvoidance().tries());
  }

  @Test
  void deniedCachedTravelSelectsAgainAndPreservesUnwrittenArbitrarySuffixBytes() {
    var f = new Fixture(2);
    f.states.updateHistory(
        f.handle, new BotMovement.History(1, 1, 2, 1, 3, 8, 100, new Vec3(4, 5, 6)));
    var output = f.service.execute(f.handle, goal(2), TravelPolicy.ofFlags(0), 1);
    assertEquals(24, output.writtenBytes());
    assertTrue(output.command().isEmpty());
    assertEquals(1, output.result().failure());
    assertEquals(new BotMovement.History(1, 1, 2, 0, 1, 0, 100, ZERO), f.snapshot().history());
    var bytes = ByteBuffer.allocate(54).order(ByteOrder.BIG_ENDIAN);
    Arrays.fill(
        bytes.array(), (byte) 0xff); // Includes NaN float encodings in the untouched suffix.
    bytes.position(53);
    output.writeTo(bytes, 1);
    assertEquals(53, bytes.position());
    assertEquals(ByteOrder.BIG_ENDIAN, bytes.order());
    assertEquals(1, bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(1));
    for (int i = 25; i < 54; i++) assertEquals((byte) 0xff, bytes.get(i));
    assertEquals((byte) 0xff, bytes.get(0));
    assertThrows(IllegalArgumentException.class, () -> output.writeTo(bytes, 3));
  }

  @Test
  void retainedLinkIgnoresTeamTagsDisabledAreasAndItsTimedAvoidance() {
    var f = new Fixture(2 | 0x1000000);
    var history = new BotMovement.History(1, 1, 2, 1, 3, 8, 100, ZERO);
    f.states.updateHistory(f.handle, history);
    for (int i = 0; i < 5; i++) f.states.recordReachAttempt(f.handle, 1, 100, 1);
    var policy = new TravelPolicy(TravelFlags.WALK, 6, TravelPolicy.Team.ONE, Set.of(1, 2, 3));
    var output = f.service.execute(f.handle, goal(2), policy, 2);
    assertEquals(2 | 0x1000000, output.result().travelType());
    assertEquals(history, f.snapshot().history());
    assertEquals(5, f.snapshot().reachAvoidance().tries());
  }

  @Test
  void sameAreaClearsLastAreaAndReachAndRetainsOtherHistory() {
    var f = new Fixture(2);
    f.states.updateHistory(
        f.handle, new BotMovement.History(99, 123, 999, 1, 3, 8, 100, new Vec3(4, 5, 6)));
    f.states.recordReachAttempt(f.handle, 1, 100, 1);
    var output = f.service.execute(f.handle, goal(1), POLICY, 2);
    assertEquals(1, f.goalCalls);
    assertEquals(52, output.writtenBytes());
    assertEquals(new BotMovement.History(1, 0, 1, 0, 3, 8, 100, ZERO), f.snapshot().history());
    assertEquals(new BotMovement.ReachAvoidance(1, 101, 1), f.snapshot().reachAvoidance());
  }

  @Test
  void solidLocalizationChangesOnlyCurrentAreaAndLeavesSuffixUntouched() {
    var f = new Fixture(2);
    f.area = 0;
    f.states.updateHistory(
        f.handle, new BotMovement.History(99, 123, 999, 1, 3, 8, 100, new Vec3(4, 5, 6)));
    var output = f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(24, output.writtenBytes());
    assertEquals(1, output.result().failure());
    assertEquals(8, output.result().type());
    assertEquals(1, output.result().blocked());
    assertEquals(
        new BotMovement.History(0, 123, 999, 1, 3, 8, 100, new Vec3(4, 5, 6)),
        f.snapshot().history());
    assertEquals(1, f.world.traces.size());
  }

  @Test
  void ordinaryEntityGroundBlocksBeforeFlagsContentsAndHistoryUpdates() {
    for (int entity : new int[] {0, 15}) {
      for (double fraction : new double[] {0, .5, 1}) {
        var f = new Fixture(2);
        f.world.entity = entity;
        f.world.fraction = fraction;
        f.states.updateMovementFlags(f.handle, 2 | 4 | 8 | 128);
        var before = f.snapshot();
        var output = f.service.execute(f.handle, goal(2), POLICY, 2);
        assertEquals(24, output.writtenBytes());
        assertEquals(1, output.result().blocked());
        assertEquals(entity, output.result().blockEntity());
        assertEquals(32, output.result().flags());
        assertEquals(before, f.snapshot());
        assertTrue(f.world.contentPoints.isEmpty());
        f.movingPlatform = true;
        assertThrows(
            UnsupportedOperationException.class,
            () -> f.service.execute(f.handle, goal(2), POLICY, 2));
        assertEquals(before, f.snapshot());
      }
    }
  }

  @Test
  void groundTraceUsesPresenceHullAndDerivedFlagsAreSeparateFromPlayerInput() {
    var f = new Fixture(2);
    var input = new MovementInit(new Vec3(4, 5, 6), ZERO, ZERO, 7, 1, .1f, 4, ZERO, 2);
    f.states.initialize(f.handle, input);
    f.states.updateMovementFlags(f.handle, 2 | 4 | 8 | 128 | 256);
    f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(
        new TraceRequest(
            input.origin(),
            new Vec3(4, 5, 3),
            new Vec3(-15, -15, -24),
            new Vec3(15, 15, 8),
            65537,
            7),
        f.world.traces.getFirst());
    assertEquals(List.of(new Vec3(4, 5, 4)), f.world.contentPoints);
    assertEquals(2 | 256, f.snapshot().movementFlags());
    assertEquals(input, f.snapshot().input().orElseThrow());
  }

  @Test
  void unsupportedTravelOrExecutorFailurePublishesNoPartialState() {
    var f = new Fixture(5);
    var before = f.snapshot();
    var unsupported =
        assertThrows(
            GroundMoveToGoal.UnsupportedMovement.class,
            () -> f.service.execute(f.handle, goal(2), POLICY, 2));
    assertEquals("Move-to-goal travel type 5", unsupported.reason());
    assertTrue(unsupported.getMessage().contains("selectedReach=1"));
    assertTrue(unsupported.getMessage().contains("history=" + before.history()));
    assertEquals(before, f.snapshot());
    var ordinary = new Fixture(2);
    ordinary.failTravel = true;
    before = ordinary.snapshot();
    assertThrows(
        IllegalStateException.class,
        () -> ordinary.service.execute(ordinary.handle, goal(2), POLICY, 2));
    assertEquals(before, ordinary.snapshot());
  }

  @Test
  void avoidSpotFlagsSurviveSuccessfulAlternativeAndFailedSelection() {
    var f = new Fixture(2);
    f.states.addAvoidSpot(f.handle, new BotMovement.AvoidSpot(new Vec3(10, 0, 0), 1, 1));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(256, output.result().flags());
    assertEquals(2, f.snapshot().history().lastReachability());
    assertEquals(new BotMovement.ReachAvoidance(2, 7, 1), f.snapshot().reachAvoidance());
  }

  @Test
  void airborneWithoutAReachOnlyRecordsLastOriginAndProducesNoCommand() {
    var f = new Fixture(2);
    f.states.updateMovementFlags(f.handle, 0);
    var before = new BotMovement.History(9, 10, 11, 0, 12, 13, 14, new Vec3(4, 5, 6));
    f.states.updateHistory(f.handle, before);
    var output = f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(24, output.writtenBytes());
    assertTrue(output.command().isEmpty());
    assertEquals(new BotMovement.History(9, 10, 11, 0, 12, 13, 14, ZERO), f.snapshot().history());
    assertTrue(f.world.traces.isEmpty());
  }

  private static Goal goal(int area) {
    return new Goal(new Vec3(30, 0, 0), area, ZERO, ZERO, 0, 0, 0, 0);
  }

  @Test
  void aasGroundContactRunsFirstAndRecoversTheFlagEvenOnEntityEarlyReturn() {
    var f = new Fixture(2);
    f.states.updateMovementFlags(f.handle, 0);
    f.onGround = true;
    f.world.entity = 15;
    var before = f.snapshot().history();
    var output = f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(1, f.groundChecks);
    assertEquals(2, f.snapshot().movementFlags());
    assertEquals(before, f.snapshot().history());
    assertEquals(32, output.result().flags());
    assertEquals(24, output.writtenBytes());
    assertTrue(f.world.contentPoints.isEmpty());
    f.onGround = false;
    f.world.entity = 1023;
    output = f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(2, f.groundChecks);
    assertEquals(2, f.snapshot().movementFlags());
    assertEquals(52, output.writtenBytes());
  }

  @Test
  void airborneWalkIgnoresExpiredMismatchedGoalAndDeniedTravelButRetainsHistory() {
    var f = new Fixture(2 | 0x1000000);
    var history = new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3(7, 8, 9));
    f.states.updateHistory(f.handle, history);
    f.states.updateMovementFlags(f.handle, 512 | 4 | 8 | 128);
    f.states.recordReachAttempt(f.handle, 1, 100, 1);
    var avoidance = f.snapshot().reachAvoidance();
    var output = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(52, output.writtenBytes());
    assertEquals(2 | 0x1000000, output.result().travelType());
    assertEquals(new BotMovement.History(3, 99, 77, 1, 555, 123, -5, ZERO), f.snapshot().history());
    assertEquals(avoidance, f.snapshot().reachAvoidance());
    assertEquals(512, f.snapshot().movementFlags());
    assertTrue(f.world.traces.isEmpty());
    assertEquals(1, f.reachCalls);
    assertEquals(3, f.executedSource);
  }

  @Test
  void airborneCrouchWritesOnlyTravelPrefixAndDoesNotExecuteOrAct() {
    var f = new Fixture(3 | 0x2000000);
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3(7, 8, 9)));
    f.states.updateMovementFlags(f.handle, 0);
    var output = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(24, output.writtenBytes());
    assertEquals(3 | 0x2000000, output.result().travelType());
    assertTrue(output.command().isEmpty());
    assertEquals(0, f.reachCalls);
    assertEquals(new BotMovement.History(3, 99, 77, 1, 555, 123, -5, ZERO), f.snapshot().history());
  }

  @Test
  void registeredBarrierPreservesJumpOnlyActionAndOrdinaryCacheFields() {
    var cleared = new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO);
    GroundMoveToGoal.ReachTravel jump =
        (input, flags, area, reach) -> new RichOutput(cleared, Optional.empty(), 16);
    GroundMoveToGoal.ReachTravel ascending =
        (input, flags, area, reach) -> new RichOutput(cleared, Optional.empty(), 0);
    var f = new Fixture(4, Map.of(4, jump), Map.of(4, ascending), false);
    f.states.updateHistory(f.handle, new BotMovement.History(1, 0, 99, 0, 3, 123, 0, ZERO));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(4, output.result().travelType());
    assertEquals(16, output.actionFlags());
    assertTrue(output.command().isEmpty());
    assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 6, ZERO), f.snapshot().history());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    assertEquals(2, f.snapshot().movementFlags()); // EA_Jump does not invent a barrier-state bit.
    f.states.updateMovementFlags(f.handle, 1);
    var cache = f.snapshot().history();
    output = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 100);
    assertEquals(52, output.writtenBytes());
    assertEquals(4, output.result().travelType());
    assertTrue(output.command().isEmpty());
    assertEquals(0, output.actionFlags());
    assertEquals(cache, f.snapshot().history());
    assertEquals(1, f.snapshot().movementFlags());
    var unavailable = new Fixture(4);
    var before = unavailable.snapshot();
    assertThrows(
        GroundMoveToGoal.UnsupportedMovement.class,
        () -> unavailable.service.execute(unavailable.handle, goal(2), POLICY, 1));
    assertEquals(before, unavailable.snapshot());
  }

  @Test
  void independentActionsDoNotInventMovementAndLegacyOutputKeepsActionBits() {
    var result = new MovementResult(0, 0, 0, 0, 0, 0, 0, new Vec3(1, 0, 0), ZERO);
    GroundMoveToGoal.ReachTravel actionOnly =
        (input, flags, area, reach) -> new RichOutput(result, Optional.empty(), 16);
    var f = new Fixture(7, Map.of(7, actionOnly));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(16, output.actionFlags());
    assertTrue(output.command().isEmpty());
    assertEquals(new Vec3(1, 0, 0), output.result().direction());
    assertEquals(52, output.writtenBytes());
    assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 6, ZERO), f.snapshot().history());
    var command = new GroundMoveToGoal.Command(ZERO, 200, 128);
    var legacy = new GroundMoveToGoal.Output(result, 52, Optional.of(command));
    assertEquals(128, legacy.actionFlags());
    assertEquals(command, legacy.command().orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () -> new GroundMoveToGoal.Output(result, 52, Optional.of(command), 16));
  }

  @Test
  void absentAirborneActionsStillWriteFullReachResultAndPreserveCache() {
    GroundMoveToGoal.ReachTravel noAction =
        (input, flags, area, reach) ->
            new RichOutput(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), Optional.empty(), 0);
    var f = new Fixture(7, Map.of(), Map.of(7, noAction), false);
    f.states.updateMovementFlags(f.handle, 0);
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3(7, 8, 9)));
    var output = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(52, output.writtenBytes());
    assertEquals(7, output.result().travelType());
    assertEquals(0, output.actionFlags());
    assertTrue(output.command().isEmpty());
    assertEquals(new BotMovement.History(3, 99, 77, 1, 555, 123, -5, ZERO), f.snapshot().history());
  }

  @Test
  void invalidOptionalMovementCannotPublishHistoryOrIndependentActions() {
    var invalid =
        new ReachMovementOutput.Move() {
          @Override
          public Vec3 direction() {
            return ZERO;
          }

          @Override
          public float speed() {
            return 401;
          }
        };
    GroundMoveToGoal.ReachTravel invalidTravel =
        (input, flags, area, reach) ->
            new RichOutput(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), Optional.of(invalid), 16);
    var f = new Fixture(7, Map.of(7, invalidTravel));
    var before = f.snapshot();
    assertThrows(
        IllegalArgumentException.class, () -> f.service.execute(f.handle, goal(2), POLICY, 1));
    assertEquals(before, f.snapshot());
  }

  private record RichOutput(
      MovementResult result, Optional<? extends ReachMovementOutput.Move> movement, int actionFlags)
      implements ReachMovementOutput {}

  @Test
  void teleportFlagSuppressesApproachWithoutSuppressingSelectionOrOverwritingInput() {
    GroundMoveToGoal.ReachTravel unexpected =
        (input, flags, area, reach) -> {
          throw new AssertionError("Completed teleport must not approach or issue EA_Move");
        };
    var f = new Fixture(10, Map.of(10, unexpected));
    f.states.updateMovementFlags(f.handle, 2 | 32);
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(52, output.writtenBytes());
    assertTrue(output.command().isEmpty());
    assertEquals(new MovementResult(0, 0, 0, 0, 10, 0, 0, ZERO, ZERO), output.result());
    assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 6, ZERO), f.snapshot().history());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    assertEquals(34, f.snapshot().movementFlags());
    assertEquals(1, f.world.traces.size());
    var bytes = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
    Arrays.fill(bytes.array(), (byte) 0xff);
    output.writeTo(bytes, 0);
    for (int word = 0; word < 13; word++) assertEquals(word == 4 ? 10 : 0, bytes.getInt(word * 4));
    var before = f.snapshot();
    f.service.execute(f.handle, goal(2), POLICY, 6);
    assertEquals(before, f.snapshot());
    f.service.execute(f.handle, goal(2), POLICY, Math.nextUp(6f));
    assertEquals(Math.nextUp(6f) + 5, f.snapshot().history().reachDeadline());
    assertEquals(2, f.snapshot().reachAvoidance().tries());
  }

  @Test
  void teleportFlagDoesNotBypassCachePolicyOrSameAreaMovement() {
    GroundMoveToGoal.ReachTravel unexpected =
        (input, flags, area, reach) -> {
          throw new AssertionError("Completed teleport approach");
        };
    var f = new Fixture(10 | 0x1000000, Map.of(10, unexpected));
    var history = new BotMovement.History(1, 1, 2, 1, 3, 8, 100, ZERO);
    f.states.updateHistory(f.handle, history);
    f.states.updateMovementFlags(f.handle, 34);
    var output =
        f.service.execute(f.handle, goal(2), TravelPolicy.ofFlags(TravelFlags.TELEPORT), 2);
    assertEquals(10 | 0x1000000, output.result().travelType());
    assertEquals(history, f.snapshot().history());
    output = f.service.execute(f.handle, goal(2), TravelPolicy.ofFlags(0), 2);
    assertEquals(24, output.writtenBytes());
    assertEquals(1, output.result().failure());
    assertEquals(0, f.snapshot().history().lastReachability());
    output = f.service.execute(f.handle, goal(1), TravelPolicy.ofFlags(0), 2);
    assertTrue(output.command().isPresent());
    assertEquals(1, f.goalCalls);
    assertEquals(34, f.snapshot().movementFlags());
  }

  @Test
  void airborneTeleportWritesOnlyTaggedPrefixAndPreservesExpiredCacheAndTeleportedFlag() {
    var f = new Fixture(10 | 0x2000000);
    var history = new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3(7, 8, 9));
    f.states.updateHistory(f.handle, history);
    f.states.updateMovementFlags(f.handle, 32 | 4 | 8 | 128);
    f.states.recordReachAttempt(f.handle, 1, 100, 1);
    var avoided = f.snapshot().reachAvoidance();
    var output = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(24, output.writtenBytes());
    assertEquals(10 | 0x2000000, output.result().travelType());
    assertTrue(output.command().isEmpty());
    assertEquals(0, f.reachCalls);
    assertTrue(f.world.traces.isEmpty());
    assertEquals(new BotMovement.History(3, 99, 77, 1, 555, 123, -5, ZERO), f.snapshot().history());
    assertEquals(avoided, f.snapshot().reachAvoidance());
    assertEquals(32, f.snapshot().movementFlags());
    var bytes = ByteBuffer.allocate(52);
    Arrays.fill(bytes.array(), (byte) 0xff);
    output.writeTo(bytes, 0);
    for (int i = 24; i < 52; i++) assertEquals((byte) 0xff, bytes.get(i));
  }

  @Test
  void activeTeleportUsesRegisteredApproachAndOrdinaryBlockedDeadline() {
    GroundMoveToGoal.ReachTravel teleport =
        (input, flags, area, reach) ->
            new GroundReachMovement.Output(
                new MovementResult(0, 0, 1, 15, 0, 0, 0, ZERO, ZERO), ZERO, 200, 0);
    var f = new Fixture(10, Map.of(10, teleport));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(10, output.result().travelType());
    assertEquals(200, output.command().orElseThrow().speed());
    assertEquals(5, f.snapshot().history().reachDeadline());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    var unavailable = new Fixture(10);
    assertThrows(
        GroundMoveToGoal.UnsupportedMovement.class,
        () -> unavailable.service.execute(unavailable.handle, goal(2), POLICY, 1));
  }

  @Test
  void registeredJumpPadUsesTenSecondDeadlineAndRetainsItsRawCommand() {
    GroundMoveToGoal.ReachTravel pad =
        (input, flags, area, reach) -> {
          var raw = new Vec3(-50, -30, 0);
          return new GroundReachMovement.Output(
              new MovementResult(0, 0, 0, 0, 0, 0, 0, raw, ZERO), raw, 400, 0);
        };
    var f = new Fixture(18, Map.of(18, pad));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(18, output.result().travelType());
    assertEquals(new Vec3(-50, -30, 0), output.command().orElseThrow().direction());
    assertEquals(11, f.snapshot().history().reachDeadline());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 11);
    assertEquals(11, f.snapshot().history().reachDeadline());
    f.service.execute(f.handle, goal(2), POLICY, Math.nextUp(11f));
    assertEquals(Math.nextUp(11f) + 10, f.snapshot().history().reachDeadline());
    assertEquals(0, f.reachCalls);
  }

  @Test
  void registeredGroundExecutorCannotEnableUnverifiedAirborneOrOtherTravel() {
    GroundMoveToGoal.ReachTravel pad =
        (input, flags, area, reach) -> {
          throw new AssertionError("Ground provider called in air");
        };
    var f = new Fixture(18, Map.of(18, pad));
    f.states.updateHistory(f.handle, new BotMovement.History(1, 1, 2, 1, 1, 0, 100, ZERO));
    f.states.updateMovementFlags(f.handle, 0);
    var before = f.snapshot();
    assertThrows(
        GroundMoveToGoal.UnsupportedMovement.class,
        () -> f.service.execute(f.handle, goal(2), POLICY, 1));
    assertEquals(before, f.snapshot());
    assertThrows(IllegalArgumentException.class, () -> new Fixture(5, Map.of(5, pad)));
    var unavailable = new Fixture(18);
    assertThrows(
        GroundMoveToGoal.UnsupportedMovement.class,
        () -> unavailable.service.execute(unavailable.handle, goal(2), POLICY, 1));
  }

  @Test
  void airbornePadContactReplacesOnlyLastAreaAndLinkBeforeExecution() {
    int[] source = {0};
    GroundMoveToGoal.ReachTravel air =
        (input, flags, area, reach) -> {
          source[0] = area;
          return new GroundReachMovement.Output(
              new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), ZERO, 200, 0);
        };
    var f = new Fixture(18, Map.of(), Map.of(18, air), true);
    f.states.updateMovementFlags(f.handle, 0);
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 99, 77, 0, 555, 123, -5, new Vec3(7, 8, 9)));
    var output = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(new BotMovement.History(3, 1, 77, 2, 555, 123, -5, ZERO), f.snapshot().history());
    assertEquals(3, source[0]);
    assertEquals(18, output.result().travelType());
    assertEquals(200, output.command().orElseThrow().speed());
    assertEquals(new BotMovement.ReachAvoidance(0, 0, 0), f.snapshot().reachAvoidance());
  }

  @Test
  void unsupportedContactAndFailedAirExecutionLeaveStateUntouched() {
    var f = new Fixture(18, Map.of(), Map.of(), true);
    f.states.updateMovementFlags(f.handle, 0);
    var before = f.snapshot();
    var failure =
        assertThrows(
            GroundMoveToGoal.UnsupportedMovement.class,
            () -> f.service.execute(f.handle, goal(0), POLICY, 1));
    assertTrue(failure.getMessage().contains("selectedReach=2"));
    assertEquals(before, f.snapshot());
    GroundMoveToGoal.ReachTravel air =
        (input, flags, area, reach) -> {
          throw new IllegalStateException("Authored air failure");
        };
    var bad = new Fixture(18, Map.of(), Map.of(18, air), true);
    bad.states.updateMovementFlags(bad.handle, 0);
    var prior = bad.snapshot();
    assertThrows(
        IllegalStateException.class, () -> bad.service.execute(bad.handle, goal(0), POLICY, 1));
    assertEquals(prior, bad.snapshot());
  }

  @Test
  void registeredLedgeUsesFiveSecondDeadlineAndSixSecondAvoidance() {
    GroundMoveToGoal.ReachTravel ledge =
        (input, flags, area, reach) ->
            new GroundReachMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), ZERO, 100, 0);
    var f = new Fixture(7, Map.of(7, ledge));
    f.states.updateHistory(f.handle, new BotMovement.History(1, 0, 99, 0, 3, 123, 0, ZERO));
    var result = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(7, result.result().travelType());
    assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 6, ZERO), f.snapshot().history());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 6);
    assertEquals(6, f.snapshot().history().reachDeadline());
  }

  @Test
  void cachedAirborneLedgeRetainsHistoryAndIgnoresGoalAndTravelPermissions() {
    GroundMoveToGoal.ReachTravel ledge =
        (input, flags, area, reach) ->
            new GroundReachMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), ZERO, 100, 0);
    var f = new Fixture(7, Map.of(), Map.of(7, ledge), false);
    f.states.updateMovementFlags(f.handle, 0);
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3(7, 8, 9)));
    var result = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(7, result.result().travelType());
    assertEquals(new BotMovement.History(3, 99, 77, 1, 555, 123, -5, ZERO), f.snapshot().history());
    assertEquals(100, result.command().orElseThrow().speed());
  }

  @Test
  void blockedReachShortensDeadlineOnceButGoalAreaMovementDoesNot() {
    GroundMoveToGoal.ReachTravel blocked =
        (input, flags, area, reach) ->
            new GroundReachMovement.Output(
                new MovementResult(0, 0, 1, 15, 0, 0, 0, ZERO, ZERO), ZERO, 100, 0);
    var f = new Fixture(7, Map.of(7, blocked), Map.of(7, blocked), false);
    f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(5, f.snapshot().history().reachDeadline());
    assertEquals(7, f.snapshot().reachAvoidance().expiresAt());
    f.states.updateMovementFlags(f.handle, 0);
    f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(4, f.snapshot().history().reachDeadline());
    var sameArea = new Fixture(2);
    sameArea.blockedTravel = true;
    sameArea.states.updateHistory(
        sameArea.handle, new BotMovement.History(1, 1, 1, 1, 1, 0, 99, ZERO));
    assertEquals(
        1, sameArea.service.execute(sameArea.handle, goal(1), POLICY, 10).result().blocked());
    assertEquals(99, sameArea.snapshot().history().reachDeadline());
  }

  @Test
  void liquidContactRoutesWithoutGroundAndPreservesTheWaterJumpFlag() {
    for (int contents : new int[] {8, 16, 32, 56}) {
      int[] observed = new int[2];
      GroundMoveToGoal.ReachTravel swim =
          (input, flags, area, reach) -> {
            observed[0] = flags;
            observed[1] = area;
            return new GroundReachMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 2, 0, new Vec3(1, 0, 0), ZERO),
                new Vec3(1, 0, 0),
                400,
                0);
          };
      var f =
          new Fixture(
              8,
              Map.of(8, swim),
              Map.of(8, swim),
              false,
              (input, flags, area, goal) -> {
                throw new AssertionError("Unexpected goal callback");
              });
      f.world.contents = contents;
      f.states.updateMovementFlags(f.handle, 16 | 4 | 8 | 128);
      var output = f.service.execute(f.handle, goal(2), POLICY, 1);
      assertEquals(8, output.result().travelType());
      assertEquals(2, output.result().flags());
      assertArrayEquals(new int[] {20, 1}, observed);
      assertEquals(20, f.snapshot().movementFlags());
      assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 6, ZERO), f.snapshot().history());
      assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
      assertEquals(List.of(new Vec3(0, 0, -2)), f.world.contentPoints);
      assertTrue(f.world.traces.isEmpty());
    }
  }

  @Test
  void sameAreaSwimmingUsesTheOptionalGoalCallbackAndPreservesUnrelatedHistory() {
    var calls = new ArrayList<Integer>();
    var f =
        new Fixture(
            8,
            Map.of(),
            Map.of(),
            false,
            (input, flags, source, goal) -> {
              calls.add(flags);
              return new LiquidReachMovement.Output(
                  new MovementResult(0, 0, 0, 0, 8, 2, 0, new Vec3(0, 0, 1), new Vec3(-90, 0, 0)),
                  Optional.of(new LiquidReachMovement.Move(new Vec3(0, 0, 1), 10)),
                  0);
            });
    f.world.contents = 32;
    f.states.updateMovementFlags(f.handle, 0);
    f.states.updateHistory(
        f.handle, new BotMovement.History(99, 123, 999, 1, 3, 8, 100, new Vec3(4, 5, 6)));
    var output = f.service.execute(f.handle, goal(1), POLICY, 2);
    assertEquals(List.of(4), calls);
    assertEquals(0, f.goalCalls);
    assertEquals(52, output.writtenBytes());
    assertEquals(10, output.command().orElseThrow().speed());
    assertEquals(new BotMovement.History(1, 0, 1, 0, 3, 8, 100, ZERO), f.snapshot().history());
  }

  @Test
  void liquidCallbackFailureAndLegacyConstructorLeaveStateUnchanged() {
    var legacy = new Fixture(8);
    legacy.world.contents = 32;
    var before = legacy.snapshot();
    assertThrows(
        UnsupportedOperationException.class,
        () -> legacy.service.execute(legacy.handle, goal(2), POLICY, 1));
    assertEquals(before, legacy.snapshot());
    var f =
        new Fixture(
            8,
            Map.of(),
            Map.of(),
            false,
            (input, flags, source, goal) -> {
              throw new IllegalStateException("Authored liquid callback failure");
            });
    f.world.contents = 32;
    before = f.snapshot();
    assertThrows(
        IllegalStateException.class, () -> f.service.execute(f.handle, goal(1), POLICY, 1));
    assertEquals(before, f.snapshot());
  }

  @Test
  void cachedAirSwimReusesEntryWhileWaterJumpFinishesWithoutAnInputCommand() {
    var liquid = new LiquidReachMovement(new MovementObstruction(new World(), a -> 1), () -> 0);
    for (int kind : new int[] {8, 9}) {
      GroundMoveToGoal.ReachTravel executor = kind == 8 ? liquid::execute : liquid::finish;
      var f = new Fixture(kind, Map.of(), Map.of(kind, executor), false);
      f.states.updateMovementFlags(f.handle, 16 | 4 | 8 | 128);
      var history = new BotMovement.History(3, 1, 2, 1, 3, 7, -5, new Vec3(7, 8, 9));
      f.states.updateHistory(f.handle, history);
      var output = f.service.execute(f.handle, goal(3), TravelPolicy.ofFlags(0), 100);
      assertEquals(52, output.writtenBytes());
      assertEquals(kind, output.result().travelType());
      assertEquals(kind == 8, output.command().isPresent());
      assertEquals(0, output.actionFlags());
      assertEquals(16, f.snapshot().movementFlags());
      assertEquals(new BotMovement.History(3, 1, 2, 1, 3, 7, -5, ZERO), f.snapshot().history());
    }
  }

  @Test
  void bobbingReachPublishesRuntimeFlagsWithTheTenSecondRouteAndCachedAirHistory() {
    GroundMoveToGoal.ReachTravel travel =
        (input, flags, area, reach) ->
            new BobbingPlatformMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO),
                Optional.empty(),
                16,
                flags | 1);
    var f = new Fixture(19, Map.of(19, travel), Map.of(19, travel), false);
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(19, output.result().travelType());
    assertEquals(16, output.actionFlags());
    assertTrue(output.command().isEmpty());
    assertEquals(3, f.snapshot().movementFlags());
    assertEquals(11, f.snapshot().history().reachDeadline());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 10);
    assertEquals(11, f.snapshot().history().reachDeadline());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    f.states.updateMovementFlags(f.handle, 0);
    var history = f.snapshot().history();
    var airborne = f.service.execute(f.handle, goal(3), TravelPolicy.ofFlags(0), 100);
    assertEquals(19, airborne.result().travelType());
    assertEquals(1, f.snapshot().movementFlags());
    assertEquals(history, f.snapshot().history());
  }

  @Test
  void bobbingDeadlineEffectPublishesWithValidatedFlagsAndForcesLaterReselection() {
    GroundMoveToGoal.ReachTravel travel =
        (input, flags, area, reach) ->
            new BobbingPlatformMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO),
                Optional.empty(),
                16,
                flags | 1,
                true);
    var f = new Fixture(19, Map.of(19, travel));
    f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(0, f.snapshot().history().reachDeadline());
    assertEquals(3, f.snapshot().movementFlags());
    f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(0, f.snapshot().history().reachDeadline());
    assertEquals(new BotMovement.ReachAvoidance(1, 8, 2), f.snapshot().reachAvoidance());
  }

  @Test
  void cachedBobbingReachIgnoresAreaAndGoalChangesUntilItsDeadline() {
    GroundMoveToGoal.ReachTravel travel =
        (input, flags, area, reach) ->
            new BobbingPlatformMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), Optional.empty(), 0, flags);
    var f = new Fixture(19, Map.of(19, travel));
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 3, 3, 1, 77, 2, 10, new Vec3(1, 2, 3)));
    f.service.execute(f.handle, goal(2), POLICY, 10);
    assertEquals(new BotMovement.History(1, 1, 2, 1, 77, 2, 10, ZERO), f.snapshot().history());
    assertEquals(BotMovement.ReachAvoidance.EMPTY, f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 11);
    assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 21, ZERO), f.snapshot().history());
    assertEquals(new BotMovement.ReachAvoidance(1, 17, 1), f.snapshot().reachAvoidance());
  }

  @Test
  void standingBobbingReachRefreshesDeadlineWithoutResettingItsSourceOrAvoidance() {
    var selected = new ArrayList<Integer>();
    GroundMoveToGoal.ReachTravel travel =
        (input, flags, area, reach) ->
            new BobbingPlatformMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), Optional.empty(), 0, flags);
    var f =
        new Fixture(
            19,
            Map.of(19, travel),
            Map.of(),
            false,
            null,
            Map.of(),
            Map.of(),
            (entity, previous) -> {
              assertEquals(17, entity);
              selected.add(previous);
              return 1;
            });
    f.movingPlatform = true;
    f.world.entity = 17;
    f.world.fraction = 1;
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 3, 3, 2, 77, 2, -2, new Vec3(1, 2, 3)));
    f.states.recordReachAttempt(f.handle, 2, 20, 1);
    var avoidance = f.snapshot().reachAvoidance();
    var output = f.service.execute(f.handle, goal(2), POLICY, 10);
    assertEquals(List.of(2), selected);
    assertEquals(new BotMovement.History(1, 1, 2, 1, 77, 2, 15, ZERO), f.snapshot().history());
    assertEquals(avoidance, f.snapshot().reachAvoidance());
    assertEquals(52, output.writtenBytes());
    assertEquals(0, output.result().flags());
  }

  @Test
  void standingPlatformEarlyReturnsPreserveCachedDeadlineOrStoreAReplacement() {
    for (int area : new int[] {0, 1}) {
      for (int previous : new int[] {0, 2}) {
        var f =
            new Fixture(
                19,
                Map.of(),
                Map.of(),
                false,
                null,
                Map.of(),
                Map.of(),
                (entity, cached) -> cached == 0 ? 1 : cached);
        f.movingPlatform = true;
        f.world.entity = 17;
        f.area = area;
        var history = new BotMovement.History(3, 3, 3, previous, 77, 2, 7, new Vec3(1, 2, 3));
        f.states.updateHistory(f.handle, history);
        var output = f.service.execute(f.handle, goal(1), POLICY, 10);
        int deadline = previous == 0 ? 20 : 7;
        assertEquals(deadline, f.snapshot().history().reachDeadline());
        assertEquals(77, f.snapshot().history().reachArea());
        assertEquals(2, f.snapshot().history().jumpReachability());
        if (area == 0) {
          assertEquals(24, output.writtenBytes());
          assertEquals(64, output.result().flags());
          assertEquals(
              new BotMovement.History(
                  0, 3, 3, previous == 0 ? 1 : previous, 77, 2, deadline, new Vec3(1, 2, 3)),
              f.snapshot().history());
        } else {
          assertEquals(52, output.writtenBytes());
          assertEquals(0, output.result().flags());
          assertEquals(
              new BotMovement.History(1, 0, 1, 0, 77, 2, deadline, ZERO), f.snapshot().history());
        }
      }
    }
  }

  @Test
  void deniedStandingTravelPreservesTheIncomingOrNewlySelectedDeadline() {
    for (int previous : new int[] {0, 1}) {
      var f =
          new Fixture(
              19, Map.of(), Map.of(), false, null, Map.of(), Map.of(), (entity, cached) -> 1);
      f.movingPlatform = true;
      f.world.entity = 17;
      f.states.updateHistory(f.handle, new BotMovement.History(1, 1, 2, previous, 77, 2, 7, ZERO));
      var output = f.service.execute(f.handle, goal(3), TravelPolicy.ofFlags(0), 10);
      assertEquals(1, output.result().failure());
      assertEquals(64, output.result().flags());
      assertEquals(24, output.writtenBytes());
      assertEquals(previous == 0 ? 20 : 7, f.snapshot().history().reachDeadline());
      assertEquals(0, f.snapshot().history().lastReachability());
    }
  }

  @Test
  void arrivalInvalidatesBobbingCacheWithOrWithoutAStandingContact() {
    for (boolean standing : new boolean[] {false, true}) {
      var f =
          new Fixture(
              19, Map.of(), Map.of(), false, null, Map.of(), Map.of(), (entity, cached) -> 1);
      f.movingPlatform = standing;
      if (standing) f.world.entity = 17;
      f.area = 2;
      f.states.updateHistory(f.handle, new BotMovement.History(2, 2, 3, 1, 77, 2, 77, ZERO));
      var output = f.service.execute(f.handle, goal(3), POLICY, 10);
      assertEquals(1, output.result().failure());
      assertEquals(standing ? 64 : 0, output.result().flags());
      assertEquals(0, f.snapshot().history().lastReachability());
      assertEquals(77, f.snapshot().history().reachDeadline());
      assertEquals(2, f.snapshot().history().reachArea());
      assertEquals(0, f.snapshot().history().jumpReachability());
    }
  }

  @Test
  void unrepresentedBobbingModelUsesTheOrdinaryEntityBlockedPrefix() {
    var f =
        new Fixture(
            19, Map.of(), Map.of(), false, null, Map.of(), Map.of(), (entity, previous) -> 0);
    f.movingPlatform = true;
    f.world.entity = 17;
    var before = f.snapshot();
    var output = f.service.execute(f.handle, goal(2), POLICY, 10);
    assertEquals(24, output.writtenBytes());
    assertEquals(32, output.result().flags());
    assertEquals(17, output.result().blockEntity());
    assertEquals(before, f.snapshot());
  }

  @Test
  void invalidFlaggedTravelLeavesRuntimeFlagsAndHistoryUnchanged() {
    GroundMoveToGoal.ReachTravel travel =
        (input, flags, area, reach) ->
            new FlaggedEffects(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO),
                Optional.of(new JumpReachMovement.Move(new Vec3(1, 0, 0), 401)),
                flags | 1,
                16);
    var f = new Fixture(19, Map.of(19, travel));
    var before = f.snapshot();
    assertThrows(
        IllegalArgumentException.class, () -> f.service.execute(f.handle, goal(2), POLICY, 1));
    assertEquals(before, f.snapshot());
  }

  private record FlaggedEffects(
      MovementResult result,
      Optional<JumpReachMovement.Move> movement,
      int movementFlags,
      int actionFlags)
      implements FlaggedReachMovementOutput {}

  @Test
  void statefulJumpReceivesFreshAndRetainedHistoryAndPublishesTheReturnedReach() {
    var calls = new ArrayList<List<Integer>>();
    GroundMoveToGoal.StatefulReachTravel jump =
        (input, flags, area, reach, last, active) -> {
          calls.add(List.of(last, active));
          return new JumpReachMovement.Output(
              new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), Optional.empty(), 32768, last);
        };
    var f = new Fixture(5, Map.of(), Map.of(), false, null, Map.of(5, jump), Map.of());
    var out = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(List.of(List.of(1, 0)), calls);
    assertEquals(1, f.snapshot().history().jumpReachability());
    assertEquals(32768, out.actionFlags());
    assertTrue(out.command().isEmpty());
    f.service.execute(f.handle, goal(2), POLICY, 6);
    assertEquals(List.of(List.of(1, 0), List.of(1, 1)), calls);
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
  }

  @Test
  void statefulAirCompletionRetainsCacheAndPreservesAbsentMovement() {
    GroundMoveToGoal.StatefulReachTravel finish =
        (input, flags, area, reach, last, active) -> {
          assertEquals(1, last);
          assertEquals(2, active);
          assertEquals(77, area);
          return new JumpReachMovement.Output(
              new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), Optional.empty(), 0, 1);
        };
    var f = new Fixture(5, Map.of(), Map.of(), false, null, Map.of(), Map.of(5, finish));
    f.states.updateMovementFlags(f.handle, 0);
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 1, 2, 1, 77, 2, -5, new Vec3(7, 8, 9)));
    var out = f.service.execute(f.handle, goal(3), TravelPolicy.ofFlags(0), 100);
    assertEquals(52, out.writtenBytes());
    assertEquals(5, out.result().travelType());
    assertTrue(out.command().isEmpty());
    assertEquals(new BotMovement.History(3, 1, 2, 1, 77, 1, -5, ZERO), f.snapshot().history());
  }

  @Test
  void invalidStatefulResultsAndEffectsPublishNoPartialHistory() {
    var clear = new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO);
    var bad =
        List.of(
            new Effects(clear, Optional.empty(), 0, 99, Optional.empty(), OptionalInt.empty()),
            new Effects(
                clear,
                Optional.of(new JumpReachMovement.Move(ZERO, 401)),
                0,
                1,
                Optional.empty(),
                OptionalInt.empty()),
            new Effects(
                clear,
                Optional.empty(),
                0,
                1,
                Optional.of(new Vec3(Double.MAX_VALUE, 0, 0)),
                OptionalInt.empty()),
            new Effects(clear, Optional.empty(), 0, 1, Optional.empty(), null));
    for (var output : bad) {
      GroundMoveToGoal.StatefulReachTravel callback =
          (input, flags, area, reach, last, active) -> output;
      var f = new Fixture(5, Map.of(), Map.of(), false, null, Map.of(5, callback), Map.of());
      var before = f.snapshot();
      assertThrows(RuntimeException.class, () -> f.service.execute(f.handle, goal(2), POLICY, 1));
      assertEquals(before, f.snapshot());
    }
  }

  @Test
  void viewAndWeaponEffectsAreExplicitAndIndependentOfMovementAndResultFlags() {
    var clear = new MovementResult(0, 0, 0, 0, 0, 24, 5, ZERO, new Vec3(9, 8, 7));
    var explicit =
        new Effects(
            clear, Optional.empty(), 16, 0, Optional.of(new Vec3(-90, 45, 0)), OptionalInt.of(9));
    var f = new Fixture(4, Map.of(4, (input, flags, area, reach) -> explicit));
    var out = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertTrue(out.command().isEmpty());
    assertEquals(16, out.actionFlags());
    assertEquals(explicit.view(), out.view());
    assertEquals(explicit.weapon(), out.weapon());
    var noEffects =
        new Fixture(
            4,
            Map.of(
                4,
                (input, flags, area, reach) ->
                    new JumpReachMovement.Output(clear, Optional.empty(), 0, 0)));
    var absent = noEffects.service.execute(noEffects.handle, goal(2), POLICY, 1);
    assertTrue(absent.view().isEmpty());
    assertTrue(absent.weapon().isEmpty());
  }

  private record Effects(
      MovementResult result,
      Optional<JumpReachMovement.Move> movement,
      int actionFlags,
      int jumpReach,
      Optional<Vec3> view,
      OptionalInt weapon)
      implements StatefulReachMovementOutput {}

  private static AasMap contactMap(int kind) {
    var m = map(kind);
    var settings = new ArrayList<>(m.areaSettings());
    settings.set(1, new AasMap.AreaSettings(128, 1, 6, 1, 0, 2, 1));
    return new AasMap(
        m.version(),
        m.bspChecksum(),
        m.lumps(),
        m.boundingBoxes(),
        m.vertices(),
        List.of(new AasMap.Plane(new Vec3(1, 0, 0), 0, 0)),
        m.edges(),
        m.edgeIndices(),
        m.faces(),
        m.faceIndices(),
        m.areas(),
        settings,
        m.reachabilities(),
        List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, -1, -1)),
        m.portals(),
        m.portalIndices(),
        m.clusters());
  }

  private static final class Fixture {
    final BotMovement states = new BotMovement(ignored -> {});
    final int handle = states.allocate();
    final World world = new World();
    final GroundMoveToGoal service;
    int area = 1, goalCalls, reachCalls, executedSource;
    boolean movingPlatform, failTravel, onGround, blockedTravel;
    int groundChecks;

    Fixture(int kind) {
      this(kind, Map.of());
    }

    Fixture(int kind, Map<Integer, GroundMoveToGoal.ReachTravel> extra) {
      this(kind, extra, Map.of(), false);
    }

    Fixture(
        int kind,
        Map<Integer, GroundMoveToGoal.ReachTravel> extra,
        Map<Integer, GroundMoveToGoal.ReachTravel> air,
        boolean contact) {
      this(kind, extra, air, contact, null);
    }

    Fixture(
        int kind,
        Map<Integer, GroundMoveToGoal.ReachTravel> extra,
        Map<Integer, GroundMoveToGoal.ReachTravel> air,
        boolean contact,
        GroundMoveToGoal.GoalTravel liquidGoalTravel) {
      this(kind, extra, air, contact, liquidGoalTravel, Map.of(), Map.of());
    }

    Fixture(
        int kind,
        Map<Integer, GroundMoveToGoal.ReachTravel> extra,
        Map<Integer, GroundMoveToGoal.ReachTravel> air,
        boolean contact,
        GroundMoveToGoal.GoalTravel liquidGoalTravel,
        Map<Integer, GroundMoveToGoal.StatefulReachTravel> statefulGround,
        Map<Integer, GroundMoveToGoal.StatefulReachTravel> statefulAir) {
      this(kind, extra, air, contact, liquidGoalTravel, statefulGround, statefulAir, null);
    }

    Fixture(
        int kind,
        Map<Integer, GroundMoveToGoal.ReachTravel> extra,
        Map<Integer, GroundMoveToGoal.ReachTravel> air,
        boolean contact,
        GroundMoveToGoal.GoalTravel liquidGoalTravel,
        Map<Integer, GroundMoveToGoal.StatefulReachTravel> statefulGround,
        Map<Integer, GroundMoveToGoal.StatefulReachTravel> statefulAir,
        GroundMoveToGoal.PlatformReach platformReach) {
      var map = contact ? contactMap(kind) : map(kind);
      states.initialize(handle, new MovementInit(ZERO, ZERO, ZERO, 0, 0, .1f, 2, ZERO, 2));
      service =
          new GroundMoveToGoal(
              states,
              new AasNavigation(map),
              p -> area,
              new AasMovementRoutes(map),
              world,
              (origin, presence, entity) -> {
                groundChecks++;
                assertEquals(
                    states.snapshot(handle).orElseThrow().input().orElseThrow().origin(), origin);
                return onGround;
              },
              e -> movingPlatform,
              (input, flags, source, reach) -> {
                reachCalls++;
                executedSource = source;
                return travel();
              },
              (input, flags, source, goal) -> {
                goalCalls++;
                return travel();
              },
              extra,
              air,
              liquidGoalTravel,
              statefulGround,
              statefulAir,
              platformReach);
    }

    GroundReachMovement.Output travel() {
      if (failTravel) throw new IllegalStateException("Authored executor failure");
      var direction = new Vec3(1, 0, 0);
      return new GroundReachMovement.Output(
          new MovementResult(
              0, 0, blockedTravel ? 1 : 0, blockedTravel ? 15 : 0, 2, 0, 0, direction, ZERO),
          direction,
          400,
          0);
    }

    BotMovement.Snapshot snapshot() {
      return states.snapshot(handle).orElseThrow();
    }
  }

  private static final class World implements TraceWorld {
    final List<TraceRequest> traces = new ArrayList<>();
    final List<Vec3> contentPoints = new ArrayList<>();
    int entity = 1023, contents;
    double fraction = .5;

    @Override
    public TraceResult trace(TraceRequest request) {
      traces.add(request);
      if (entity == 1023) return TraceResult.clear(request);
      return new TraceResult(
          fraction,
          request.start(),
          false,
          false,
          Optional.of(
              new TraceResult.Hit(TraceResult.Plane.NONE, 1, 0, entity, 0, -1, -1, -1, "fixture")));
    }

    @Override
    public int pointContents(Vec3 point, int mask, int ignoreEntity) {
      assertEquals(-1, mask);
      assertEquals(-1, ignoreEntity);
      contentPoints.add(point);
      return contents;
    }
  }

  private static AasMap map(int kind) {
    var areas = new ArrayList<AasMap.Area>();
    for (int i = 0; i < 4; i++) areas.add(new AasMap.Area(i, 0, 0, ZERO, ZERO, ZERO));
    var settings =
        List.of(
            new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AasMap.AreaSettings(0, 1, 6, 1, 0, 2, 1),
            new AasMap.AreaSettings(0, 1, 6, 1, 1, 1, 3),
            new AasMap.AreaSettings(0, 1, 6, 1, 2, 1, 4));
    var reaches =
        List.of(
            new AasMap.Reachability(0, 0, 0, ZERO, ZERO, 1, 0, 0),
            new AasMap.Reachability(2, 0, 0, new Vec3(10, 0, 0), new Vec3(20, 0, 0), kind, 1, 0),
            new AasMap.Reachability(3, 0, 0, new Vec3(10, 10, 0), new Vec3(20, 10, 0), kind, 20, 0),
            new AasMap.Reachability(2, 0, 0, ZERO, ZERO, 2, 1, 0),
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
        List.of(new AasMap.Cluster(0, 0, 0, 0), new AasMap.Cluster(3, 3, 0, 0)));
  }
}
