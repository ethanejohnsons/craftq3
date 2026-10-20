package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JumpPadMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void nativeEntryPreservesRawHorizontalMagnitudeAndIgnoresSlowWalk() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 1));
    var origin = new Vec3(0, 0, 24);
    var result = travel.execute(input(origin, 2), 514, 1, reach(new Vec3(100, -50, 300)));
    assertEquals(new Vec3(100, -50, 0), result.direction());
    assertEquals(result.direction(), result.result().direction());
    assertEquals(400, result.speed());
    assertEquals(0, result.actionFlags());
    assertEquals(0, result.result().travelType());
    assertEquals(1, world.requests.size());
    assertEquals(new Vec3(300, -150, 24), world.requests.getFirst().end());
  }

  @Test
  void zeroHorizontalDisplacementStillPublishesTheNativeMove() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 0));
    var result = travel.execute(input(new Vec3(0, 0, 24), 4), 0, 0, reach(new Vec3(0, 0, 400)));
    assertEquals(ZERO, result.direction());
    assertEquals(400, result.speed());
    assertEquals(2, world.requests.size());
    assertEquals(new Vec3(15, 15, -2), world.requests.getFirst().maxs());
  }

  @Test
  void firstRetailJumpPadApproachMatchesNativeFloatBits() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 1));
    var origin = new Vec3(374.6842346191406, 94.78460693359375, 39.56245040893555);
    var result =
        travel.execute(input(origin, 2), 2, 2512, reach(new Vec3(324.000244140625, 64, 0)));
    assertEquals(new Vec3(-50.683990478515625, -30.78460693359375, 0), result.direction());
    assertEquals(
        new Vec3(222.63226318359375, 2.4307861328125, 39.56245040893555),
        world.requests.getFirst().end());
  }

  @Test
  void airborneCompletionBorrowsAirControlAndClampsOnlyTheActionSpeed() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 0));
    var input =
        new MovementInit(new Vec3(0, 0, 24), new Vec3(50, 10, -300), ZERO, 1, 1, .1f, 2, ZERO, 0);
    var result = travel.finish(input, 0, 0, reach(new Vec3(100, 0, 24)));
    assertEquals(new Vec3(.9995280504226685, .030720459297299385, 0), result.direction());
    assertEquals(result.direction(), result.result().direction());
    assertEquals(400, result.speed());
    assertEquals(0, result.actionFlags());
    assertEquals(2, world.requests.size());
    assertEquals(new Vec3(-15, -15, -6), world.requests.getFirst().mins());
  }

  @Test
  void invalidDirectionFailsBeforeAnyBorrowedWorldQuery() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> travel.execute(input(ZERO, 2), 2, 1, reach(new Vec3(1_000_001, 0, 0))));
    assertTrue(world.requests.isEmpty());
  }

  private static MovementInit input(Vec3 origin, int presence) {
    return new MovementInit(origin, new Vec3(20, 30, 919), ZERO, 1, 1, .1f, presence, ZERO, 2);
  }

  private static Reachability reach(Vec3 start) {
    return new Reachability(2, 270, 100, start, new Vec3(200, 0, 300), 18, 1, 0);
  }

  private static final class World implements TraceWorld {
    final List<TraceRequest> requests = new ArrayList<>();

    public TraceResult trace(TraceRequest request) {
      requests.add(request);
      return TraceResult.clear(request);
    }

    public int pointContents(Vec3 point, int mask, int ignoreEntity) {
      throw new AssertionError("Grounded jump-pad entry does not query contents");
    }
  }
}
