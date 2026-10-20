package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BarrierReachMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void jumpingInsideNineUnitsPreservesTheAbsenceOfAMove() {
    var movement = movement(new World(), 1);
    for (float distance : new float[] {0, 1, Math.nextDown(9f)}) {
      var output =
          movement.execute(input(ZERO, ZERO, 2), 514, 1, reach(new Vec3(distance, 0, 100), ZERO));
      assertTrue(output.movement().isEmpty());
      assertEquals(16, output.actionFlags());
    }
    var boundary = movement.execute(input(ZERO, ZERO, 2), 514, 1, reach(new Vec3(9, 0, 100), ZERO));
    assertEquals(0, boundary.actionFlags());
    assertEquals(
        new BarrierReachMovement.Move(new Vec3(1, 0, 0), 54), boundary.movement().orElseThrow());
  }

  @Test
  void approachPreservesNativeSpeedCancellationAndTheSixtyUnitCap() {
    var movement = movement(new World(), 1);
    var input = input(ZERO, new Vec3(500, 500, 500), 2);
    var result = movement.execute(input, 4, 1, reach(new Vec3(9.999f, 0, 100), ZERO));
    assertEquals(59.9940185546875f, result.movement().orElseThrow().speed());
    assertEquals(0, result.result().flags());
    assertEquals(0, result.result().travelType());
    assertEquals(
        145.49227905273438f,
        movement
            .execute(
                input(new Vec3(604.3551f, -439.11954f, 174.44884f), ZERO, 2),
                2,
                1,
                reach(new Vec3(624.9f, -452, 176), ZERO))
            .movement()
            .orElseThrow()
            .speed());
    for (float distance : new float[] {60, 100, 1000}) {
      assertEquals(
          360,
          movement
              .execute(input, 1023, 1, reach(new Vec3(distance, 0, -100), ZERO))
              .movement()
              .orElseThrow()
              .speed());
    }
  }

  @Test
  void blockedJumpStillRetainsItsJumpActionAndResultDirection() {
    var world = new World();
    world.entities = new int[] {19};
    var output =
        movement(world, 1).execute(input(ZERO, ZERO, 4), 2, 1, reach(new Vec3(1, 0, 0), ZERO));
    assertTrue(output.movement().isEmpty());
    assertEquals(16, output.actionFlags());
    assertEquals(new Vec3(1, 0, 0), output.result().direction());
    assertEquals(1, output.result().blocked());
    assertEquals(19, output.result().blockEntity());
    assertEquals(new Vec3(15, 15, -2), world.queries.getFirst().maxs());
  }

  @Test
  void standingMetadataUsesTheSharedDownwardCheckAfterAClearApproach() {
    var world = new World();
    world.entities = new int[] {1023, 15};
    var output =
        movement(world, 0).execute(input(ZERO, ZERO, 2), 0, 0, reach(new Vec3(100, 0, 0), ZERO));
    assertEquals(32, output.result().flags());
    assertEquals(15, output.result().blockEntity());
    assertEquals(360, output.movement().orElseThrow().speed());
    assertEquals(2, world.queries.size());
    assertEquals(new Vec3(0, 0, -3), world.queries.getLast().end());
  }

  @Test
  void finishPreservesEarlierActionsUntilVerticalVelocityDropsBelow250() {
    var world = new World();
    var movement = movement(world, 1);
    var reach = reach(ZERO, new Vec3(100, 100, 100));
    for (float velocity : new float[] {250, Math.nextUp(250f), 400}) {
      var output = movement.finish(input(ZERO, new Vec3(0, 0, velocity), 2), 2, 1, reach);
      assertTrue(output.movement().isEmpty());
      assertEquals(0, output.actionFlags());
      assertEquals(new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), output.result());
    }
    assertTrue(world.queries.isEmpty());
    assertTrue(
        movement
            .finish(input(ZERO, new Vec3(0, 0, Math.nextDown(250f)), 2), 2, 1, reach)
            .movement()
            .isPresent());
    assertEquals(1, world.queries.size());
  }

  @Test
  void finishUsesTheRawHorizontalEndpointDeltaAndStillMovesAtTheEndpoint() {
    var world = new World();
    var movement = movement(world, 1);
    var input = input(new Vec3(10, 20, 30), new Vec3(400, -400, -400), 2);
    var output =
        movement.finish(input, 1023, 1, reach(new Vec3(900, 800, 700), new Vec3(100, 70, 100)));
    assertEquals(
        new BarrierReachMovement.Move(new Vec3(90, 50, 0), 400), output.movement().orElseThrow());
    assertEquals(new Vec3(280, 170, 30), world.queries.getFirst().end());
    assertEquals(
        new BarrierReachMovement.Move(ZERO, 400),
        movement.finish(input, 2, 1, reach(ZERO, input.origin())).movement().orElseThrow());
  }

  @Test
  void invalidKindsAndOverflowingApproachesFailBeforeATrace() {
    var world = new World();
    var movement = movement(world, 1);
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            movement.execute(
                input(ZERO, ZERO, 2), 2, 1, new Reachability(1, 0, 0, ZERO, ZERO, 2, 1, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> movement.execute(input(ZERO, ZERO, 2), 2, 1, reach(new Vec3(1e30, 0, 0), ZERO)));
    assertTrue(world.queries.isEmpty());
  }

  private static MovementInit input(Vec3 origin, Vec3 velocity, int presence) {
    return new MovementInit(origin, velocity, ZERO, 0, 0, .1f, presence, ZERO, 0);
  }

  private static Reachability reach(Vec3 start, Vec3 end) {
    return new Reachability(1, 0, 0, start, end, 4, 1, 0);
  }

  private static BarrierReachMovement movement(World world, int outgoing) {
    return new BarrierReachMovement(new MovementObstruction(world, a -> outgoing));
  }

  private static final class World implements TraceWorld {
    final ArrayList<TraceRequest> queries = new ArrayList<>();
    int[] entities = {};

    public TraceResult trace(TraceRequest request) {
      int index = queries.size();
      queries.add(request);
      if (index >= entities.length || entities[index] == 1023) return TraceResult.clear(request);
      return new TraceResult(
          .5,
          request.start(),
          false,
          false,
          Optional.of(
              new TraceResult.Hit(
                  new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                  1,
                  0,
                  entities[index],
                  -1,
                  -1,
                  -1,
                  -1,
                  "")));
    }

    public int pointContents(Vec3 p, int mask, int ignore) {
      return 0;
    }
  }
}
