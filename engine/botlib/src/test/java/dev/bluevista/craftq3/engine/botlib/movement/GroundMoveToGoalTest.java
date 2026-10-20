package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.engine.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.engine.botlib.goal.Goal;
import dev.bluevista.craftq3.engine.botlib.testing.TraceRequest;
import dev.bluevista.craftq3.engine.botlib.testing.TraceResult;
import dev.bluevista.craftq3.engine.botlib.testing.TraceWorld;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Move-to-goal orchestration. The expected histories, deadlines and avoidance records were captured
 * from the native botlib oracle (ioquake3 588393618dbc built with -ffp-contract=off; see {@code
 * BOTLIB_GROUND_GOAL.md} and the {@code *Oracle.c} harnesses on {@code main}) and are kept
 * verbatim. Situations the legacy port rejected with an exception now degrade to a failed result.
 */
final class GroundMoveToGoalTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);
  private static final TravelPolicy POLICY = TravelPolicy.ofFlags(TravelFlags.ALL);

  /** History after selecting reach 1 from area 1 towards area 2 at time 1. */
  private static final BotMovement.History SELECTED_AT_ONE =
      BotMovement.History.EMPTY
          .withArea(1)
          .withLastArea(1)
          .withLastGoalArea(2)
          .withLastReachability(1)
          .withReachArea(1)
          .withReachDeadline(6);

  private static void assertDegraded(GroundMoveToGoal.Output output, String reason) {
    assertEquals(GroundMoveToGoal.PREFIX_BYTES, output.writtenBytes());
    assertEquals(1, output.result().failure());
    assertTrue(output.command().isEmpty());
    assertTrue(
        output.degraded().orElseThrow().contains(reason), () -> "Degraded: " + output.degraded());
  }

  @Test
  void newGroundLinkRecordsAttemptAndRetainsItThroughInclusiveDeadline() {
    var f = new Fixture(2);
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(52, output.writtenBytes());
    assertEquals(
        new GroundMoveToGoal.Command(new Vec3f(1, 0, 0), 400, 0), output.command().orElseThrow());
    var before = f.snapshot();
    assertEquals(SELECTED_AT_ONE, before.history());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), before.reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 6);
    assertEquals(before.history(), f.snapshot().history());
    assertEquals(before.reachAvoidance(), f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, Math.nextUp(6f));
    assertEquals(Math.nextUp(6f) + 5, f.snapshot().history().reachDeadline());
    assertEquals(2, f.snapshot().reachAvoidance().tries());
  }

  @Test
  void deniedCachedTravelSelectsAgainAndWritesOnlyTheResultPrefix() {
    var f = new Fixture(2);
    f.states.updateHistory(
        f.handle, new BotMovement.History(1, 1, 2, 1, 3, 8, 100, new Vec3f(4, 5, 6)));
    var output = f.service.execute(f.handle, goal(2), TravelPolicy.ofFlags(0), 1);
    assertEquals(24, output.writtenBytes());
    assertTrue(output.command().isEmpty());
    assertEquals(1, output.result().failure());
    assertTrue(output.degraded().isEmpty());
    assertEquals(new BotMovement.History(1, 1, 2, 0, 1, 0, 100, ZERO), f.snapshot().history());
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
        f.handle, new BotMovement.History(99, 123, 999, 1, 3, 8, 100, new Vec3f(4, 5, 6)));
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
        f.handle, new BotMovement.History(99, 123, 999, 1, 3, 8, 100, new Vec3f(4, 5, 6)));
    var output = f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(24, output.writtenBytes());
    assertEquals(1, output.result().failure());
    assertEquals(8, output.result().type());
    assertEquals(1, output.result().blocked());
    assertEquals(
        new BotMovement.History(0, 123, 999, 1, 3, 8, 100, new Vec3f(4, 5, 6)),
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
        // The grapple reset runs first and drops the unused active-grapple flag (128).
        assertEquals(2 | 4 | 8, f.snapshot().movementFlags());
        assertEquals(before.history(), f.snapshot().history());
        assertEquals(before.reachAvoidance(), f.snapshot().reachAvoidance());
        assertTrue(f.world.contentPoints.isEmpty());
        // A platform model without a reachability that rides it is an obstacle too.
        f.movingPlatform = true;
        output = f.service.execute(f.handle, goal(2), POLICY, 2);
        assertEquals(24, output.writtenBytes());
        assertEquals(1, output.result().blocked());
        assertEquals(entity, output.result().blockEntity());
        assertEquals(32, output.result().flags());
        assertEquals(before.history(), f.snapshot().history());
      }
    }
  }

  @Test
  void groundTraceUsesPresenceHullAndDerivedFlagsAreSeparateFromPlayerInput() {
    var f = new Fixture(2);
    var input = new MovementInit(new Vec3f(4, 5, 6), ZERO, ZERO, 7, 1, .1f, 4, ZERO, 2);
    f.states.initialize(f.handle, input);
    f.states.updateMovementFlags(f.handle, 2 | 4 | 8 | 128 | 256);
    f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(
        new TraceRequest(
            input.origin(),
            new Vec3f(4, 5, 3),
            new Vec3f(-15, -15, -24),
            new Vec3f(15, 15, 8),
            65537,
            7),
        f.world.traces.getFirst());
    assertEquals(List.of(new Vec3f(4, 5, 4)), f.world.contentPoints);
    // Selecting a new reachability also clears the grapple-reset flag (256).
    assertEquals(2, f.snapshot().movementFlags());
    assertEquals(input, f.snapshot().input().orElseThrow());
  }

  @Test
  void unsupportedTravelDegradesButExecutorFailurePublishesNoPartialState() {
    // A travel type without an executor fails softly and keeps the selection, so the timed
    // reach avoidance eventually routes the bot around it.
    var f = new Fixture(5);
    var output = f.service.execute(f.handle, goal(2), POLICY, 2);
    assertDegraded(output, "travel type jump");
    assertEquals(5, output.result().travelType());
    assertEquals(1, f.snapshot().history().lastReachability());
    assertEquals(new BotMovement.ReachAvoidance(1, 8, 1), f.snapshot().reachAvoidance());
    var ordinary = new Fixture(2);
    BotMovement.Snapshot before;
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
    f.states.addAvoidSpot(f.handle, new BotMovement.AvoidSpot(new Vec3f(10, 0, 0), 1, 1));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(256, output.result().flags());
    assertEquals(2, f.snapshot().history().lastReachability());
    assertEquals(new BotMovement.ReachAvoidance(2, 7, 1), f.snapshot().reachAvoidance());
  }

  @Test
  void airborneWithoutAReachOnlyRecordsLastOriginAndProducesNoCommand() {
    var f = new Fixture(2);
    f.states.updateMovementFlags(f.handle, 0);
    var before = new BotMovement.History(9, 10, 11, 0, 12, 13, 14, new Vec3f(4, 5, 6));
    f.states.updateHistory(f.handle, before);
    var output = f.service.execute(f.handle, goal(2), POLICY, 2);
    assertEquals(24, output.writtenBytes());
    assertTrue(output.command().isEmpty());
    assertEquals(new BotMovement.History(9, 10, 11, 0, 12, 13, 14, ZERO), f.snapshot().history());
    assertTrue(f.world.traces.isEmpty());
  }

  private static Goal goal(int area) {
    return new Goal(new Vec3f(30, 0, 0), area, ZERO, ZERO, 0, 0, 0, 0);
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
    var history = new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3f(7, 8, 9));
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
        f.handle, new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3f(7, 8, 9)));
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
    assertEquals(SELECTED_AT_ONE, f.snapshot().history());
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
    assertDegraded(
        unavailable.service.execute(unavailable.handle, goal(2), POLICY, 1),
        "travel type barrier_jump");
  }

  @Test
  void independentActionsDoNotInventMovementAndOutputChecksActionBits() {
    var result = new MovementResult(0, 0, 0, 0, 0, 0, 0, new Vec3f(1, 0, 0), ZERO);
    GroundMoveToGoal.ReachTravel actionOnly =
        (input, flags, area, reach) -> new RichOutput(result, Optional.empty(), 16);
    var f = new Fixture(7, Map.of(7, actionOnly));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(16, output.actionFlags());
    assertTrue(output.command().isEmpty());
    assertEquals(new Vec3f(1, 0, 0), output.result().direction());
    assertEquals(52, output.writtenBytes());
    assertEquals(SELECTED_AT_ONE, f.snapshot().history());
    var command = new GroundMoveToGoal.Command(ZERO, 200, 128);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GroundMoveToGoal.Output(
                result,
                52,
                Optional.of(command),
                16,
                Optional.empty(),
                OptionalInt.empty(),
                List.of(),
                Optional.empty()));
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
        f.handle, new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3f(7, 8, 9)));
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
          public Vec3f direction() {
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
    assertEquals(SELECTED_AT_ONE, f.snapshot().history());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    assertEquals(34, f.snapshot().movementFlags());
    assertEquals(1, f.world.traces.size());
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
    var history = new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3f(7, 8, 9));
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
    assertDegraded(
        unavailable.service.execute(unavailable.handle, goal(2), POLICY, 1),
        "travel type teleport");
  }

  @Test
  void registeredJumpPadUsesTenSecondDeadlineAndRetainsItsRawCommand() {
    GroundMoveToGoal.ReachTravel pad =
        (input, flags, area, reach) -> {
          var raw = new Vec3f(-50, -30, 0);
          return new GroundReachMovement.Output(
              new MovementResult(0, 0, 0, 0, 0, 0, 0, raw, ZERO), raw, 400, 0);
        };
    var f = new Fixture(18, Map.of(18, pad));
    var output = f.service.execute(f.handle, goal(2), POLICY, 1);
    assertEquals(18, output.result().travelType());
    assertEquals(new Vec3f(-50, -30, 0), output.command().orElseThrow().direction());
    assertEquals(11, f.snapshot().history().reachDeadline());
    assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 11);
    assertEquals(11, f.snapshot().history().reachDeadline());
    f.service.execute(f.handle, goal(2), POLICY, Math.nextUp(11f));
    assertEquals(Math.nextUp(11f) + 10, f.snapshot().history().reachDeadline());
    assertEquals(0, f.reachCalls);
  }

  @Test
  void registeredGroundExecutorIsNotUsedForAirborneTravel() {
    GroundMoveToGoal.ReachTravel pad =
        (input, flags, area, reach) -> {
          throw new AssertionError("Ground provider called in air");
        };
    var f = new Fixture(18, Map.of(18, pad));
    f.states.updateHistory(f.handle, new BotMovement.History(1, 1, 2, 1, 1, 0, 100, ZERO));
    f.states.updateMovementFlags(f.handle, 0);
    var before = f.snapshot();
    assertDegraded(
        f.service.execute(f.handle, goal(2), POLICY, 1), "airborne travel type jump_pad");
    assertEquals(before.history().withLastOrigin(ZERO), f.snapshot().history());
    var unavailable = new Fixture(18);
    assertDegraded(
        unavailable.service.execute(unavailable.handle, goal(2), POLICY, 1),
        "travel type jump_pad");
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
        f.handle, new BotMovement.History(3, 99, 77, 0, 555, 123, -5, new Vec3f(7, 8, 9)));
    var output = f.service.execute(f.handle, goal(0), TravelPolicy.ofFlags(0), 10);
    assertEquals(new BotMovement.History(3, 1, 77, 2, 555, 123, -5, ZERO), f.snapshot().history());
    assertEquals(3, source[0]);
    assertEquals(18, output.result().travelType());
    assertEquals(200, output.command().orElseThrow().speed());
    assertEquals(new BotMovement.ReachAvoidance(0, 0, 0), f.snapshot().reachAvoidance());
  }

  @Test
  void unsupportedContactDegradesAndFailedAirExecutionLeavesStateUntouched() {
    var f = new Fixture(18, Map.of(), Map.of(), true);
    f.states.updateMovementFlags(f.handle, 0);
    assertDegraded(
        f.service.execute(f.handle, goal(0), POLICY, 1), "airborne travel type jump_pad");
    assertEquals(2, f.snapshot().history().lastReachability());
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
    assertEquals(SELECTED_AT_ONE, f.snapshot().history());
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
        f.handle, new BotMovement.History(3, 99, 77, 1, 555, 123, -5, new Vec3f(7, 8, 9)));
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
                new MovementResult(0, 0, 0, 0, 0, 2, 0, new Vec3f(1, 0, 0), ZERO),
                new Vec3f(1, 0, 0),
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
      assertEquals(SELECTED_AT_ONE, f.snapshot().history());
      assertEquals(new BotMovement.ReachAvoidance(1, 7, 1), f.snapshot().reachAvoidance());
      assertEquals(List.of(new Vec3f(0, 0, -2)), f.world.contentPoints);
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
                  new MovementResult(0, 0, 0, 0, 8, 2, 0, new Vec3f(0, 0, 1), new Vec3f(-90, 0, 0)),
                  Optional.of(new LiquidReachMovement.Move(new Vec3f(0, 0, 1), 10)),
                  0);
            });
    f.world.contents = 32;
    f.states.updateMovementFlags(f.handle, 0);
    f.states.updateHistory(
        f.handle, new BotMovement.History(99, 123, 999, 1, 3, 8, 100, new Vec3f(4, 5, 6)));
    var output = f.service.execute(f.handle, goal(1), POLICY, 2);
    assertEquals(List.of(4), calls);
    assertEquals(0, f.goalCalls);
    assertEquals(52, output.writtenBytes());
    assertEquals(10, output.command().orElseThrow().speed());
    assertEquals(new BotMovement.History(1, 0, 1, 0, 3, 8, 100, ZERO), f.snapshot().history());
  }

  @Test
  void liquidWithoutExecutorsDegradesAndCallbackFailureLeavesStateUnchanged() {
    var bare = new Fixture(8);
    bare.world.contents = 32;
    assertDegraded(bare.service.execute(bare.handle, goal(2), POLICY, 1), "travel type swim");
    assertDegraded(
        bare.service.execute(bare.handle, goal(1), POLICY, 1), "swimming within the goal area");
    BotMovement.Snapshot before;
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
      var history = new BotMovement.History(3, 1, 2, 1, 3, 7, -5, new Vec3f(7, 8, 9));
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
        f.handle, new BotMovement.History(3, 3, 3, 1, 77, 2, 10, new Vec3f(1, 2, 3)));
    f.service.execute(f.handle, goal(2), POLICY, 10);
    assertEquals(new BotMovement.History(1, 1, 2, 1, 77, 2, 10, ZERO), f.snapshot().history());
    assertEquals(BotMovement.ReachAvoidance.EMPTY, f.snapshot().reachAvoidance());
    f.service.execute(f.handle, goal(2), POLICY, 11);
    assertEquals(new BotMovement.History(1, 1, 2, 1, 1, 0, 21, ZERO), f.snapshot().history());
    assertEquals(new BotMovement.ReachAvoidance(1, 17, 1), f.snapshot().reachAvoidance());
  }

  @Test
  void standingBobbingReachRefreshesDeadlineWithoutResettingItsSourceOrAvoidance() {
    GroundMoveToGoal.ReachTravel travel =
        (input, flags, area, reach) ->
            new BobbingPlatformMovement.Output(
                new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), Optional.empty(), 0, flags);
    // Reach 1 rides platform model 1; the cached reach 2 rides another model.
    var f = new Fixture(19, Map.of(19, travel), Map.of(), false, null, Map.of(), Map.of(), 0, 1, 0);
    f.movingPlatform = true;
    f.world.entity = 17;
    f.world.fraction = 1;
    f.states.updateHistory(
        f.handle, new BotMovement.History(3, 3, 3, 2, 77, 2, -2, new Vec3f(1, 2, 3)));
    f.states.recordReachAttempt(f.handle, 2, 20, 1);
    var avoidance = f.snapshot().reachAvoidance();
    var output = f.service.execute(f.handle, goal(2), POLICY, 10);
    assertEquals(new BotMovement.History(1, 1, 2, 1, 77, 2, 15, ZERO), f.snapshot().history());
    assertEquals(avoidance, f.snapshot().reachAvoidance());
    assertEquals(52, output.writtenBytes());
    assertEquals(0, output.result().flags());
  }

  @Test
  void standingPlatformEarlyReturnsPreserveCachedDeadlineOrStoreAReplacement() {
    for (int area : new int[] {0, 1}) {
      for (int previous : new int[] {0, 2}) {
        // Both reaches ride platform model 1: a cached one is kept, otherwise the first is chosen.
        var f = new Fixture(19, Map.of(), Map.of(), false, null, Map.of(), Map.of(), 0, 1, 1);
        f.movingPlatform = true;
        f.world.entity = 17;
        f.area = area;
        var history = new BotMovement.History(3, 3, 3, previous, 77, 2, 7, new Vec3f(1, 2, 3));
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
                  0, 3, 3, previous == 0 ? 1 : previous, 77, 2, deadline, new Vec3f(1, 2, 3)),
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
      var f = new Fixture(19, Map.of(), Map.of(), false, null, Map.of(), Map.of(), 0, 1, 0);
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
      var f = new Fixture(19, Map.of(), Map.of(), false, null, Map.of(), Map.of(), 0, 1, 0);
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
    // No reachability rides platform model 1.
    var f = new Fixture(19, Map.of(), Map.of(), false, null, Map.of(), Map.of(), 0, 0, 0);
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
                Optional.of(new JumpReachMovement.Move(new Vec3f(1, 0, 0), 401)),
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
        f.handle, new BotMovement.History(3, 1, 2, 1, 77, 2, -5, new Vec3f(7, 8, 9)));
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
                Optional.of(new Vec3f((float) Double.MAX_VALUE, 0, 0)),
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
    var clear = new MovementResult(0, 0, 0, 0, 0, 24, 5, ZERO, new Vec3f(9, 8, 7));
    var explicit =
        new Effects(
            clear, Optional.empty(), 16, 0, Optional.of(new Vec3f(-90, 45, 0)), OptionalInt.of(9));
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
      Optional<Vec3f> view,
      OptionalInt weapon)
      implements StatefulReachMovementOutput {}

  private static AasWorld contactMap(int kind, int... faces) {
    var m = map(kind, faces);
    var settings = new ArrayList<>(m.areaSettings());
    settings.set(1, new AasWorld.AreaSettings(128, 1, 6, 1, 0, 2, 1));
    return new AasWorld(
        m.version(),
        m.bspChecksum(),
        m.lumps(),
        m.boundingBoxes(),
        m.vertices(),
        List.of(new AasWorld.Plane(new Vec3f(1, 0, 0), 0, 0)),
        m.edges(),
        m.edgeIndices(),
        m.faces(),
        m.faceIndices(),
        m.areas(),
        settings,
        m.reachabilities(),
        List.of(new AasWorld.Node(0, 0, 0), new AasWorld.Node(0, -1, -1)),
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
      this(kind, extra, air, contact, liquidGoalTravel, statefulGround, statefulAir, 0, 0, 0);
    }

    /**
     * {@code faces} are the face fields of reaches 0..2: a bobbing reach whose low 16 bits are 1
     * rides the fixture's platform model 1, which the standing entity uses when {@code
     * movingPlatform} is set.
     */
    Fixture(
        int kind,
        Map<Integer, GroundMoveToGoal.ReachTravel> extra,
        Map<Integer, GroundMoveToGoal.ReachTravel> air,
        boolean contact,
        GroundMoveToGoal.GoalTravel liquidGoalTravel,
        Map<Integer, GroundMoveToGoal.StatefulReachTravel> statefulGround,
        Map<Integer, GroundMoveToGoal.StatefulReachTravel> statefulAir,
        int... faces) {
      var map = contact ? contactMap(kind, faces) : map(kind, faces);
      states.initialize(handle, new MovementInit(ZERO, ZERO, ZERO, 0, 0, .1f, 2, ZERO, 2));
      var builder =
          GroundMoveToGoal.Config.builder(
                  states,
                  new AasNavigation(map),
                  p -> area,
                  new AasMovementRoutes(map),
                  world,
                  (origin, presence, entity) -> {
                    groundChecks++;
                    assertEquals(
                        states.snapshot(handle).orElseThrow().input().orElseThrow().origin(),
                        origin);
                    return onGround;
                  },
                  (input, flags, source, reach) -> {
                    reachCalls++;
                    executedSource = source;
                    return travel();
                  },
                  (input, flags, source, goal) -> {
                    goalCalls++;
                    return travel();
                  })
              .movers(
                  MoverModels.of(List.of(Map.of("classname", "func_bobbing", "model", "*1")), map),
                  entity -> movingPlatform ? 1 : 0)
              .ground(types(extra))
              .air(types(air));
      if (liquidGoalTravel != null) builder.liquidGoalTravel(liquidGoalTravel);
      statefulGround.forEach((type, travel) -> builder.statefulGround(type(type), travel));
      statefulAir.forEach((type, travel) -> builder.statefulAir(type(type), travel));
      service = builder.build();
    }

    private static TravelType type(int id) {
      return TravelType.fromId(id).orElseThrow();
    }

    private static <T> Map<TravelType, T> types(Map<Integer, T> byNumber) {
      var result = new EnumMap<TravelType, T>(TravelType.class);
      byNumber.forEach((id, value) -> result.put(type(id), value));
      return result;
    }

    GroundReachMovement.Output travel() {
      if (failTravel) throw new IllegalStateException("Authored executor failure");
      var direction = new Vec3f(1, 0, 0);
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
    final List<Vec3f> contentPoints = new ArrayList<>();
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
    public int pointContents(Vec3f point, int mask, int ignoreEntity) {
      assertEquals(-1, mask);
      assertEquals(-1, ignoreEntity);
      contentPoints.add(point);
      return contents;
    }
  }

  private static AasWorld map(int kind, int... faces) {
    var areas = new ArrayList<AasWorld.Area>();
    for (int i = 0; i < 4; i++) areas.add(new AasWorld.Area(i, 0, 0, ZERO, ZERO, ZERO));
    var settings =
        List.of(
            new AasWorld.AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AasWorld.AreaSettings(0, 1, 6, 1, 0, 2, 1),
            new AasWorld.AreaSettings(0, 1, 6, 1, 1, 1, 3),
            new AasWorld.AreaSettings(0, 1, 6, 1, 2, 1, 4));
    var reaches =
        List.of(
            new AasWorld.Reachability(0, face(faces, 0), 0, ZERO, ZERO, 1, 0, 0),
            new AasWorld.Reachability(
                2, face(faces, 1), 0, new Vec3f(10, 0, 0), new Vec3f(20, 0, 0), kind, 1, 0),
            new AasWorld.Reachability(
                3, face(faces, 2), 0, new Vec3f(10, 10, 0), new Vec3f(20, 10, 0), kind, 20, 0),
            new AasWorld.Reachability(2, 0, 0, ZERO, ZERO, 2, 1, 0),
            new AasWorld.Reachability(2, 0, 0, ZERO, ZERO, 2, 1, 0));
    var empty = new AasWorld.Indices(new int[0]);
    return new AasWorld(
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
        List.of(new AasWorld.Portal(0, 0, 0, 0, 0)),
        empty,
        List.of(new AasWorld.Cluster(0, 0, 0, 0), new AasWorld.Cluster(3, 3, 0, 0)));
  }

  private static int face(int[] faces, int reach) {
    return reach < faces.length ? faces[reach] : 0;
  }
}
