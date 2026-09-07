package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AasSwimmingPredictorTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void swimmingFrictionSlowsHorizontalMomentumWhileGravityAloneChangesVerticalMomentum() {
    var result =
        predict(
            new WaterWorld(),
            new Vec3(0, 0, 100),
            new Vec3(100, 0, 100),
            ZERO,
            false,
            0,
            1,
            .1f,
            0);
    assertEquals(new Vec3(9, 0, 106.25), result.endPosition());
    assertEquals(new Vec3(90, 0, 60), result.velocity());
    var stopped =
        predict(
            new WaterWorld(), new Vec3(0, 0, 100), new Vec3(10, 0, 100), ZERO, false, 0, 1, .1f, 0);
    assertEquals(new Vec3(0, 0, 60), stopped.velocity());
  }

  @Test
  void upwardCommandsUseSwimAccelerationEvenWhenTheCallerReportsGrounded() {
    for (boolean ground : new boolean[] {false, true}) {
      var result =
          predict(
              new WaterWorld(),
              new Vec3(0, 0, 100),
              ZERO,
              new Vec3(0, 0, 400),
              ground,
              1,
              1,
              .1f,
              0);
      assertEquals(new Vec3(0, 0, 20), result.velocity());
      assertEquals(new Vec3(0, 0, 102.25), result.endPosition());
      var diagonal =
          predict(
              new WaterWorld(),
              new Vec3(0, 0, 100),
              ZERO,
              new Vec3(400, 0, 400),
              ground,
              1,
              1,
              .1f,
              0);
      assertEquals(42.4264107, diagonal.velocity().x(), 1e-5);
      assertEquals(2.42641068, diagonal.velocity().z(), 1e-5);
    }
  }

  @Test
  void groundedCrouchSelectsTheHullButRetainsTheSwimSpeedLimit() {
    var result =
        predict(
            new WaterWorld(), new Vec3(0, 0, 100), ZERO, new Vec3(0, 0, -400), true, 1, 1, .1f, 0);
    assertEquals(4, result.presence());
    assertEquals(new Vec3(0, 0, -100), result.velocity());
    var airborne =
        predict(
            new WaterWorld(), new Vec3(0, 0, 100), ZERO, new Vec3(0, 0, -400), false, 1, 1, .1f, 0);
    assertEquals(2, airborne.presence());
    assertEquals(result.velocity(), airborne.velocity());
  }

  @Test
  void leavingTheLiquidLayerChangesPhysicsOnTheNextFrame() {
    var world = new WaterWorld();
    world.waterHeight = 0;
    var result = predict(world, ZERO, new Vec3(100, 0, 100), ZERO, false, 0, 2, .1f, 0);
    assertEquals(new Vec3(18, 0, 4.25), result.endPosition());
    assertEquals(new Vec3(90, 0, -20), result.velocity());
    assertEquals(4, world.traces);
  }

  @Test
  void swimmingSuppressesDamagingImpactButPreservesOrdinaryContactHandling() {
    var world = new WaterWorld();
    world.floor = true;
    var result =
        predict(
            world,
            new Vec3(0, 0, 50),
            new Vec3(0, 0, -1000),
            ZERO,
            false,
            0,
            2,
            .1f,
            AasMovementPredictor.HIT_GROUND_DAMAGE);
    assertEquals(0, result.stopEvent());
    assertEquals(2, result.frames());
    assertEquals(ZERO, result.endPosition());
    assertEquals(ZERO, result.velocity());
    assertEquals(6, world.traces);
    assertEquals(0, result.trace().orElseThrow().fraction());
  }

  @Test
  void existingFluidStopsApplyToSwimmersAndCombineAllThreeContentsBits() {
    var world = new WaterWorld();
    world.liquid = 56;
    var result = predict(world, new Vec3(0, 0, 100), ZERO, ZERO, false, 0, 1, .1f, 28);
    assertEquals(28, result.stopEvent());
    assertEquals(56, result.endContents());
    assertEquals(0, result.frames());
    assertEquals(1, world.traces);
    assertEquals(ZERO, result.trace().orElseThrow().endPosition());
  }

  @Test
  void waterSettingsAreIndependentBoundedAndAvailableThroughOlderConstructors() {
    var settings =
        AasMovementPredictor.Settings.from(
            Map.of(
                "phys_waterfriction",
                "0",
                "phys_watergravity",
                "0",
                "phys_maxswimvelocity",
                "250",
                "phys_swimaccelerate",
                "2"));
    var result =
        new AasMovementPredictor(new WaterWorld(), settings)
            .predict(request(new Vec3(0, 0, 100), ZERO, new Vec3(0, 0, 400), true, 1, 1, .1f, 0))
            .orElseThrow();
    assertEquals(new Vec3(0, 0, 50), result.velocity());
    assertEquals(
        33,
        new AasMovementPredictor.Settings(6, 100, 800, 320, 100, 10, 1, 270, .7f, 19).maxBarrier());
    assertEquals(
        400,
        new AasMovementPredictor.Settings(6, 100, 800, 320, 100, 10, 1, 270, .7f, 19, 33)
            .waterGravity());
    for (String key :
        new String[] {
          "phys_waterfriction", "phys_watergravity", "phys_maxswimvelocity", "phys_swimaccelerate"
        }) {
      for (String bad : new String[] {"-1", "NaN", "Infinity", "100001"})
        assertThrows(
            IllegalArgumentException.class,
            () -> AasMovementPredictor.Settings.from(Map.of(key, bad)));
    }
  }

  private static AasMovementPredictor.Prediction predict(
      WaterWorld world,
      Vec3 origin,
      Vec3 velocity,
      Vec3 command,
      boolean ground,
      int commandFrames,
      int maxFrames,
      float dt,
      int events) {
    return new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults())
        .predict(request(origin, velocity, command, ground, commandFrames, maxFrames, dt, events))
        .orElseThrow();
  }

  private static AasMovementPredictor.Request request(
      Vec3 origin,
      Vec3 velocity,
      Vec3 command,
      boolean ground,
      int commandFrames,
      int maxFrames,
      float dt,
      int events) {
    return new AasMovementPredictor.Request(
        3, origin, 2, ground, velocity, command, commandFrames, maxFrames, dt, events);
  }

  private static final class WaterWorld implements AasMovementPredictor.World {
    boolean floor;
    int liquid = 32, traces;
    float waterHeight = Float.POSITIVE_INFINITY;

    public int area(Vec3 point) {
      return 1;
    }

    public int presence(Vec3 point) {
      return 6;
    }

    public int contents(Vec3 point) {
      return point.z() < waterHeight ? liquid : 0;
    }

    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, 1);
    }

    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      traces++;
      float fraction = 1;
      boolean solid = false;
      if (floor && end.z() < 0) {
        if (start.z() < 0) {
          fraction = 0;
          solid = true;
          end = start;
        } else {
          fraction = (float) start.z() / ((float) start.z() - (float) end.z());
          end =
              new Vec3(
                  (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
                  (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
                  (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
        }
      }
      return new AasPresenceTrace.Result(solid, fraction, end, 0, 1, 0, 0, 1);
    }
  }
}
