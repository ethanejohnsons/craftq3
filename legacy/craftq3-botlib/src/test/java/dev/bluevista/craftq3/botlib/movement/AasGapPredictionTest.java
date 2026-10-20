package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AasGapPredictionTest {
  @Test
  void gapRestoresCurrentFrameStartAreaButRetainsVelocityAndTheMainMovementTrace() {
    var world = new World(20, 25);
    var result = predict(world, 64, 3);
    assertEquals(new Vec3(33, 2, 92.25), result.endPosition());
    assertEquals(7, result.endArea());
    assertEquals(new Vec3(320, 0, -160), result.velocity());
    assertEquals(64, result.stopEvent());
    assertEquals(0, result.endContents());
    assertEquals(1, result.frames());
    assertEquals(.1f, result.time());
    assertSame(world.movements.get(1), result.trace().orElseThrow());
    assertEquals(new Vec3(65, 2, 76.25), result.trace().orElseThrow().endPosition());
    assertEquals(2, world.gaps);
    assertEquals(1, world.bottomContentsQueries);
    assertEquals(new Vec3(65, 2, -4.75), world.bottom);
  }

  @Test
  void dropThresholdAndStartSolidShortCircuitContentsWhileFractionDoesNotDecideGap() {
    for (float fraction : new float[] {0, .25f, .5f, 1}) {
      var world = new World(25);
      world.fraction = fraction;
      assertEquals(64, predict(world, 64, 1).stopEvent());
      assertEquals(1, world.bottomContentsQueries);
    }
    var exact = new World(20);
    assertEquals(0, predict(exact, 64, 1).stopEvent());
    assertEquals(0, exact.bottomContentsQueries);
    var solid = new World(100);
    solid.solid = true;
    assertEquals(0, predict(solid, 64, 1).stopEvent());
    assertEquals(0, solid.bottomContentsQueries);
  }

  @Test
  void gapThresholdSubtractsStepAndOneSeparatelyFromCurrentHeight() {
    for (int i = 0; i < 2; i++) {
      float current = i == 0 ? -109.383232f : 1.7681581974f;
      float step = i == 0 ? 19 : 7.1f;
      var world = new World(step + 1);
      var settings =
          AasMovementPredictor.Settings.from(Map.of("phys_maxstep", Float.toString(step)));
      var request =
          new AasMovementPredictor.Request(
              3,
              new Vec3(0, 0, current - .25f),
              2,
              false,
              new Vec3(10, 0, 80),
              new Vec3(0, 0, 0),
              0,
              1,
              .1f,
              64);
      var result = new AasMovementPredictor(world, settings).predict(request).orElseThrow();
      assertEquals(i == 0 ? 0 : 64, result.stopEvent());
      assertEquals(i, world.bottomContentsQueries);
    }
  }

  @Test
  void waterAtRequestedBottomSuppressesGapButWaterAtTheActualHitDoesNot() {
    var bottomWater = new World(25);
    bottomWater.bottomContents = 32;
    assertEquals(0, predict(bottomWater, 64, 1).stopEvent());
    var hitWater = new World(25);
    hitWater.hitContents = 32;
    assertEquals(64, predict(hitWater, 64, 1).stopEvent());
    for (int contents : new int[] {8, 16, 24}) {
      var world = new World(25);
      world.bottomContents = contents;
      assertEquals(64, predict(world, 64, 1).stopEvent());
    }
  }

  @Test
  void liquidAndRequestedLeaveGroundEventsPrecedeTheGapProbe() {
    var leave = new World(25);
    assertEquals(2, predict(leave, 66, 1).stopEvent());
    assertEquals(0, leave.gaps);
    var liquid = new World(25);
    liquid.entryContents = 32;
    assertEquals(4, predict(liquid, 124, 1).stopEvent());
    assertEquals(0, liquid.gaps);
  }

  @Test
  void gapDepthUsesIndependentPhysicsBarrierSettingAndPreservesOldSettingsConstructor() {
    var old = new AasMovementPredictor.Settings(6, 100, 800, 320, 100, 10, 1, 270, .7f, 19);
    assertEquals(33, old.maxBarrier());
    var settings =
        AasMovementPredictor.Settings.from(
            Map.of("phys_maxbarrier", "12", "sv_maxbarrier", "100000", "phys_maxstep", "7"));
    var world = new World(9);
    var result = new AasMovementPredictor(world, settings).predict(request(64, 1)).orElseThrow();
    assertEquals(64, result.stopEvent());
    assertEquals(new Vec3(33, 2, 32.25), world.bottom);
    assertThrows(
        IllegalArgumentException.class,
        () -> AasMovementPredictor.Settings.from(Map.of("phys_maxbarrier", "-1")));
  }

  private static AasMovementPredictor.Prediction predict(World world, int events, int frames) {
    return new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults())
        .predict(request(events, frames))
        .orElseThrow();
  }

  private static AasMovementPredictor.Request request(int events, int frames) {
    return new AasMovementPredictor.Request(
        3,
        new Vec3(1, 2, 100),
        2,
        true,
        new Vec3(0, 0, 0),
        new Vec3(400, 0, 0),
        1,
        frames,
        .1f,
        events);
  }

  /** Authored clear world with independently supplied gap hits and spatial contents. */
  private static final class World implements AasMovementPredictor.World {
    final float[] drops;
    final List<AasPresenceTrace.Result> movements = new ArrayList<>();
    int gaps, bottomContentsQueries, bottomContents, hitContents, entryContents;
    float fraction = 1;
    boolean solid;
    Vec3 bottom, hit;

    World(float... drops) {
      this.drops = drops.clone();
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      if (presence == 4) {
        assertEquals(-1, entity);
        float drop = drops[Math.min(gaps++, drops.length - 1)];
        bottom = end;
        hit = new Vec3(start.x(), start.y(), (float) start.z() - drop);
        return new AasPresenceTrace.Result(solid, fraction, hit, 23, 77, 88, 99, 1);
      }
      assertEquals(3, entity);
      assertEquals(2, presence);
      var result = new AasPresenceTrace.Result(false, 1, end, 0, 5, 0, 0, 1);
      if (start.x() != end.x()) movements.add(result);
      return result;
    }

    @Override
    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, 1);
    }

    @Override
    public int area(Vec3 position) {
      return position.x() < 48 ? 7 : 8;
    }

    @Override
    public int presence(Vec3 position) {
      return 6;
    }

    @Override
    public int contents(Vec3 point) {
      if (point.equals(bottom)) {
        bottomContentsQueries++;
        return bottomContents;
      }
      if (point.equals(hit)) return hitContents;
      if (!movements.isEmpty() && point.z() == movements.getLast().endPosition().z() - 22)
        return entryContents;
      return 0;
    }
  }
}
