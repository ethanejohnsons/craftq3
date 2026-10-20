package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TeleportReachMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void dryMovementTargetsTheStartHorizontallyAndIgnoresTheDestination() {
    var world = new World();
    var movement = movement(world, 1);
    var first =
        movement.execute(
            input(2, 0), 2, 1, reach(new Vec3(100, 100, 100), new Vec3(-100, -100, -100)));
    assertEquals(new Vec3(.7071068286895752, .7071068286895752, 0), first.direction());
    assertEquals(400, first.speed());
    assertEquals(0, first.actionFlags());
    assertEquals(0, first.result().travelType());
    assertEquals(
        first,
        movement.execute(
            input(2, 0), 514, 1, reach(new Vec3(100, 100, -100), new Vec3(500, 700, 800))));
    assertEquals(2, world.queries.size());
    assertEquals(new Vec3(-15, -15, -6), world.queries.getFirst().mins());
  }

  @Test
  void swimmingUsesEffectiveFlagsAndCombinesItsFlagWithStandingMetadata() {
    var world = new World();
    world.entities = new int[] {1023, 15};
    var result =
        movement(world, 0).execute(input(2, 0), 4, 0, reach(new Vec3(100, 100, 100), ZERO));
    assertEquals(
        new Vec3(.5773502588272095, .5773502588272095, .5773502588272095), result.direction());
    assertEquals(34, result.result().flags());
    assertEquals(1, result.result().blocked());
    assertEquals(15, result.result().blockEntity());
    assertEquals(ZERO, result.result().idealViewAngles());
    assertEquals(
        0,
        movement(new World(), 1)
            .execute(input(2, 4), 0, 1, reach(new Vec3(0, 0, 100), ZERO))
            .result()
            .flags());
  }

  @Test
  void aZeroApproachStillIssuesTheNativeMoveAndCrouchAddsNoAction() {
    var world = new World();
    var result =
        movement(world, 1).execute(input(4, 0), 514, 1, reach(ZERO, new Vec3(1000, 1000, 1000)));
    assertEquals(ZERO, result.direction());
    assertEquals(200, result.speed());
    assertEquals(0, result.actionFlags());
    assertEquals(new Vec3(15, 15, -2), world.queries.getFirst().maxs());
  }

  @Test
  void steepSwimmingApproachesUseTheFullHullWithoutSuppressingBlockedCommands() {
    var world = new World();
    world.entities = new int[] {19};
    var result = movement(world, 1).execute(input(2, 0), 4, 1, reach(new Vec3(0, 0, 100), ZERO));
    assertEquals(new Vec3(0, 0, 1), result.direction());
    assertEquals(400, result.speed());
    assertEquals(1, result.result().blocked());
    assertEquals(19, result.result().blockEntity());
    assertEquals(2, result.result().flags());
    assertEquals(new Vec3(-15, -15, -24), world.queries.getFirst().mins());
    assertEquals(new Vec3(0, 0, 3), world.queries.getFirst().end());
  }

  @Test
  void invalidTravelAndUnrepresentableDirectionsFailBeforeIssuingATrace() {
    var world = new World();
    var movement = movement(world, 1);
    assertThrows(
        UnsupportedOperationException.class,
        () -> movement.execute(input(2, 0), 2, 1, new Reachability(1, 0, 0, ZERO, ZERO, 2, 1, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> movement.execute(input(2, 0), 2, 1, reach(new Vec3(1e30, 0, 0), ZERO)));
    assertTrue(world.queries.isEmpty());
  }

  @Test
  void teleportedEntryRequiresTheCallerToPreserveAnAbsentMovementCommand() {
    var world = new World();
    var movement = movement(world, 1);
    for (int flags : new int[] {32, 34, 36, 38, 546}) {
      assertThrows(
          UnsupportedOperationException.class,
          () -> movement.execute(input(2, 0), flags, 1, reach(new Vec3(100, 100, 100), ZERO)));
    }
    assertTrue(world.queries.isEmpty());
  }

  @Test
  void theThirtyUnitSpeedBoundaryUsesTheSelectedDryOrSwimmingDistance() {
    var movement = movement(new World(), 1);
    assertEquals(
        200,
        movement
            .execute(input(2, 0), 2, 1, reach(new Vec3(Math.nextDown(30f), 0, 0), ZERO))
            .speed());
    assertEquals(
        400, movement.execute(input(2, 0), 514, 1, reach(new Vec3(30, 0, 0), ZERO)).speed());
    assertEquals(
        200, movement.execute(input(2, 4), 0, 1, reach(new Vec3(0, 0, 100), ZERO)).speed());
    assertEquals(
        400, movement.execute(input(2, 0), 4, 1, reach(new Vec3(0, 0, 100), ZERO)).speed());
  }

  private static MovementInit input(int presence, int flags) {
    return new MovementInit(ZERO, new Vec3(200, -100, 50), ZERO, 0, 0, .1f, presence, ZERO, flags);
  }

  private static Reachability reach(Vec3 start, Vec3 end) {
    return new Reachability(1, 0, 0, start, end, 10, 1, 0);
  }

  private static TeleportReachMovement movement(World world, int outgoing) {
    return new TeleportReachMovement(new MovementObstruction(world, a -> outgoing));
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
