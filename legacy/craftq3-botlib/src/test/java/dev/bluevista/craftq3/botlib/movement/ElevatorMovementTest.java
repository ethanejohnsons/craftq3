package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.*;
import org.junit.jupiter.api.Test;

class ElevatorMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final Reachability REACH =
      new Reachability(1, 11, 0, new Vec3(-80, 0, 24), new Vec3(80, 0, 124), 11, 100, 0);

  @Test
  void ridingDepartureUsesStrictAbsoluteVerticalBoundary() {
    var rig = new Rig();
    rig.contact = true;
    var exact = rig.helper.execute(input(new Vec3(0, 0, 92)), 514, 1, REACH);
    assertTrue(exact.movement().isEmpty());
    assertEquals(0, rig.barriers);
    var inside = rig.helper.execute(input(new Vec3(0, 0, Math.nextUp(92f))), 514, 1, REACH);
    assertEquals(new Vec3(1, 0, 0), inside.result().direction());
    assertEquals(400, inside.movement().orElseThrow().speed());
    assertEquals(100, rig.barrierSpeed);
    assertEquals(1, rig.barriers);
    assertTrue(rig.helper.execute(input(new Vec3(0, 0, 200)), 514, 1, REACH).movement().isEmpty());
  }

  @Test
  void ridingCenterDeadbandPreservesPreviousMovement() {
    var rig = new Rig();
    rig.contact = true;
    var exact = rig.helper.execute(input(new Vec3(10, 0, 24)), 514, 1, REACH);
    assertTrue(exact.movement().isEmpty());
    assertEquals(ZERO, exact.result().direction());
    var outside = rig.helper.execute(input(new Vec3(Math.nextUp(10f), 0, 24)), 514, 1, REACH);
    assertEquals(new Vec3(-1, 0, 0), outside.result().direction());
    assertEquals(40, outside.movement().orElseThrow().speed());
    assertEquals(0, rig.barriers);
  }

  @Test
  void arrivalRetainsRawDirectionAndClearsDeadlineEvenWithinSpeedDeadband() {
    var rig = new Rig();
    var output = rig.helper.execute(input(new Vec3(79.5, 0, 124)), 514, 1, REACH);
    assertEquals(new Vec3(.5, 0, 0), output.result().direction());
    assertTrue(output.movement().isEmpty());
    assertTrue(output.clearReachDeadline());
    assertEquals(50, rig.barrierSpeed);
    assertEquals(7, rig.barrierInput.entity());
    var moving = rig.helper.execute(input(new Vec3(79, 0, 124)), 514, 1, REACH);
    assertEquals(new ElevatorMovement.Move(new Vec3(1, 0, 0), 6), moving.movement().orElseThrow());
  }

  @Test
  void swimmingArrivalEmitsRawThreeDimensionalMoveWithoutBarrierQuery() {
    var rig = new Rig();
    var output = rig.helper.execute(input(new Vec3(60, 0, 82)), 6, 1, REACH);
    assertEquals(new Vec3(20, 0, 42), output.result().direction());
    assertEquals(new Vec3(20, 0, 42), output.movement().orElseThrow().direction());
    assertEquals(279.112885f, output.movement().orElseThrow().speed());
    assertEquals(2, output.result().flags());
    assertEquals(6, output.movementFlags());
    assertTrue(output.clearReachDeadline());
    assertEquals(0, rig.barriers);
  }

  @Test
  void platformTopAtStartHeightWaitsAndLowRampRetainsPreviousMove() {
    var rig = new Rig();
    rig.offset = new Vec3(0, 0, 16);
    var waiting = rig.helper.execute(input(new Vec3(-80.5, 0, 24)), 514, 1, REACH);
    assertEquals(1, waiting.result().type());
    assertEquals(4, waiting.result().flags());
    assertTrue(waiting.movement().isEmpty());
    assertFalse(waiting.clearReachDeadline());
    assertEquals(1, rig.obstructions);
    rig.offset = new Vec3(0, 0, 15);
    var boarding = rig.helper.execute(input(new Vec3(-80.5, 0, 24)), 514, 1, REACH);
    assertEquals(0, boarding.result().type());
    assertEquals(360, boarding.movement().orElseThrow().speed());
  }

  @Test
  void boardingAtCenterExplicitlyReplacesMovementWithZero() {
    var rig = new Rig();
    var output = rig.helper.execute(input(new Vec3(0, 0, 24)), 514, 1, REACH);
    assertEquals(new ElevatorMovement.Move(ZERO, 0), output.movement().orElseThrow());
    assertEquals(ZERO, output.result().direction());
    assertEquals(514, output.movementFlags());
    assertFalse(output.clearReachDeadline());
  }

  @Test
  void successfulBarrierOverridesEaButPreservesPlannedRawDirection() {
    var rig = new Rig();
    rig.barrier = true;
    var input = input(new Vec3(60, 0, 82));
    var output = rig.helper.execute(input, 514, 1, REACH);
    assertEquals(new Vec3(20, 0, 42), output.result().direction());
    assertEquals(new ElevatorMovement.Move(new Vec3(1, 0, 0), 50), output.movement().orElseThrow());
    assertEquals(16, output.actionFlags());
    assertEquals(515, output.movementFlags());
    assertSame(input, rig.barrierInput);
    assertTrue(output.clearReachDeadline());
  }

  @Test
  void finishUsesVerticalProximityTieChoosesEndAndAlwaysIssuesSpeed300() {
    var rig = new Rig();
    var center = rig.helper.finish(input(new Vec3(0, 0, 24)), 518, 1, REACH);
    assertEquals(new ElevatorMovement.Move(ZERO, 300), center.movement().orElseThrow());
    assertEquals(new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), center.result());
    assertEquals(518, center.movementFlags());
    assertFalse(center.clearReachDeadline());
    var tie = rig.helper.finish(input(new Vec3(0, 0, 74)), 514, 1, REACH);
    assertTrue(tie.movement().orElseThrow().direction().x() > 0);
    assertTrue(tie.movement().orElseThrow().direction().z() > 0);
    var below = rig.helper.finish(input(new Vec3(0, 0, Math.nextDown(74f))), 514, 1, REACH);
    assertEquals(new Vec3(0, 0, -1), below.movement().orElseThrow().direction());
    assertEquals(0, rig.traces);
    assertEquals(0, rig.barriers);
    assertEquals(3, rig.boundsQueries);
  }

  @Test
  void obstructionFlagsSurviveAndMissingMoverOrOtherTravelFailsExplicitly() {
    var rig = new Rig();
    rig.blocked = true;
    var output = rig.helper.execute(input(new Vec3(-90, 0, 24)), 514, 1, REACH);
    assertEquals(1, output.result().blocked());
    assertEquals(37, output.result().blockEntity());
    assertEquals(1, rig.obstructions);
    rig.missing = true;
    assertThrows(
        UnsupportedOperationException.class, () -> rig.helper.execute(input(ZERO), 0, 1, REACH));
    var wrong = new Reachability(1, 11, 0, REACH.start(), REACH.end(), 19, 1, 0);
    assertThrows(
        UnsupportedOperationException.class, () -> rig.helper.finish(input(ZERO), 0, 1, wrong));
  }

  private static MovementInit input(Vec3 origin) {
    return new MovementInit(origin, ZERO, ZERO, 7, 0, .1f, 2, ZERO, 0);
  }

  private static final class Rig implements TraceWorld {
    boolean contact, barrier, blocked, missing;
    int traces, barriers, obstructions, boundsQueries;
    float barrierSpeed;
    MovementInit barrierInput;
    Vec3 offset = ZERO;
    final MoverQueries movers =
        new MoverQueries(
            this,
            this::bounds,
            entity ->
                entity == 180 && !missing
                    ? Optional.of(new MoverQueries.Entity(4, 11, offset))
                    : Optional.empty(),
            1024);
    final ElevatorMovement helper =
        new ElevatorMovement(
            movers,
            this::bounds,
            new MovementObstruction(this, area -> 0),
            (input, direction, speed) -> {
              barriers++;
              barrierInput = input;
              barrierSpeed = speed;
              return barrier;
            });

    MoverQueries.ModelBounds bounds(int model) {
      assertEquals(11, model);
      boundsQueries++;
      return new MoverQueries.ModelBounds(new Vec3(-32, -32, -8), new Vec3(32, 32, 8));
    }

    public TraceResult trace(TraceRequest request) {
      traces++;
      assertEquals(7, request.ignoreEntity());
      boolean contactQuery = request.contentsMask() == 65537;
      if (!contactQuery) {
        obstructions++;
        assertEquals(33619969, request.contentsMask());
      }
      if (contactQuery ? !contact : !blocked) return TraceResult.clear(request);
      return new TraceResult(
          .5f,
          request.end(),
          false,
          false,
          Optional.of(
              new TraceResult.Hit(
                  new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                  1,
                  0,
                  contactQuery ? 180 : 37,
                  0,
                  -1,
                  -1,
                  -1,
                  "")));
    }

    public int pointContents(Vec3 point, int mask, int ignoredEntity) {
      return 0;
    }
  }
}
