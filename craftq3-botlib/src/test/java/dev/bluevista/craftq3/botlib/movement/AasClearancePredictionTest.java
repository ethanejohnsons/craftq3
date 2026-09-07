package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

final class AasClearancePredictionTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void retainedCrouchHullUsesWalkSpeedWithoutAnActiveGroundCrouchCommand() {
    for (boolean grounded : new boolean[] {false, true}) {
      var world = new World(false, p -> 4);
      var result = predict(world, 4, grounded, 0, 1, 1, 100);
      assertEquals(grounded ? 320 : 32, result.velocity().x());
      assertEquals(4, result.presence());
      assertEquals(List.of(4, 4), world.tracePresence);
      assertEquals(1, world.presenceQueries.size());
    }
  }

  @Test
  void activeGroundCrouchBypassesClearanceLookupAndUsesStrictCommandThreshold() {
    for (float commandZ : new float[] {-301, -300}) {
      var world =
          new World(
              false,
              p -> {
                throw new AssertionError("Unexpected clearance lookup");
              });
      var result = predict(world, 2, true, commandZ, 1, 1, 100);
      assertEquals(commandZ < -300 ? 100 : 320, result.velocity().x());
      assertEquals(commandZ < -300 ? 4 : 2, result.presence());
      assertTrue(world.presenceQueries.isEmpty());
    }
  }

  @Test
  void standingRecoveryChecksAtFrameStartAndStopsQueryingAfterStanding() {
    var world = new World(true, p -> p.x() < 40 ? 4 : 6);
    var result = predict(world, 4, true, 0, 1, 4, 0);
    assertEquals(new Vec3(50.9199982f, 0, 0), result.endPosition());
    assertEquals(2, result.presence());
    assertEquals(
        List.of(new Vec3(0, 0, .25), new Vec3(33, 0, 0), new Vec3(45.8f, 0, 0)),
        world.presenceQueries);
    assertEquals(List.of(4, 4, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2), world.tracePresence);
  }

  @Test
  void standingHullDoesNotAutoCrouchAndCollisionStillBoundsAnImpossibleFit() {
    for (boolean blocked : new boolean[] {false, true}) {
      var world =
          new World(
              false,
              p -> {
                throw new AssertionError("Standing hull queried clearance");
              });
      world.blocked = blocked;
      var result =
          new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults())
              .predict(request(2, true, 0, 1, 1, 100));
      assertEquals(!blocked, result.isPresent());
      assertTrue(world.tracePresence.stream().allMatch(p -> p == 2));
      assertEquals(blocked ? 21 : 2, world.tracePresence.size());
    }
  }

  @Test
  void expiredCrouchCommandRetainsHullUntilStandingFits() {
    for (int available : new int[] {0, 4}) {
      var world = new World(true, p -> available);
      var result = predict(world, 2, true, -400, 1, 3, 0);
      assertEquals(new Vec3(14.3125, 0, 0), result.endPosition());
      assertEquals(4, result.presence());
      assertEquals(2, world.presenceQueries.size());
      assertTrue(world.tracePresence.stream().allMatch(p -> p == 4));
    }
  }

  @Test
  void finalBlockedContactChecksItsStepOrDamageEventBeforeReportingFailure() {
    for (int mode = 0; mode < 4; mode++) {
      int finalMode = mode;
      int[] calls = {0};
      var world =
          new AasMovementPredictor.World() {
            @Override
            public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
              int call = ++calls[0];
              boolean floor = finalMode == 1 && call == 41 || finalMode == 2 && call == 42;
              boolean clear = finalMode == 3 && call == 41;
              boolean solid = call % 2 == 0 && !floor;
              Vec3 point =
                  clear
                      ? end
                      : floor && call == 42 ? new Vec3(end.x(), end.y(), end.z() + 1) : start;
              return new AasPresenceTrace.Result(
                  solid, clear ? 1 : 0, point, 0, 1, 0, floor ? 1 : 0, 1);
            }

            @Override
            public Vec3 planeNormal(int plane) {
              return plane == 1 ? new Vec3(0, 0, 1) : new Vec3(-1, 0, 0);
            }

            @Override
            public int area(Vec3 point) {
              return 1;
            }

            @Override
            public int presence(Vec3 point) {
              throw new AssertionError();
            }

            @Override
            public int contents(Vec3 point) {
              return 0;
            }
          };
      var request =
          new AasMovementPredictor.Request(
              3, ZERO, 2, false, new Vec3(0, 0, -2000), ZERO, 0, 1, .1f, 32);
      var result =
          new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults())
              .predict(request);
      assertEquals(mode == 1, result.isPresent());
      assertEquals(mode == 0 || mode == 2 ? 42 : 41, calls[0]);
      if (result.isPresent()) assertEquals(32, result.get().stopEvent());
    }
  }

  private static AasMovementPredictor.Prediction predict(
      World world,
      int presence,
      boolean ground,
      float commandZ,
      int commandFrames,
      int frames,
      float originZ) {
    return new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults())
        .predict(request(presence, ground, commandZ, commandFrames, frames, originZ))
        .orElseThrow();
  }

  private static AasMovementPredictor.Request request(
      int presence, boolean ground, float commandZ, int commandFrames, int frames, float originZ) {
    return new AasMovementPredictor.Request(
        3,
        new Vec3(0, 0, originZ),
        presence,
        ground,
        ZERO,
        new Vec3(400, 0, commandZ),
        commandFrames,
        frames,
        .1f,
        0);
  }

  /** Independent plane collision and spatial clearance callbacks. */
  private static final class World implements AasMovementPredictor.World {
    final boolean floor;
    final ToIntFunction<Vec3> available;
    final List<Integer> tracePresence = new ArrayList<>();
    final List<Vec3> presenceQueries = new ArrayList<>();
    boolean blocked;

    World(boolean floor, ToIntFunction<Vec3> available) {
      this.floor = floor;
      this.available = available;
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      tracePresence.add(presence);
      boolean solid = blocked || floor && start.z() < 0 && end.z() < 0;
      float fraction =
          solid
              ? 0
              : floor && end.z() < 0
                  ? (float) start.z() / ((float) start.z() - (float) end.z())
                  : 1;
      Vec3 point =
          solid
              ? start
              : new Vec3(
                  (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
                  (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
                  (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
      return new AasPresenceTrace.Result(solid, fraction, point, 0, 1, 0, 0, 1);
    }

    @Override
    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, 1);
    }

    @Override
    public int area(Vec3 point) {
      return 1;
    }

    @Override
    public int presence(Vec3 point) {
      presenceQueries.add(point);
      return available.applyAsInt(point);
    }

    @Override
    public int contents(Vec3 point) {
      return 0;
    }
  }
}
