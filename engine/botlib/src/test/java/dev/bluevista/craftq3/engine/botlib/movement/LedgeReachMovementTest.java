package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.testing.*;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LedgeReachMovementTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void shortLedgesSlowAtTheVerifiedFortyEightAndSixtyFourBoundaries() {
    var movement = movement(new World(), 1);
    var shortReach = reach(ZERO, new Vec3f(10, 0, -100));
    assertEquals(100, movement.execute(input(new Vec3f(-47.999f, 0, 0)), 2, 1, shortReach).speed());
    assertEquals(336, movement.execute(input(new Vec3f(-48, 0, 0)), 2, 1, shortReach).speed());
    assertEquals(344, movement.execute(input(new Vec3f(-50, 0, 0)), 2, 1, shortReach).speed());
    assertEquals(400, movement.execute(input(new Vec3f(-64, 0, 0)), 2, 1, shortReach).speed());
    assertEquals(
        40, movement.execute(input(ZERO), 2, 1, reach(ZERO, new Vec3f(20, 0, -100))).speed());
    assertEquals(
        100,
        movement
            .execute(input(ZERO), 2, 1, reach(ZERO, new Vec3f(Math.nextDown(20f), 0, -100)))
            .speed());
  }

  @Test
  void departurePhysicsUseGravityAndMaxVelocityWithNativeFailureFallback() {
    var obstruction = new MovementObstruction(new World(), a -> 1);
    var reach = reach(ZERO, new Vec3f(100, 0, -100));
    assertEquals(
        200, new LedgeReachMovement(obstruction).execute(input(ZERO), 514, 1, reach).speed());
    assertEquals(
        141.42135620117188f,
        new LedgeReachMovement(obstruction, 400, 320).execute(input(ZERO), 2, 1, reach).speed());
    assertEquals(
        282.84271240234375f,
        new LedgeReachMovement(obstruction, 1600, 320).execute(input(ZERO), 2, 1, reach).speed());
    assertEquals(
        400,
        new LedgeReachMovement(obstruction, 800, 100).execute(input(ZERO), 2, 1, reach).speed());
    assertEquals(
        400,
        new LedgeReachMovement(obstruction)
            .execute(input(ZERO), 2, 1, reach(ZERO, new Vec3f(100, 0, 100)))
            .speed());
    assertEquals(
        0, new LedgeReachMovement(obstruction).execute(input(ZERO), 514, 1, reach).actionFlags());
  }

  @Test
  void bothObstructionChecksRunAndPreserveEarlierStandingMetadata() {
    var world = new World();
    world.entities = new int[] {1023, 15, 19};
    var result =
        movement(world, 0)
            .execute(input(ZERO), 2, 0, reach(new Vec3f(10, 0, 50), new Vec3f(100, 0, -100)));
    assertEquals(3, world.queries.size());
    assertEquals(new Vec3f(-15, -15, -24), world.queries.get(0).mins());
    assertEquals(new Vec3f(-15, -15, -6), world.queries.get(2).mins());
    assertEquals(1, result.result().blocked());
    assertEquals(19, result.result().blockEntity());
    assertEquals(32, result.result().flags());
    assertEquals(146.96937561035156f, result.speed());
  }

  @Test
  void airborneFinishNudgesOnlyBeyondSixteenAndUsesTheRawObstructionVector() {
    var world = new World();
    var movement = movement(world, 1);
    var result = movement.finish(input(ZERO), 0, 1, reach(ZERO, new Vec3f(16, 0, -100)));
    assertEquals(208, result.speed());
    assertEquals(new Vec3f(48, 0, -300), world.queries.getFirst().end());
    assertEquals(new Vec3f(-15, -15, -24), world.queries.getFirst().mins());
    assertEquals(
        400,
        movement
            .finish(input(ZERO), 0, 1, reach(ZERO, new Vec3f(Math.nextUp(16f), 0, -100)))
            .speed());
  }

  @Test
  void reproducesTheFirstLiveQ3dm17LedgeEntry() {
    var world = new World();
    var in =
        new MovementInit(
            new Vec3f(130.95799255371094f, -993.2659912109375f, 600.1265258789062f),
            new Vec3f(97, -23, 0),
            ZERO,
            1,
            1,
            .1f,
            2,
            ZERO,
            2);
    var result =
        movement(world, 1)
            .execute(
                in,
                2,
                1546,
                new Reachability(
                    1608,
                    0,
                    14597,
                    new Vec3f(198, -833, 600),
                    new Vec3f(198, -831, 352),
                    7,
                    115,
                    0));
    assertEquals(new Vec3f(.38591238856315613f, .9225354790687561f, 0), result.direction());
    assertEquals(400, result.speed());
    assertEquals(2, world.queries.size());
    assertNotEquals(world.queries.getFirst().end().z(), world.queries.getLast().end().z());
    assertEquals(0, result.result().travelType());
  }

  @Test
  void invalidPhysicsAndTravelKindsFailExplicitlyWhileZeroVelocityCapIsSupported() {
    var obstruction = new MovementObstruction(new World(), a -> 1);
    for (float gravity : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY, 100001})
      assertThrows(
          IllegalArgumentException.class, () -> new LedgeReachMovement(obstruction, gravity, 320));
    for (float maxVelocity : new float[] {-1, Float.NaN, Float.POSITIVE_INFINITY, 100001})
      assertThrows(
          IllegalArgumentException.class,
          () -> new LedgeReachMovement(obstruction, 800, maxVelocity));
    assertEquals(
        400,
        new LedgeReachMovement(obstruction, 800, 0)
            .execute(input(ZERO), 2, 1, reach(ZERO, new Vec3f(100, 0, -100)))
            .speed());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LedgeReachMovement(obstruction)
                .execute(
                    input(ZERO),
                    2,
                    1,
                    new Reachability(1, 0, 0, ZERO, new Vec3f(100, 0, 0), 2, 1, 0)));
  }

  private static LedgeReachMovement movement(World world, int outgoing) {
    return new LedgeReachMovement(new MovementObstruction(world, a -> outgoing));
  }

  private static MovementInit input(Vec3f origin) {
    return new MovementInit(origin, ZERO, ZERO, 0, 0, .1f, 2, ZERO, 0);
  }

  private static Reachability reach(Vec3f start, Vec3f end) {
    return new Reachability(1, 0, 0, start, end, 7, 1, 0);
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
                  new TraceResult.Plane(new Vec3f(0, 0, 1), 0),
                  1,
                  0,
                  entities[index],
                  -1,
                  -1,
                  -1,
                  -1,
                  "")));
    }

    public int pointContents(Vec3f p, int mask, int ignore) {
      return 0;
    }
  }
}
