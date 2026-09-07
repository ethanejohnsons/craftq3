package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BotDirectionalMovementTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0), FORWARD = new Vec3(1, 0, 0);

  @Test
  void swimmingNormalizesAllAxesWithoutChangingCallerDirectionOrFlags() {
    var services = new Services();
    services.swimming = true;
    var direction = new Vec3(0, 3, 4);
    var result = new BotDirectionalMovement(services).move(input(ZERO), 513, direction, 123, 6);
    assertTrue(result.accepted());
    assertEquals(513, result.movementFlags());
    assertEquals(
        new Vec3(0, .6000000238418579, .800000011920929),
        result.motion().orElseThrow().direction());
    assertEquals(direction, new Vec3(0, 3, 4));
    assertEquals(List.of("swimming"), services.calls);
    assertFalse(result.jump());
    assertFalse(result.crouch());
  }

  @Test
  void airborneBarrierFollowThroughUsesRawDirectionOnlyBelowFiftyVerticalSpeed() {
    for (float vertical : new float[] {49.999f, 50, 100}) {
      var services = new Services();
      services.ground = false;
      var direction = new Vec3(3, 4, 5);
      var result =
          new BotDirectionalMovement(services)
              .move(input(new Vec3(0, 0, vertical)), 1, direction, 320, 1);
      assertTrue(result.accepted());
      assertEquals(1, result.movementFlags());
      assertEquals(vertical < 50, result.motion().isPresent());
      result.motion().ifPresent(motion -> assertEquals(direction, motion.direction()));
      assertEquals(List.of("swimming", "ground"), services.calls);
    }
  }

  @Test
  void barrierSuccessClearsThenSetsRuntimeFlagAndBypassesPrediction() {
    var services = new Services();
    services.barrier = true;
    var direction = new Vec3(0, 5, 20);
    var result = new BotDirectionalMovement(services).move(input(ZERO), 5, direction, 320, 2);
    assertTrue(result.accepted());
    assertTrue(result.jump());
    assertFalse(result.crouch());
    assertEquals(7, result.movementFlags());
    assertEquals(new Vec3(0, 1, 0), result.motion().orElseThrow().direction());
    assertEquals(direction, services.barrierDirection);
    assertEquals(List.of("swimming", "ground", "barrier"), services.calls);
  }

  @Test
  void gapJumpPreservesCrouchPresenceAndActionsWhileExplicitJumpPredictsStanding() {
    for (int type : new int[] {2, 6}) {
      var services = new Services();
      services.gaps.add(8f);
      services.frames = 5;
      var result = new BotDirectionalMovement(services).move(input(ZERO), 0, FORWARD, 320, type);
      assertTrue(result.accepted());
      assertTrue(result.jump());
      assertTrue(result.crouch());
      assertEquals(type == 2 ? 4 : 2, services.request.presence());
      assertEquals(new Vec3(0, 0, .5), services.request.origin());
      assertEquals(new Vec3(320, 0, 400), services.request.commandMove());
      assertEquals(1, services.request.commandFrames());
      assertEquals(30, services.request.maxFrames());
      assertEquals(.1f, services.request.frameTime());
      assertEquals(61, services.request.stopEvents());
      assertEquals(type == 2 ? 1 : 0, services.gapIndex);
    }
  }

  @Test
  void predictionSafetyRejectsDangerLongJumpLandingGapsAndInsufficientDistance() {
    var services = new Services();
    services.event = 32;
    assertFalse(
        new BotDirectionalMovement(services).move(input(ZERO), 1, FORWARD, 320, 1).accepted());
    services = new Services();
    services.frames = 30;
    assertFalse(
        new BotDirectionalMovement(services).move(input(ZERO), 0, FORWARD, 320, 4).accepted());
    services = new Services();
    services.event = 1;
    services.gaps.addAll(List.of(0f, 0f, 8f));
    var result = new BotDirectionalMovement(services).move(input(ZERO), 1, FORWARD, 320, 1);
    assertFalse(result.accepted());
    assertEquals(2, result.movementFlags());
    assertTrue(result.motion().isEmpty());
    assertEquals(3, services.gapIndex);
    assertEquals(ZERO, services.gapDirections.get(1));
    services = new Services();
    services.end = new Vec3(15.999, 0, 100);
    assertFalse(
        new BotDirectionalMovement(services).move(input(ZERO), 0, FORWARD, 320, 1).accepted());
    services = new Services();
    services.end = new Vec3(-16, 0, 0);
    assertTrue(
        new BotDirectionalMovement(services).move(input(ZERO), 0, FORWARD, 320, 1).accepted());
  }

  @Test
  void absentPredictionCannotEmitUninitializedMotionAndInvalidSpeedMakesNoQueries() {
    var services = new Services();
    services.available = false;
    var result = new BotDirectionalMovement(services).move(input(ZERO), 1, FORWARD, 320, 1);
    assertFalse(result.accepted());
    assertTrue(result.motion().isEmpty());
    services.calls.clear();
    assertThrows(
        IllegalArgumentException.class,
        () -> new BotDirectionalMovement(services).move(input(ZERO), 0, FORWARD, Float.NaN, 1));
    assertTrue(services.calls.isEmpty());
  }

  private static MovementInit input(Vec3 velocity) {
    return new MovementInit(ZERO, velocity, ZERO, 3, 2, .1f, 2, ZERO, 0);
  }

  private static final class Services implements BotDirectionalMovement.Services {
    boolean swimming, barrier, ground = true, available = true;
    int event, frames = 2, gapIndex;
    Vec3 end = new Vec3(32, 0, .5), barrierDirection;
    final List<Float> gaps = new ArrayList<>();
    final List<Vec3> gapDirections = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    AasMovementPredictor.Request request;

    public boolean swimming(Vec3 origin) {
      calls.add("swimming");
      return swimming;
    }

    public boolean onGround(Vec3 origin, int presence, int entity) {
      calls.add("ground");
      return ground;
    }

    public boolean barrierJump(MovementInit input, Vec3 direction, float speed) {
      calls.add("barrier");
      barrierDirection = direction;
      return barrier;
    }

    public float gapDistance(Vec3 origin, Vec3 direction, int entity) {
      calls.add("gap");
      gapDirections.add(direction);
      int index = gapIndex++;
      return index < gaps.size() ? gaps.get(index) : 0;
    }

    public Optional<AasMovementPredictor.Prediction> predict(AasMovementPredictor.Request query) {
      calls.add("predict");
      request = query;
      return available
          ? Optional.of(
              new AasMovementPredictor.Prediction(
                  end,
                  1,
                  ZERO,
                  query.presence(),
                  event,
                  0,
                  query.maxFrames() * query.frameTime(),
                  frames,
                  Optional.empty()))
          : Optional.empty();
    }
  }
}
