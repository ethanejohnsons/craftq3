package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.testing.TraceRequest;
import dev.bluevista.craftq3.engine.botlib.testing.TraceResult;
import dev.bluevista.craftq3.engine.botlib.testing.TraceWorld;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JumpPadMovementTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void nativeEntryPreservesRawHorizontalMagnitudeAndIgnoresSlowWalk() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 1));
    var origin = new Vec3f(0, 0, 24);
    var result = travel.execute(input(origin, 2), 514, 1, reach(new Vec3f(100, -50, 300)));
    assertEquals(new Vec3f(100, -50, 0), result.direction());
    assertEquals(result.direction(), result.result().direction());
    assertEquals(400, result.speed());
    assertEquals(0, result.actionFlags());
    assertEquals(0, result.result().travelType());
    assertEquals(1, world.requests.size());
    assertEquals(new Vec3f(300, -150, 24), world.requests.getFirst().end());
  }

  @Test
  void zeroHorizontalDisplacementStillPublishesTheNativeMove() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 0));
    var result = travel.execute(input(new Vec3f(0, 0, 24), 4), 0, 0, reach(new Vec3f(0, 0, 400)));
    assertEquals(ZERO, result.direction());
    assertEquals(400, result.speed());
    assertEquals(2, world.requests.size());
    assertEquals(new Vec3f(15, 15, -2), world.requests.getFirst().maxs());
  }

  @Test
  void firstRetailJumpPadApproachMatchesNativeFloatBits() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 1));
    var origin = new Vec3f(374.6842346191406f, 94.78460693359375f, 39.56245040893555f);
    var result =
        travel.execute(input(origin, 2), 2, 2512, reach(new Vec3f(324.000244140625f, 64, 0)));
    assertEquals(new Vec3f(-50.683990478515625f, -30.78460693359375f, 0), result.direction());
    assertEquals(
        new Vec3f(222.63226318359375f, 2.4307861328125f, 39.56245040893555f),
        world.requests.getFirst().end());
  }

  @Test
  void airborneCompletionBorrowsAirControlAndClampsOnlyTheActionSpeed() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 0));
    var input =
        new MovementInit(new Vec3f(0, 0, 24), new Vec3f(50, 10, -300), ZERO, 1, 1, .1f, 2, ZERO, 0);
    var result = travel.finish(input, 0, 0, reach(new Vec3f(100, 0, 24)));
    assertEquals(new Vec3f(.9995280504226685f, .030720459297299385f, 0), result.direction());
    assertEquals(result.direction(), result.result().direction());
    assertEquals(400, result.speed());
    assertEquals(0, result.actionFlags());
    assertEquals(2, world.requests.size());
    assertEquals(new Vec3f(-15, -15, -6), world.requests.getFirst().mins());
  }

  @Test
  void invalidDirectionFailsBeforeAnyBorrowedWorldQuery() {
    var world = new World();
    var travel = new JumpPadMovement(new MovementObstruction(world, area -> 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> travel.execute(input(ZERO, 2), 2, 1, reach(new Vec3f(1_000_001, 0, 0))));
    assertTrue(world.requests.isEmpty());
  }

  private static MovementInit input(Vec3f origin, int presence) {
    return new MovementInit(origin, new Vec3f(20, 30, 919), ZERO, 1, 1, .1f, presence, ZERO, 2);
  }

  private static Reachability reach(Vec3f start) {
    return new Reachability(2, 270, 100, start, new Vec3f(200, 0, 300), 18, 1, 0);
  }

  private static final class World implements TraceWorld {
    final List<TraceRequest> requests = new ArrayList<>();

    public TraceResult trace(TraceRequest request) {
      requests.add(request);
      return TraceResult.clear(request);
    }

    public int pointContents(Vec3f point, int mask, int ignoreEntity) {
      throw new AssertionError("Grounded jump-pad entry does not query contents");
    }
  }
}
