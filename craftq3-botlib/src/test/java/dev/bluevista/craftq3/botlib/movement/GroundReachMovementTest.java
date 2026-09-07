package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GroundReachMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0), ORIGIN = new Vec3(0, 0, 24);

  @Test
  void walkingTurnsAtTenHorizontalUnitsAfterCheckingTheApproach() {
    var world = new World();
    var travel = new GroundReachMovement(world, ignored -> 6, ignored -> 0, (p, d, e) -> 0);
    var result = travel.execute(input(2), 2, 0, reach(2, 9, 100));
    assertEquals(new Vec3(0, 1, 0), result.direction());
    assertEquals(new Vec3(3, 0, 24), world.requests.getFirst().end());
    assertEquals(400, result.speed());
    assertEquals(0, result.result().travelType());
    assertEquals(new Vec3(1, 0, 0), travel.execute(input(2), 2, 0, reach(2, 10, 100)).direction());
    // Vertical separation does not affect horizontal target switching.
    assertEquals(
        new Vec3(0, 1, 0),
        travel
            .execute(
                input(2),
                2,
                0,
                new Reachability(1, 0, 0, new Vec3(9, 0, 1000), new Vec3(0, 100, -1000), 2, 1, 0))
            .direction());
  }

  @Test
  void crouchingUsesEndpointAndIgnoresGapAndSlowWalk() {
    var world = new World();
    var travel =
        new GroundReachMovement(
            world,
            ignored -> {
              throw new AssertionError();
            },
            ignored -> 0,
            (p, d, e) -> {
              throw new AssertionError();
            });
    var result = travel.execute(input(4), 514, 0, reach(3, 100, 100));
    assertEquals(new Vec3(0, 1, 0), result.direction());
    assertEquals(400, result.speed());
    assertEquals(ActionFlags.CROUCH, result.actionFlags());
    assertEquals(new Vec3(-15, -15, -6), world.requests.getFirst().mins());
    assertEquals(new Vec3(15, 15, -2), world.requests.getFirst().maxs());
    assertEquals(new Vec3(15, 15, 8), world.requests.getLast().maxs());
  }

  @Test
  void approachingACrouchAreaUsesExclusiveTenAndTwentyBoundaries() {
    var travel = new GroundReachMovement(new World(), ignored -> 4, ignored -> 0, (p, d, e) -> 0);
    assertEquals(0, travel.execute(input(2), 2, 0, reach(2, 9.999, 100)).actionFlags());
    assertEquals(
        ActionFlags.CROUCH, travel.execute(input(2), 2, 0, reach(2, 10, 100)).actionFlags());
    assertEquals(
        ActionFlags.CROUCH, travel.execute(input(2), 2, 0, reach(2, 19.999, 100)).actionFlags());
    assertEquals(0, travel.execute(input(2), 2, 0, reach(2, 20, 100)).actionFlags());
    assertEquals(ActionFlags.CROUCH, travel.execute(input(2), 2, 0, reach(2, 0, 5)).actionFlags());
  }

  @Test
  void gapSpeedIsBoundedThenEffectiveSlowWalkHalvesIt() {
    for (float gap : new float[] {-1, 0, 1, 10, 100, 200}) {
      var travel =
          new GroundReachMovement(new World(), ignored -> 6, ignored -> 0, (p, d, e) -> gap);
      float expected = gap <= 0 ? 400 : Math.min(400, 40 + gap * 2);
      assertEquals(expected, travel.execute(input(2), 2, 0, reach(2, 100, 100)).speed());
      var slow = travel.execute(input(2), 514, 0, reach(2, 100, 100));
      assertEquals(expected / 2, slow.speed());
      assertEquals(ActionFlags.WALK, slow.actionFlags());
    }
  }

  @Test
  void obstructionRecordsEntitiesAndStandingOnThemWithoutSuppressingCommands() {
    var world = new World();
    world.hits = List.of(hit(12, false));
    var travel = new GroundReachMovement(world, ignored -> 6, ignored -> 0, (p, d, e) -> 0);
    var result = travel.execute(input(2), 2, 0, reach(2, 100, 100));
    assertEquals(1, result.result().blocked());
    assertEquals(12, result.result().blockEntity());
    assertEquals(0, result.result().flags());
    assertEquals(400, result.speed());
    assertEquals(1, world.requests.size());
    assertEquals(33619969, world.requests.getFirst().contentsMask());
    world.requests.clear();
    world.hits = List.of(hit(1022, false), hit(13, false));
    result = travel.execute(input(2), 2, 0, reach(2, 100, 100));
    assertEquals(13, result.result().blockEntity());
    assertEquals(32, result.result().flags());
    assertEquals(new Vec3(0, 0, 21), world.requests.getLast().end());
    assertEquals(65537, world.requests.getLast().contentsMask());
    world.requests.clear();
    world.hits = List.of(hit(12, true), hit(13, true));
    assertEquals(0, travel.execute(input(2), 2, 0, reach(2, 100, 100)).result().blocked());
  }

  @Test
  void aRoutingSourceAreaSkipsTheDownwardEntityQuery() {
    var world = new World();
    var travel =
        new GroundReachMovement(world, ignored -> 6, area -> area == 126 ? 1 : 0, (p, d, e) -> 0);
    travel.execute(input(2), 2, 126, reach(2, 100, 100));
    assertEquals(1, world.requests.size());
    world.requests.clear();
    travel.execute(input(2), 2, 0, reach(2, 100, 100));
    assertEquals(2, world.requests.size());
  }

  @Test
  void originalFirstLiveWalkDirectionIsReproduced() {
    var travel = new GroundReachMovement(new World(), ignored -> 6, ignored -> 0, (p, d, e) -> 0);
    var input = new MovementInit(new Vec3(1052, 1432, 24.125), ZERO, ZERO, 1, 1, .1f, 2, ZERO, 0);
    var reach =
        new Reachability(
            119,
            0,
            1232,
            new Vec3(960, 1649.0999755859375, 24),
            new Vec3(960, 1654, 24.125),
            2,
            1,
            0);
    var result = travel.execute(input, 2, 0, reach);
    assertEquals(new Vec3(-.3901795446872711, .9207387566566467, 0), result.direction());
    assertEquals(400, result.speed());
  }

  @Test
  void nativeNormalizationReturnControlsTheTenUnitBoundary() {
    var travel = new GroundReachMovement(new World(), ignored -> 6, ignored -> 1, (p, d, e) -> 0);
    var reach =
        new Reachability(
            1,
            0,
            0,
            new Vec3(9.8302001953125, -1.83498215675354, 24),
            new Vec3(0, 100, 24),
            2,
            1,
            0);
    assertEquals(new Vec3(0, 1, 0), travel.execute(input(2), 2, 1, reach).direction());
  }

  @Test
  void dryGoalAreaMovementAlwaysEmitsMoveAndIgnoresSlowWalkAndCrouchActions() {
    var travel =
        new GroundReachMovement(
            new World(),
            ignored -> {
              throw new AssertionError();
            },
            ignored -> 1,
            (p, d, e) -> {
              throw new AssertionError();
            });
    var result = travel.moveInGoalArea(input(4), 514, 1, new Vec3(3, 4, 500));
    assertEquals(new Vec3((float) .6, (float) .8, 0), result.direction());
    assertEquals(20, result.speed());
    assertEquals(0, result.actionFlags());
    assertEquals(2, result.result().travelType());
    result = travel.moveInGoalArea(input(2), 2, 1, ORIGIN);
    assertEquals(ZERO, result.direction());
    assertEquals(0, result.speed());
  }

  @Test
  void goalAreaSpeedKeepsNativeRoundingAndTheTenSpeedDeadband() {
    var travel = new GroundReachMovement(new World(), ignored -> 6, ignored -> 1, (p, d, e) -> 0);
    assertEquals(0, travel.moveInGoalArea(input(2), 2, 1, new Vec3(2.499, 0, 24)).speed());
    assertEquals(10, travel.moveInGoalArea(input(2), 2, 1, new Vec3(2.5, 0, 24)).speed());
    assertEquals(
        10.003997802734375f, travel.moveInGoalArea(input(2), 2, 1, new Vec3(2.501, 0, 24)).speed());
    assertEquals(400, travel.moveInGoalArea(input(2), 2, 1, new Vec3(100, 0, 24)).speed());
    assertEquals(400, travel.moveInGoalArea(input(2), 2, 1, new Vec3(1000, 0, 24)).speed());
  }

  @Test
  void goalAreaMovementRetainsObstructionMetadataAndRejectsSwimming() {
    var world = new World();
    world.hits = List.of(hit(1022, false), hit(13, false));
    var travel = new GroundReachMovement(world, ignored -> 6, ignored -> 0, (p, d, e) -> 0);
    var result = travel.moveInGoalArea(input(2), 2, 0, new Vec3(100, 0, 24));
    assertEquals(1, result.result().blocked());
    assertEquals(13, result.result().blockEntity());
    assertEquals(32, result.result().flags());
    assertEquals(2, result.result().travelType());
    world.requests.clear();
    assertThrows(
        UnsupportedOperationException.class, () -> travel.moveInGoalArea(input(2), 4, 0, ORIGIN));
    assertTrue(world.requests.isEmpty());
  }

  @Test
  void unsupportedTravelAndNonfiniteGapFailExplicitly() {
    var travel =
        new GroundReachMovement(new World(), ignored -> 6, ignored -> 0, (p, d, e) -> Float.NaN);
    assertThrows(
        UnsupportedOperationException.class,
        () -> travel.execute(input(2), 2, 0, reach(5, 100, 100)));
    assertThrows(
        IllegalArgumentException.class, () -> travel.execute(input(2), 2, 0, reach(2, 100, 100)));
  }

  private static MovementInit input(int presence) {
    return new MovementInit(ORIGIN, ZERO, ZERO, 1, 1, .1f, presence, ZERO, 0);
  }

  private static Reachability reach(int type, double start, double end) {
    return new Reachability(1, 0, 0, new Vec3(start, 0, 24), new Vec3(0, end, 24), type, 1, 0);
  }

  private static TraceResult hit(int entity, boolean startSolid) {
    return new TraceResult(
        .5,
        ORIGIN,
        startSolid,
        false,
        Optional.of(new TraceResult.Hit(TraceResult.Plane.NONE, 1, 0, entity, -1, -1, -1, -1, "")));
  }

  private static final class World implements TraceWorld {
    final List<TraceRequest> requests = new ArrayList<>();
    List<TraceResult> hits = List.of();

    public TraceResult trace(TraceRequest request) {
      int index = requests.size();
      requests.add(request);
      return index < hits.size() ? hits.get(index) : TraceResult.clear(request);
    }

    public int pointContents(Vec3 point, int mask, int ignore) {
      return 0;
    }
  }
}
