package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Original collision callbacks preserving distinct wall and landing planes. */
final class AasStepLandingTest {
  private static final Vec3 LANDING = new Vec3(0, -.24253562092781067, .9701424837112427);

  @Test
  void tiltedLandingKeepsWallContactXYAndOnlyAdoptsTheProbeHeight() {
    var world = new LandingWorld(LANDING, false);
    var result = predict(world, .7f, 0, 60);
    assertEquals(new Vec3(31.75, 0, 30.75), result.endPosition());
    assertEquals(new Vec3(320, 0, 0), result.velocity());
    assertEquals(0, result.stopEvent());
    assertEquals(4, world.traces.size());
    assertEquals(
        new Query(new Vec3(16.25, 0, 40.25), new Vec3(16.25, 0, 21.25)), world.traces.get(1));
    assertEquals(new Query(new Vec3(16, 0, 30.75), new Vec3(31.75, 0, 30.75)), world.traces.get(2));
    assertEquals(0, result.trace().orElseThrow().fraction());
  }

  @Test
  void tiltedStepAcceptanceUsesStrictThresholdIncludingAdjacentFloatValues() {
    for (float threshold : new float[] {0, .3f, .7f, .95f, 1}) {
      for (float z :
          new float[] {
            Math.max(0, Math.nextDown(threshold)), threshold, Math.min(1, Math.nextUp(threshold))
          }) {
        float x = (float) Math.sqrt(1 - (double) z * z);
        var world = new LandingWorld(new Vec3(x, 0, z), false);
        var result = predict(world, threshold, 0, 60);
        boolean accepted = z > threshold;
        assertEquals(
            accepted ? new Vec3(31.75, 0, 30.75) : new Vec3(16, 0, 13.25), result.endPosition());
        assertEquals(accepted ? new Vec3(320, 0, 0) : new Vec3(0, 0, -80), result.velocity());
        assertEquals(4, world.traces.size());
      }
    }
  }

  @Test
  void startSolidStepDoesNotReadOrUseItsLandingPlane() {
    var world = new LandingWorld(new Vec3(7, 8, 9), true);
    var result = predict(world, .7f, 0, 60);
    assertEquals(new Vec3(16, 0, 13.25), result.endPosition());
    assertEquals(new Vec3(0, 0, -80), result.velocity());
    assertEquals(List.of(1), world.planes);
    assertEquals(4, world.traces.size());
  }

  @Test
  void acceptedStepZerosVerticalVelocityWithoutChargingImpactDamage() {
    var world = new LandingWorld(LANDING, false);
    var result = predict(world, .7f, -4000, 63);
    assertEquals(new Vec3(320, 0, 0), result.velocity());
    assertEquals(AasMovementPredictor.LEAVE_GROUND, result.stopEvent());
    assertEquals(0, result.frames());
    assertEquals(0, result.time());
    assertEquals(1, result.trace().orElseThrow().fraction());
    assertEquals(result.endPosition(), result.trace().orElseThrow().endPosition());
    assertEquals(4, world.traces.size());
  }

  private static AasMovementPredictor.Prediction predict(
      LandingWorld world, float steepness, float verticalVelocity, int events) {
    var settings =
        AasMovementPredictor.Settings.from(Map.of("phys_maxsteepness", Float.toString(steepness)));
    return new AasMovementPredictor(world, settings)
        .predict(
            new AasMovementPredictor.Request(
                3,
                new Vec3(0, 0, 25),
                2,
                true,
                new Vec3(200, 0, verticalVelocity),
                new Vec3(400, 0, 0),
                1,
                1,
                .1f,
                events))
        .orElseThrow();
  }

  private record Query(Vec3 start, Vec3 end) {}

  private static final class LandingWorld implements AasMovementPredictor.World {
    private final Vec3 landing;
    private final boolean blocked;
    private final List<Query> traces = new ArrayList<>();
    private final List<Integer> planes = new ArrayList<>();

    LandingWorld(Vec3 landing, boolean blocked) {
      this.landing = landing;
      this.blocked = blocked;
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      traces.add(new Query(start, end));
      int call = traces.size();
      boolean solid = call == 2 && blocked;
      float fraction = solid ? 0 : call <= 2 ? .5f : 1;
      Vec3 position =
          solid
              ? start
              : fraction == 1
                  ? end
                  : new Vec3(
                      (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
                      (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
                      (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
      return new AasPresenceTrace.Result(solid, fraction, position, 0, 1, 0, call == 1 ? 1 : 0, 1);
    }

    @Override
    public Vec3 planeNormal(int plane) {
      planes.add(plane);
      return plane == 1 ? new Vec3(-1, 0, 0) : landing;
    }

    @Override
    public int area(Vec3 point) {
      return 1;
    }

    @Override
    public int presence(Vec3 point) {
      return 6;
    }

    @Override
    public int contents(Vec3 point) {
      return 0;
    }
  }
}
