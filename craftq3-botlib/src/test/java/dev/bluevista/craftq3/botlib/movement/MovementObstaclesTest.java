package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MovementObstaclesTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void barrierUsesNormalizedHorizontalHalfThinkDistanceAndInclusiveStepHeight() {
    var world = new World(1, .5f, .4375f);
    var input = input(new Vec3(1, 2, 3), .05f);
    assertTrue(new MovementObstacles(world).barrierJump(input, new Vec3(3, 4, 100), 123));
    assertEquals(3, world.requests.size());
    assertEquals(new Vec3(1, 2, 35), world.requests.getFirst().end());
    assertEquals(2.845, world.requests.get(1).end().x(), 1e-6);
    assertEquals(4.46, world.requests.get(1).end().y(), 1e-6);
    assertEquals(35, world.requests.get(1).end().z());
    assertEquals(world.requests.get(2).start().x(), world.requests.get(2).end().x());
    assertEquals(3, world.requests.getLast().end().z());
    assertTrue(world.requests.stream().allMatch(q -> q.presence() == 2 && q.entity() == 3));
    assertFalse(
        new MovementObstacles(new World(1, 1, .437501f))
            .barrierJump(input, new Vec3(1, 0, 0), 123));
  }

  @Test
  void partialCeilingClearanceIsSufficientButStartSolidAndLowClearanceReject() {
    assertTrue(
        new MovementObstacles(new World(.5625f, 1, 0))
            .barrierJump(input(ZERO, .1f), new Vec3(1, 0, 0), 320));
    for (float[] replies :
        List.of(
            new float[] {.56249f}, new float[] {-1}, new float[] {1, -1}, new float[] {1, 1, -1})) {
      var world = new World(replies);
      assertFalse(
          new MovementObstacles(world).barrierJump(input(ZERO, .1f), new Vec3(1, 0, 0), 320));
      assertEquals(replies.length, world.requests.size());
    }
  }

  @Test
  void gapSamplesRawHorizontalDirectionAndRetainsFloorAcrossStartSolidSample() {
    var world = new World(0, -1, .5f, 1);
    assertEquals(
        24, new MovementObstacles(world).gapDistance(new Vec3(1, 2, 3), new Vec3(3, 4, 5), 3));
    assertEquals(new Vec3(1, 2, -57), world.requests.getFirst().end());
    assertEquals(new Vec3(25, 34, 28), world.requests.get(1).start());
    assertEquals(new Vec3(49, 66, 28), world.requests.get(2).start());
    assertEquals(new Vec3(73, 98, 12), world.requests.get(3).start());
    assertTrue(world.requests.stream().allMatch(q -> q.presence() == 4 && q.entity() == 3));
  }

  @Test
  void waterBelowLargeDropCancelsGapButLavaAndSlimeDoNot() {
    for (int contents : new int[] {0, 8, 16, 32, 48}) {
      var world = new World(0, 1);
      world.contents = contents;
      assertEquals(
          (contents & 32) == 0 ? 8 : 0,
          new MovementObstacles(world).gapDistance(ZERO, new Vec3(1, 0, 0), 3));
      assertEquals(new Vec3(8, 0, -75), world.sampledContents);
    }
    var unsupported = new World(1);
    assertEquals(1, new MovementObstacles(unsupported).gapDistance(ZERO, ZERO, 3));
    assertNull(unsupported.sampledContents);
  }

  @Test
  void gapRequiresMoreThanFiftyUnitsOfSampleDropAndHasThirteenTraceLimit() {
    var world = new World(0, .625f);
    assertEquals(0, new MovementObstacles(world).gapDistance(ZERO, ZERO, 3));
    assertEquals(13, world.requests.size());
    assertEquals(8, new MovementObstacles(new World(0, .625001f)).gapDistance(ZERO, ZERO, 3));
  }

  @Test
  void zeroStepStillRequiresLandingContactAndBarrierHeightIsIndependent() {
    assertFalse(
        new MovementObstacles(new World(1, 1, 1), 0, 0)
            .barrierJump(input(ZERO, .1f), new Vec3(1, 0, 0), 100));
    assertTrue(
        new MovementObstacles(new World(1, 1, Math.nextDown(1f)), 0, 0)
            .barrierJump(input(ZERO, .1f), new Vec3(1, 0, 0), 100));
    var world = new World(1, 1, .25f);
    assertTrue(
        new MovementObstacles(world, 25, 40).barrierJump(input(ZERO, .1f), new Vec3(1, 0, 0), 100));
    assertEquals(40, world.requests.getFirst().end().z());
    assertFalse(
        new MovementObstacles(new World(1, 1, .25f), 25, 32)
            .barrierJump(input(ZERO, .1f), new Vec3(1, 0, 0), 100));
  }

  @Test
  void invalidSettingsAndSpeedFailBeforeTracing() {
    var world = new World();
    assertThrows(IllegalArgumentException.class, () -> new MovementObstacles(world, Float.NaN, 32));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MovementObstacles(world).barrierJump(input(ZERO, .1f), ZERO, Float.NaN));
    assertTrue(world.requests.isEmpty());
  }

  private static MovementInit input(Vec3 origin, float thinkTime) {
    return new MovementInit(origin, ZERO, ZERO, 3, 2, thinkTime, 2, ZERO, 0);
  }

  private record Query(Vec3 start, Vec3 end, int presence, int entity) {}

  private static final class World implements AasMovementPredictor.World {
    final float[] responses;
    final List<Query> requests = new ArrayList<>();
    int contents;
    Vec3 sampledContents;

    World(float... responses) {
      this.responses = responses;
    }

    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      int index = requests.size();
      requests.add(new Query(start, end, presence, entity));
      float fraction = index < responses.length ? responses[index] : .3f;
      boolean solid = fraction < 0;
      if (solid) fraction = 0;
      var point =
          new Vec3(
              (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
              (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
              (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
      return new AasPresenceTrace.Result(solid, fraction, point, 0, 1, 0, 0, 1);
    }

    public int contents(Vec3 point) {
      sampledContents = point;
      return contents;
    }

    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, 1);
    }

    public int area(Vec3 point) {
      return 1;
    }

    public int presence(Vec3 point) {
      return 6;
    }
  }
}
