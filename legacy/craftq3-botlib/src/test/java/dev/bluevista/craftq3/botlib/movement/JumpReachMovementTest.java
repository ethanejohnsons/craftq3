package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class JumpReachMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void lastSampleInTheStoredReachAreaBecomesTheApproachTarget() {
    var queries = new ArrayList<Vec3>();
    var movement =
        new JumpReachMovement(
            (a, b) -> new Vec3(-64, 0, 99),
            p -> {
              queries.add(p);
              return queries.size() == 3 ? 1 : 0;
            });
    var output = movement.execute(input(-40, 0), 514, 0, reach(), 123, 456);
    assertEquals(new Vec3(1, 0, 0), output.result().direction());
    assertEquals(100, output.movement().orElseThrow().speed());
    assertEquals(456, output.jumpReach());
    assertEquals(0, output.actionFlags());
    assertEquals(
        java.util.List.of(new Vec3(-10, 0, 2), new Vec3(-20, 0, 2), new Vec3(-30, 0, 2)), queries);
  }

  @Test
  void crossingFromStoredReachAreaStopsAtEitherSolidOrAnotherValidArea() {
    for (int other : new int[] {0, 12}) {
      var queries = new ArrayList<Vec3>();
      var movement =
          new JumpReachMovement(
              (a, b) -> new Vec3(-64, 0, 0),
              p -> {
                queries.add(p);
                return queries.size() < 3 ? 7 : other;
              });
      var output = movement.execute(input(-40, 0), 2, 7, reach(), 123, 456);
      assertEquals(3, queries.size());
      assertEquals(new Vec3(1, 0, 0), output.result().direction());
      assertEquals(100, output.movement().orElseThrow().speed());
      assertEquals(456, output.jumpReach());
    }
  }

  @Test
  void runCommitSelectsJumpDelayedJumpAndNoActionUsingNativeDistance() {
    var movement = new JumpReachMovement((a, b) -> new Vec3(-64, 0, 0), p -> 0);
    float[] distances = {23.99f, Math.nextDown(24f), 24, 31.999f, 32, 40};
    int[] actions = {16, 32768, 32768, 32768, 0, 0};
    for (int i = 0; i < distances.length; i++) {
      var output = movement.execute(input(-distances[i], 0), 2, 0, reach(), 123, 456);
      assertEquals(actions[i], output.actionFlags(), "distance " + distances[i]);
      assertEquals(123, output.jumpReach());
      assertEquals(400, output.movement().orElseThrow().speed());
    }
  }

  @Test
  void floatMinusPointEightIsInsideTheMeasuredRunAlignmentBoundary() {
    var movement = new JumpReachMovement((a, b) -> new Vec3(-64, 0, 0), p -> 0);
    var output = movement.execute(input(-.1f, .07485371828079224f), 0, 0, reach(), 123, 456);
    assertEquals(123, output.jumpReach());
    assertEquals(16, output.actionFlags());
    assertEquals(456, movement.execute(input(-40, 20), 0, 0, reach(), 123, 456).jumpReach());
  }

  @Test
  void fewerThanFiveApproachUnitsCommitsEvenWithoutOpposingDirections() {
    var movement = new JumpReachMovement((a, b) -> new Vec3(-64, 0, 0), p -> 1);
    assertEquals(123, movement.execute(input(-4.999f, 0), 2, 0, reach(), 123, 456).jumpReach());
    var boundary = movement.execute(input(-5, 0), 2, 0, reach(), 123, 456);
    assertEquals(456, boundary.jumpReach());
    assertEquals(25, boundary.movement().orElseThrow().speed());
  }

  @Test
  void sampleCoordinatesUseTheOriginalStartAndAreBoundedToEightQueries() {
    var queries = new ArrayList<Vec3>();
    var movement =
        new JumpReachMovement(
            (a, b) -> new Vec3(31.23f, 97.13f, 999),
            p -> {
              queries.add(p);
              return 0;
            });
    var r =
        new Reachability(
            1, 0, 0, new Vec3(1.2345f, 2.3456f, 3.4567f), new Vec3(100, 100, 100), 5, 1, 0);
    movement.execute(input(-100, -100), 2, 0, r, 123, 456);
    assertEquals(8, queries.size());
    assertEquals(
        new Vec3(7.268757343292236f, 21.413578033447266f, 5.456700325012207f), queries.get(1));
    assertEquals(
        new Vec3(25.371530532836914f, 78.61750793457031f, 5.456700325012207f), queries.getLast());
  }

  @Test
  void finishHasNoCommandUntilAJumpReachExistsAndNeverQueriesTheWorld() {
    var movement =
        new JumpReachMovement(
            (a, b) -> {
              fail("finish queried run-start");
              return ZERO;
            },
            p -> {
              fail("finish queried an area");
              return 0;
            });
    var absent = movement.finish(input(20, 30), 4, 1, reach(), 123, 0);
    assertTrue(absent.movement().isEmpty());
    assertEquals(ZERO, absent.result().direction());
    var active = movement.finish(input(100, 0), 0, 1, reach(), 0, 456);
    assertEquals(new JumpReachMovement.Move(ZERO, 400), active.movement().orElseThrow());
    assertEquals(456, active.jumpReach());
    assertEquals(0, active.actionFlags());
  }

  @Test
  void finishSuppressesTheMoveOnlyWhenNearAndBeyondTheArrivalHeading() {
    var movement = new JumpReachMovement((a, b) -> ZERO, p -> 0);
    assertTrue(movement.finish(input(123.999f, 0), 2, 1, reach(), 123, 456).movement().isEmpty());
    assertTrue(movement.finish(input(124, 0), 2, 1, reach(), 123, 456).movement().isPresent());
    assertTrue(
        movement.finish(input(110, 17.320505f), 2, 1, reach(), 123, 456).movement().isEmpty());
    assertTrue(
        movement.finish(input(110, 17.320508f), 2, 1, reach(), 123, 456).movement().isPresent());
  }

  @Test
  void approachPreservesNativeSpeedCancellationAndCap() {
    var movement = new JumpReachMovement((a, b) -> new Vec3(-64, 0, 0), p -> 1);
    assertEquals(
        50,
        movement
            .execute(input(-10.000003f, 0), 2, 0, reach(), 123, 456)
            .movement()
            .orElseThrow()
            .speed());
    assertEquals(
        400,
        movement.execute(input(-200, 0), 2, 0, reach(), 123, 456).movement().orElseThrow().speed());
  }

  @Test
  void invalidTypesAndHistoryFailBeforeProvidersAreCalled() {
    var movement =
        new JumpReachMovement(
            (a, b) -> {
              fail("invalid request queried run-start");
              return ZERO;
            },
            p -> 0);
    assertThrows(
        IllegalArgumentException.class, () -> movement.execute(input(0, 0), 2, 0, reach(), -1, 0));
    var r = new Reachability(1, 0, 0, ZERO, ZERO, 4, 1, 0);
    assertThrows(
        UnsupportedOperationException.class, () -> movement.finish(input(0, 0), 2, 1, r, 0, 0));
  }

  private static MovementInit input(float x, float y) {
    return new MovementInit(new Vec3(x, y, 0), ZERO, ZERO, 0, 0, .1f, 2, ZERO, 0);
  }

  private static Reachability reach() {
    return new Reachability(1, 0, 0, ZERO, new Vec3(100, 0, 0), 5, 1, 0);
  }
}
