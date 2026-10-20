package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BobbingPlatformMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final Reachability REACH =
      new Reachability(1, 11, 100, new Vec3(-80, 0, 24), new Vec3(80, 0, 124), 19, 1, 0);

  @Test
  void waitingPreservesAnEarlierMoveAndSkipsTheBottomTrace() {
    var f = new Fixture();
    f.phase = 50;
    var result = f.helper.execute(input(-80, 0, 24), 514, 0, REACH);
    assertEquals(2, result.result().type());
    assertEquals(4, result.result().flags());
    assertTrue(result.movement().isEmpty());
    assertEquals(514, result.movementFlags());
    assertEquals(1, f.requests.size());
    assertEquals(new Vec3(15, 15, 22), f.requests.getFirst().maxs());
    assertEquals(1, f.barriers.size());
    assertEquals(50, f.barriers.getFirst().speed());
  }

  @Test
  void waitingRetainsEarlierMovementAtFiveButStillChecksForBarrierJump() {
    var f = new Fixture();
    f.phase = 50;
    var reach = new Reachability(1, 11, 100, new Vec3(0, 0, 24), REACH.end(), 19, 1, 0);
    for (float distance : new float[] {.5f, .8f, 5f / 6}) {
      var output = f.helper.execute(input(-distance, 0, 24), 2, 1, reach);
      assertTrue(output.movement().isEmpty());
      assertEquals(new Vec3(1, 0, 0), output.result().direction());
      assertEquals(2, output.result().type());
      assertEquals(4, output.result().flags());
    }
    assertEquals(
        5.040008544921875f,
        f.helper.execute(input(-.84f, 0, 24), 2, 1, reach).movement().orElseThrow().speed());
    f.barrier = true;
    var jumping = f.helper.execute(input(-.5f, 0, 24), 2, 1, reach);
    assertEquals(16, jumping.actionFlags());
    assertEquals(50, jumping.movement().orElseThrow().speed());
  }

  @Test
  void boardingAcceptsSixteenUnitsAndSwitchesAfterPassingTheStart() {
    var f = new Fixture();
    f.phase = 16;
    var result = f.helper.execute(input(-80, 0, 24), 2, 0, REACH);
    assertEquals(0, result.result().type());
    assertEquals(new Vec3(1, 0, 0), result.result().direction());
    assertEquals(360, result.movement().orElseThrow().speed());
    f.phase = Math.nextUp(16f);
    assertEquals(2, f.helper.execute(input(-80, 0, 24), 2, 0, REACH).result().type());
    f.phase = 0;
    var passed = new Reachability(1, 11, 100, new Vec3(-110, 0, 24), REACH.end(), 19, 1, 0);
    assertEquals(
        new Vec3(1, 0, 0), f.helper.execute(input(-80, 0, 24), 2, 0, passed).result().direction());
  }

  @Test
  void swimmingApproachSetsResultViewFlagWhilePreservingEarlierElementaryMove() {
    var f = new Fixture();
    f.phase = 50;
    var output = f.helper.execute(input(-80, 20, 40), 4, 1, REACH);
    assertEquals(
        new Vec3(0, -.7808688282966614f, -.6246950626373291f), output.result().direction());
    assertEquals(6, output.result().flags());
    assertTrue(output.movement().isEmpty());
    assertTrue(f.barriers.isEmpty());
    assertTrue(output.view().isEmpty());
    assertTrue(output.weapon().isEmpty());
  }

  @Test
  void centeringAndDepartureUseDistinctDeadbands() {
    var f = new Fixture();
    f.on = true;
    f.phase = 50;
    assertTrue(f.helper.execute(input(10, 0, 24), 2, 1, REACH).movement().isEmpty());
    var centered = f.helper.execute(input(5, 10, 24), 2, 1, REACH);
    assertEquals(44.72137451171875f, centered.movement().orElseThrow().speed());
    assertTrue(f.barriers.isEmpty());
    f.phase = 76;
    assertTrue(f.helper.execute(input(0, 0, 24), 2, 1, REACH).movement().isEmpty());
    f.phase = Math.nextUp(76f);
    assertEquals(
        400, f.helper.execute(input(0, 0, 24), 2, 1, REACH).movement().orElseThrow().speed());
    assertEquals(100, f.barriers.getLast().speed());
  }

  @Test
  void nearEndKeepsTheRawThreeDimensionalDeltaAndOmitsObstruction() {
    var f = new Fixture();
    f.phase = 50;
    var output = f.helper.execute(input(60, 0, 82), 2, 1, REACH);
    assertEquals(new Vec3(20, 0, 42), output.result().direction());
    assertEquals(279.1128845214844f, output.movement().orElseThrow().speed());
    assertTrue(f.requests.isEmpty());
    assertEquals(50, f.barriers.getFirst().speed());
    f.barriers.clear();
    assertEquals(2, f.helper.execute(input(60, 0, 82), 4, 1, REACH).result().flags());
    assertTrue(f.barriers.isEmpty());
  }

  @Test
  void arrivalDeadbandRetainsMoveWhileStillClearingDeadlineAndReportingRawDirection() {
    var f = new Fixture();
    f.phase = 50;
    var reach = new Reachability(1, 11, 100, REACH.start(), new Vec3(0, 0, 124), 19, 1, 0);
    for (int flags : new int[] {2, 4}) {
      var output = f.helper.execute(input(-5f / 6, 0, 124), flags, 1, reach);
      assertTrue(output.movement().isEmpty());
      assertTrue(output.clearReachDeadline());
      assertEquals(new Vec3(5f / 6, 0, 0), output.result().direction());
      assertEquals(flags == 4 ? 2 : 0, output.result().flags());
      assertEquals(
          5.040008544921875f,
          f.helper.execute(input(-.84f, 0, 124), flags, 1, reach).movement().orElseThrow().speed());
    }
    assertEquals(4, f.requests.size());
    assertTrue(f.requests.stream().allMatch(r -> r.maxs().equals(new Vec3(16, 16, 8))));
  }

  @Test
  void successfulBarrierOverridesTheMoveButPreservesThePlannedResultDirection() {
    var f = new Fixture();
    f.phase = 50;
    f.barrier = true;
    var output = f.helper.execute(input(80, 0, 100), 514, 1, REACH);
    assertEquals(new Vec3(0, 0, 24), output.result().direction());
    assertEquals(new BobbingPlatformMovement.Move(ZERO, 50), output.movement().orElseThrow());
    assertEquals(16, output.actionFlags());
    assertEquals(515, output.movementFlags());
    f.on = true;
    f.phase = 100;
    assertEquals(
        100, f.helper.execute(input(0, 0, 24), 514, 1, REACH).movement().orElseThrow().speed());
  }

  @Test
  void finishNearPlatformEndUsesRawMetadataDeltaAndIndependentEndpointSpeed() {
    var f = new Fixture();
    f.phase = 92;
    var output = f.helper.finish(input(60, 0, 82), 2, 1, REACH);
    assertEquals(new Vec3(0, 0, -8), output.result().direction());
    assertEquals(
        new BobbingPlatformMovement.Move(new Vec3(0, 0, -8), 120), output.movement().orElseThrow());
    assertTrue(f.helper.finish(input(80, 0, 124), 2, 1, REACH).movement().isEmpty());
    assertTrue(f.helper.finish(input(79.5, 0, 124), 2, 1, REACH).movement().isEmpty());
    assertEquals(
        6, f.helper.finish(input(79, 0, 124), 2, 1, REACH).movement().orElseThrow().speed());
    assertTrue(f.requests.isEmpty());
    assertTrue(f.barriers.isEmpty());
  }

  @Test
  void finishAtSixteenUnitsUsesFiveUnitCenteringAndSwimmingStartHeight() {
    var f = new Fixture();
    f.phase = 84;
    assertTrue(f.helper.finish(input(5, 0, 24), 2, 1, REACH).movement().isEmpty());
    assertEquals(
        40, f.helper.finish(input(10, 0, 24), 2, 1, REACH).movement().orElseThrow().speed());
    var swim = f.helper.finish(input(0, 0, 124), 4, 1, REACH);
    assertEquals(new Vec3(0, 0, -1), swim.result().direction());
    assertEquals(400, swim.movement().orElseThrow().speed());
    assertEquals(0, swim.result().flags());
  }

  @Test
  void obstructionMetadataIsRetainedWhenBoardingStillRequestsMovement() {
    var f = new Fixture();
    f.block = true;
    var output = f.helper.execute(input(-80, 0, 24), 2, 0, REACH);
    assertEquals(1, output.result().blocked());
    assertEquals(17, output.result().blockEntity());
    assertEquals(360, output.movement().orElseThrow().speed());
    assertEquals(1, f.requests.size());
  }

  @Test
  void boardingAtExactCenterWritesZeroMoveAndArrivalExplicitlyClearsDeadline() {
    var f = new Fixture();
    var boarding = f.helper.execute(input(0, 0, 24), 2, 1, REACH);
    assertEquals(new BobbingPlatformMovement.Move(ZERO, 0), boarding.movement().orElseThrow());
    assertFalse(boarding.clearReachDeadline());
    f.phase = 50;
    var arrived = f.helper.execute(input(80, 0, 124), 2, 1, REACH);
    assertTrue(arrived.clearReachDeadline());
    assertTrue(arrived.movement().isEmpty());
    f.barrier = true;
    assertTrue(f.helper.execute(input(60, 0, 82), 2, 1, REACH).clearReachDeadline());
    assertTrue(f.helper.execute(input(60, 0, 82), 4, 1, REACH).clearReachDeadline());
    f.phase = 100;
    assertFalse(f.helper.finish(input(60, 0, 82), 2, 1, REACH).clearReachDeadline());
  }

  @Test
  void readyBoardingAndWaitingUseDifferentFloatCancellationEvenBeforeTheTargetSwitch() {
    var f = new Fixture();
    var reach =
        new Reachability(
            1,
            11,
            100,
            new Vec3(-316.49994f, -3.5000005f, -352),
            new Vec3(41, -15.444445f, 413),
            19,
            1,
            0);
    var input = input(-336.28336f, -10.918717f, -353.875f);
    assertEquals(
        126.77203369140625f, f.helper.execute(input, 2, 1, reach).movement().orElseThrow().speed());
    f.phase = 50;
    assertEquals(
        126.77204895019531f, f.helper.execute(input, 2, 1, reach).movement().orElseThrow().speed());
  }

  @Test
  void missingMoversAndOtherTravelTypesFailBeforeCommands() {
    var f = new Fixture();
    f.present = false;
    assertThrows(
        UnsupportedOperationException.class, () -> f.helper.execute(input(0, 0, 0), 2, 1, REACH));
    var wrong = new Reachability(1, 11, 100, ZERO, ZERO, 11, 1, 0);
    assertThrows(
        UnsupportedOperationException.class, () -> f.helper.finish(input(0, 0, 0), 2, 1, wrong));
    assertTrue(f.requests.isEmpty());
    assertTrue(f.barriers.isEmpty());
  }

  private static MovementInit input(double x, double y, double z) {
    return new MovementInit(new Vec3(x, y, z), ZERO, ZERO, 0, 0, .1f, 2, ZERO, 0);
  }

  private static final class Fixture implements TraceWorld {
    float phase;
    boolean on, block, barrier, present = true;
    final ArrayList<TraceRequest> requests = new ArrayList<>();
    final ArrayList<BobbingPlatformMovement.Move> barriers = new ArrayList<>();
    final MoverQueries movers =
        new MoverQueries(
            this,
            m -> new MoverQueries.ModelBounds(new Vec3(-32, -32, -8), new Vec3(32, 32, 8)),
            e ->
                e == 180 && present
                    ? Optional.of(new MoverQueries.Entity(4, 11, new Vec3(0, 0, phase)))
                    : Optional.empty(),
            1024);
    final BobbingPlatformMovement helper =
        new BobbingPlatformMovement(
            movers,
            new MovementObstruction(this, a -> 0),
            (input, direction, speed) -> {
              barriers.add(new BobbingPlatformMovement.Move(direction, speed));
              return barrier;
            });

    public TraceResult trace(TraceRequest r) {
      requests.add(r);
      if ((r.contentsMask() == 65537 && on) || (r.contentsMask() != 65537 && block))
        return new TraceResult(
            .5,
            r.end(),
            false,
            false,
            Optional.of(
                new TraceResult.Hit(
                    TraceResult.Plane.NONE, 1, 0, on ? 180 : 17, 0, -1, -1, -1, "")));
      return TraceResult.clear(r);
    }

    public int pointContents(Vec3 p, int mask, int entity) {
      return 0;
    }
  }
}
