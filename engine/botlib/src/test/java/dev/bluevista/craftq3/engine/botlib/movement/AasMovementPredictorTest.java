package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AasMovementPredictorTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void airGravityUsesNative100msStepWhileDisplacementUsesRequestedTime() {
    var predictor = predictor(new FlatWorld(false));
    var result =
        predictor
            .predict(
                request(new Vec3f(0, 0, 100), ZERO, new Vec3f(400, 0, 0), false, 1, 1, .05f, 0))
            .orElseThrow();
    assertEquals(new Vec3f(16, 0, -80), result.velocity());
    assertEquals(.8, result.endPosition().x(), 1e-6);
    assertEquals(96.25, result.endPosition().z());
    assertEquals(.05f, result.time());
    assertEquals(1, result.frames());
    assertClearedTrace(result);
  }

  @Test
  void zeroFrameCompletionStillReturnsAClearedNativeTrace() {
    var result =
        predictor(new FlatWorld(false))
            .predict(request(new Vec3f(1, 2, 3), ZERO, ZERO, false, 0, 0, .1f, 0))
            .orElseThrow();
    assertEquals(new Vec3f(1, 2, 3.25f), result.endPosition());
    assertEquals(0, result.frames());
    assertClearedTrace(result);
  }

  @Test
  void twentyFirstMovementTraceFailsEvenWhenThatTraceBecomesClear() {
    for (int blocked : new int[] {19, 20}) {
      int[] calls = {0};
      var world =
          new FlatWorld(false) {
            @Override
            public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
              boolean hit = ++calls[0] <= blocked;
              return new AasPresenceTrace.Result(
                  false, hit ? 0 : 1, hit ? start : end, 0, 1, 0, 0, 1);
            }

            @Override
            public Vec3f planeNormal(int plane) {
              return new Vec3f(0, 0, -1);
            }
          };
      var result =
          predictor(world)
              .predict(
                  request(new Vec3f(0, 0, -10), new Vec3f(100, 0, 0), ZERO, false, 0, 1, .1f, 0));
      assertEquals(blocked == 19, result.isPresent());
      assertEquals(21, calls[0]);
      if (result.isPresent()) assertEquals(new Vec3f(10, 0, -9.75f), result.get().endPosition());
    }
  }

  @Test
  void groundContactRetainsFullTangentialDisplacementAndExpiredCommandsApplyFriction() {
    var predictor = predictor(new FlatWorld(true));
    var first =
        predictor
            .predict(request(ZERO, ZERO, new Vec3f(400, 0, 0), true, 1, 1, .1f, 0))
            .orElseThrow();
    assertEquals(new Vec3f(33, 0, 0), first.endPosition());
    var second =
        predictor
            .predict(request(ZERO, ZERO, new Vec3f(400, 0, 0), true, 1, 2, .1f, 0))
            .orElseThrow();
    assertEquals(45.8, second.endPosition().x(), 1e-5);
    assertEquals(128, second.velocity().x());
  }

  @Test
  void jumpAddsFiveUnitsAndCrouchUsesStrictCommandThreshold() {
    var predictor = predictor(new FlatWorld(true));
    var jump =
        predictor
            .predict(request(ZERO, ZERO, new Vec3f(0, 0, 2), true, 1, 1, .05f, 0))
            .orElseThrow();
    assertEquals(14.75, jump.endPosition().z());
    assertEquals(290, jump.velocity().z());
    var crouch =
        predictor
            .predict(request(ZERO, ZERO, new Vec3f(400, 0, -301), true, 1, 1, .1f, 0))
            .orElseThrow();
    assertEquals(4, crouch.presence());
    assertEquals(100, crouch.velocity().x());
    var standing =
        predictor
            .predict(request(ZERO, ZERO, new Vec3f(400, 0, -300), true, 1, 1, .1f, 0))
            .orElseThrow();
    assertEquals(2, standing.presence());
    assertEquals(320, standing.velocity().x());
  }

  @Test
  void stopEventsReportZeroBasedFrameTimeAndRetainLastMovementTrace() {
    var predictor = predictor(new FlatWorld(true));
    var grounded =
        predictor
            .predict(request(ZERO, ZERO, new Vec3f(400, 0, 0), true, 1, 10, .1f, 1))
            .orElseThrow();
    assertEquals(1, grounded.stopEvent());
    assertEquals(0, grounded.frames());
    assertEquals(0, grounded.time());
    assertEquals(1, grounded.trace().orElseThrow().fraction());
    var falling =
        predictor
            .predict(request(new Vec3f(0, 0, 100), ZERO, ZERO, false, 0, 10, .1f, 2))
            .orElseThrow();
    assertEquals(2, falling.stopEvent());
    assertEquals(0, falling.frames());
    assertEquals(92.25, falling.endPosition().z());
  }

  @Test
  void wallsRetainTangentialMotionAndStepsUseTheInsetLandingProbe() {
    var wall = predictor(new ObstacleWorld(false));
    var sliding =
        wall.predict(
                request(new Vec3f(99, 0, 100), new Vec3f(50, 50, 0), ZERO, false, 0, 1, .1f, 0))
            .orElseThrow();
    assertEquals(100, sliding.endPosition().x());
    assertEquals(6, sliding.endPosition().y());
    assertEquals(90.65, sliding.endPosition().z(), 1e-5);
    assertEquals(new Vec3f(0, 50, -80), sliding.velocity());
    var step = predictor(new ObstacleWorld(true));
    var climbed =
        step.predict(request(new Vec3f(99, 0, 0), new Vec3f(200, 300, 0), ZERO, true, 0, 1, .1f, 0))
            .orElseThrow();
    closeVector(new Vec3f(107, 12.375f, 16), climbed.endPosition());
    closeVector(new Vec3f(80, 120, 0), climbed.velocity());
  }

  @Test
  void rejectedStepKeepsTheOriginalWallPlaneEvenWhenTheStepTraceIsClear() {
    for (int mode = 0; mode < 3; mode++) {
      int stepMode = mode;
      int[] calls = {0};
      var world =
          new FlatWorld(false) {
            @Override
            public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
              int call = ++calls[0];
              boolean solid = call == 2 && stepMode == 1;
              float fraction = call == 1 || call == 2 && stepMode == 2 ? .5f : solid ? 0 : 1;
              var point =
                  new Vec3f(
                      start.x() + fraction * (end.x() - start.x()),
                      start.y() + fraction * (end.y() - start.y()),
                      start.z() + fraction * (end.z() - start.z()));
              return new AasPresenceTrace.Result(
                  solid, fraction, point, 0, 1, 0, call == 1 ? 1 : 0, 1);
            }

            @Override
            public Vec3f planeNormal(int plane) {
              return plane == 1 ? new Vec3f(-1, 0, 0) : new Vec3f(0, 1, 0);
            }
          };
      var result =
          predictor(world)
              .predict(
                  request(
                      new Vec3f(0, 0, 24.625f),
                      new Vec3f(200, 0, 0),
                      new Vec3f(400, 0, 0),
                      true,
                      1,
                      1,
                      .1f,
                      60))
              .orElseThrow();
      assertEquals(new Vec3f(16, 0, 12.875f), result.endPosition());
      assertEquals(new Vec3f(0, 0, -80), result.velocity());
      assertEquals(4, calls[0]);
    }
  }

  @Test
  void maximumSteepnessRejectsAFlatStepWithoutChangingOrdinaryWallClipping() {
    for (float steepness : new float[] {.7f, 1}) {
      int[] calls = {0};
      var world =
          new FlatWorld(false) {
            @Override
            public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
              boolean wall = ++calls[0] == 1;
              var point =
                  wall
                      ? new Vec3f(
                          start.x() + .5f * (end.x() - start.x()),
                          start.y() + .5f * (end.y() - start.y()),
                          start.z() + .5f * (end.z() - start.z()))
                      : end;
              return new AasPresenceTrace.Result(
                  false, wall ? .5f : 1, point, 0, 1, 0, wall ? 1 : 0, 1);
            }

            @Override
            public Vec3f planeNormal(int plane) {
              return plane == 1 ? new Vec3f(-1, 0, 0) : new Vec3f(0, 0, 1);
            }
          };
      var settings =
          AasMovementPredictor.Settings.from(
              Map.of("phys_maxsteepness", Float.toString(steepness)));
      var result =
          new AasMovementPredictor(world, settings)
              .predict(
                  request(
                      new Vec3f(0, 0, 24.625f),
                      new Vec3f(200, 0, 0),
                      new Vec3f(400, 0, 0),
                      true,
                      1,
                      1,
                      .1f,
                      60))
              .orElseThrow();
      assertEquals(
          steepness == 1 ? new Vec3f(16, 0, 12.875f) : new Vec3f(31.75f, 0, 20.875f),
          result.endPosition());
      assertEquals(steepness == 1 ? new Vec3f(0, 0, -80) : new Vec3f(320, 0, 0), result.velocity());
      assertEquals(0, result.stopEvent());
      assertEquals(4, calls[0]);
    }
  }

  @Test
  void triggeredJumpSuppressesAscendingStepButAirborneMomentumCanClimb() {
    var step = predictor(new ObstacleWorld(true));
    var jumping =
        step.predict(
                request(
                    new Vec3f(99, 0, 0),
                    new Vec3f(200, 300, 0),
                    new Vec3f(0, 0, 400),
                    true,
                    1,
                    1,
                    .1f,
                    0))
            .orElseThrow();
    closeVector(new Vec3f(100, 13.5f, 27.25f), jumping.endPosition());
    closeVector(new Vec3f(0, 120, 240), jumping.velocity());
    var carried =
        step.predict(
                request(new Vec3f(99, 0, 5), new Vec3f(50, 50, 300), ZERO, false, 0, 1, .1f, 0))
            .orElseThrow();
    assertEquals(new Vec3f(103.75f, 5, 16), carried.endPosition());
    assertEquals(new Vec3f(50, 50, 0), carried.velocity());
  }

  @Test
  void obliqueWallsPreserveObservedSequentialProjectionAndRepeatedContacts() {
    var world = new ObstacleWorld(false, .6f, .8f);
    var result =
        predictor(world)
            .predict(
                request(new Vec3f(59, 79, 100), new Vec3f(50, 50, 0), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    closeVector(new Vec3f(59.5426178f, 80.3430328f, 90.6500092f), result.endPosition());
    closeVector(new Vec3f(-4.57378292f, 3.43037391f, -80), result.velocity());
    assertEquals(22, world.traces);
    var step =
        predictor(new ObstacleWorld(true, .6f, .8f))
            .predict(
                request(new Vec3f(59, 79, 0), new Vec3f(200, 300, 0), ZERO, true, 0, 1, .1f, 60))
            .orElseThrow();
    closeVector(new Vec3f(67.0999985f, 91.1750031f, 16), step.endPosition());
  }

  @Test
  void frictionMultipliesFrameTimeBeforeCoefficientToPreserveWallApproach() {
    var result =
        predictor(new FlatWorld(false))
            .predict(
                request(
                    new Vec3f(40.341644287109375f, 32.51520919799805f, 0),
                    new Vec3f(201.42958068847656f, -48.528831481933594f, 0),
                    new Vec3f(416.8821105957031f, 220.92019653320312f, 400),
                    true,
                    1,
                    1,
                    .1f,
                    60))
            .orElseThrow();
    assertEquals(-4.427581787109375, result.velocity().y());
    assertEquals(32.072452545166016, result.endPosition().y());
  }

  @Test
  void floatConversionsPreserveGravityAndCommandNormalizationAcrossFrames() {
    var result =
        predictor(new FlatWorld(false))
            .predict(
                request(
                    new Vec3f(0, 0, 1000),
                    new Vec3f(0, 0, -234.05039978027344f),
                    ZERO,
                    false,
                    2,
                    2,
                    .1f,
                    0))
            .orElseThrow();
    assertEquals(-394.0504455566406, result.velocity().z());
    var command =
        predictor(new FlatWorld(false))
            .predict(
                request(
                    new Vec3f(0, 0, 1000),
                    new Vec3f(220.155029296875f, -468.81011962890625f, 0),
                    new Vec3f(84.27628326416016f, 271.9375f, 0),
                    false,
                    1,
                    1,
                    .2f,
                    0))
            .orElseThrow();
    assertEquals(-414.4226379394531, command.velocity().y());
  }

  @Test
  void jumpSuppressesStepProbesForThreeFramesEvenWithoutGravity() {
    var settings = new AasMovementPredictor.Settings(6, 100, 0, 320, 100, 10, 1, 270, .7f, 19);
    int[] stepQueries = {0};
    var world =
        new FlatWorld(true) {
          private final ObstacleWorld wall = new ObstacleWorld(false);

          @Override
          public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
            if (start.x() == 100.25 && start.z() > end.z()) stepQueries[0]++;
            return wall.trace(start, end, presence, entity);
          }

          @Override
          public Vec3f planeNormal(int plane) {
            return wall.planeNormal(plane);
          }
        };
    var predictor = new AasMovementPredictor(world, settings);
    predictor
        .predict(
            request(
                new Vec3f(99, 0, 100),
                new Vec3f(200, 0, 0),
                new Vec3f(100, 0, 400),
                true,
                6,
                3,
                .1f,
                60))
        .orElseThrow();
    assertEquals(0, stepQueries[0]);
    predictor
        .predict(
            request(
                new Vec3f(99, 0, 100),
                new Vec3f(200, 0, 0),
                new Vec3f(100, 0, 400),
                true,
                6,
                4,
                .1f,
                60))
        .orElseThrow();
    assertEquals(1, stepQueries[0]);
  }

  @Test
  void swimmingUsesTwoUnitOffsetAndAllThreeFluidContents() {
    var world = fluidWorld(false, 32);
    var predictor = predictor(world);
    assertTrue(predictor.swimming(new Vec3f(0, 0, Math.nextDown(2f))));
    assertFalse(predictor.swimming(new Vec3f(0, 0, 2)));
    for (int contents : new int[] {8, 16, 32}) {
      world.contents = contents;
      assertTrue(predictor.swimming(ZERO));
    }
    world.contents = 64;
    assertFalse(predictor.swimming(ZERO));
  }

  @Test
  void fluidEntryCombinesRequestedBitsAndClearsTraceWithPhysicalVelocity() {
    var predictor = predictor(fluidWorld(false, 56));
    var result =
        predictor
            .predict(
                request(new Vec3f(0, 0, 30), new Vec3f(100, 200, -100), ZERO, false, 0, 2, .1f, 61))
            .orElseThrow();
    assertEquals(new Vec3f(10, 20, 12.25f), result.endPosition());
    assertEquals(new Vec3f(100, 200, -180), result.velocity());
    assertEquals(28, result.stopEvent());
    assertEquals(56, result.endContents());
    assertEquals(0, result.frames());
    assertClearedTrace(result);
    var waterOnly =
        predictor
            .predict(request(new Vec3f(0, 0, 30), new Vec3f(0, 0, -100), ZERO, false, 0, 1, .1f, 4))
            .orElseThrow();
    assertEquals(4, waterOnly.stopEvent());
    assertEquals(56, waterOnly.endContents());
  }

  @Test
  void fastUpwardFramesSkipFluidEntryButBoundaryDisplacementChecksFeet() {
    var predictor = predictor(fluidWorld(false, 32));
    var boundary =
        predictor
            .predict(request(new Vec3f(0, 0, 2), new Vec3f(0, 0, 180), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    assertEquals(4, boundary.stopEvent());
    var rising =
        predictor
            .predict(
                request(new Vec3f(0, 0, 2), new Vec3f(0, 0, 180.001f), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    assertEquals(0, rising.stopEvent());
    assertEquals(1, rising.frames());
  }

  @Test
  void damagingImpactPrecedesFluidAndGroundAndRetainsFrameVelocity() {
    var predictor = predictor(fluidWorld(true, 32));
    var result =
        predictor
            .predict(
                request(new Vec3f(0, 0, 30), new Vec3f(100, 200, -800), ZERO, false, 0, 2, .1f, 63))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3f(10, 20, 0), result.velocity());
    assertEquals(0, result.endContents());
    assertEquals(.34375f, result.trace().orElseThrow().fraction());
    assertEquals(0, result.frames());
    var dry = predictor(new FlatWorld(true));
    var safe =
        dry.predict(request(new Vec3f(0, 0, 1), new Vec3f(0, 0, -552), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(0, safe.stopEvent());
    var damaging =
        dry.predict(
                request(new Vec3f(0, 0, 1), new Vec3f(0, 0, -552.5f), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(32, damaging.stopEvent());
  }

  @Test
  void flatCeilingClipsUpwardMotionAndPreservesFullHorizontalAttempt() {
    var result =
        predictor(new CeilingWorld())
            .predict(
                request(new Vec3f(0, 0, 95), new Vec3f(50, 0, 300), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    closeVector(new Vec3f(6.0795455f, 0, 100), result.endPosition());
    assertEquals(new Vec3f(50, 0, 0), result.velocity());
    assertEquals(0, result.stopEvent());
    assertEquals(1, result.frames());
    assertClearedTrace(result);
  }

  @Test
  void ceilingImpactRetainsGroundStateFromBeforeJumpForDamageEvent() {
    var predictor = predictor(new CeilingWorld());
    var jump =
        predictor
            .predict(request(ZERO, ZERO, new Vec3f(0, 0, 400), true, 1, 1, .5f, 60))
            .orElseThrow();
    assertEquals(32, jump.stopEvent());
    assertEquals(new Vec3f(0, 0, 100), jump.endPosition());
    assertEquals(.9975f, jump.trace().orElseThrow().fraction());
    assertEquals(0, jump.frames());
    var airborne =
        predictor
            .predict(request(ZERO, new Vec3f(0, 0, 280), ZERO, false, 0, 1, .5f, 60))
            .orElseThrow();
    assertEquals(jump.endPosition(), airborne.endPosition());
    assertEquals(0, airborne.stopEvent());
    assertEquals(1, airborne.frames());
  }

  @Test
  void tiltedCeilingClipsSequentiallyAndChargesGroundedVelocityChange() {
    var normal = new Vec3f(.94868326f, 0, -.31622776f);
    var world = new SlantedCeilingWorld(normal);
    var ordinary =
        predictor(world)
            .predict(request(normal, new Vec3f(0, 0, 1000), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(new Vec3f(31.2783527f, 0, 93.8350525f), ordinary.endPosition());
    assertEquals(new Vec3f(303.296692f, 0, 909.890076f), ordinary.velocity());
    assertEquals(0, ordinary.stopEvent());
    assertEquals(9, world.traces);
    var grounded =
        predictor(new SlantedCeilingWorld(normal))
            .predict(request(normal, new Vec3f(0, 0, 100000), ZERO, true, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(32, grounded.stopEvent());
    assertEquals(new Vec3f(2997.59985f, 0, 9892.08008f), grounded.velocity());
    var airborne =
        predictor(new SlantedCeilingWorld(normal))
            .predict(request(normal, new Vec3f(0, 0, 100000), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(0, airborne.stopEvent());
  }

  @Test
  void walkableSlopedFloorClipsSequentiallyAndGroundsAnAirborneImpact() {
    var normal = new Vec3f(-.44721359f, 0, .89442718f);
    var world = new SlantedCeilingWorld(normal);
    var result =
        predictor(world)
            .predict(
                request(new Vec3f(0, 0, 10), new Vec3f(100, 0, -100), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    assertEquals(new Vec3f(3.50414085f, 0, 1.75207043f), result.endPosition());
    assertEquals(new Vec3f(-9.52380657f, 0, -4.76190376f), result.velocity());
    assertEquals(13, world.traces);
    var impact =
        predictor(new SlantedCeilingWorld(normal))
            .predict(
                request(new Vec3f(0, 0, 10), new Vec3f(100, 0, -2000), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    assertEquals(32, impact.stopEvent());
    assertEquals(new Vec3f(-75.1999969f, 0, -71.6800079f), impact.velocity());
    assertEquals(.0481220633f, impact.trace().orElseThrow().fraction());
    assertEquals(0, impact.frames());
  }

  @Test
  void steepAirborneImpactUsesTheDownwardVelocityBeforeClippingAtTheThreshold() {
    var normal = new Vec3f(.67419028f, -.23551539f, .7f);
    var world = new SlantedCeilingWorld(normal);
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3f(29.04101944f, -10.14492035f, 30.15278244f),
                    new Vec3f(-605.9909668f, -242.5197754f, -454.2550354f),
                    new Vec3f(-302.9829102f, 138.6628265f, 0),
                    false,
                    1,
                    1,
                    .2f,
                    32))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3f(-23.3847046f, -67.1421738f, -54.5271072f), result.velocity());
    assertTrue(result.velocity().z() * result.velocity().z() < 4000);
    assertEquals(.29810819f, result.trace().orElseThrow().fraction());
    assertEquals(1, world.traces);
    assertEquals(0, result.frames());
  }

  @Test
  void steepAirborneImpactCanProduceDamageEvenWhenClippingTurnsVelocityUpward() {
    var normal = new Vec3f(-.58659893f, -.40731034f, .7f);
    var world = new SlantedCeilingWorld(normal);
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3f(0, 0, -.25f),
                    new Vec3f(1645.6309814f, 1300.8729248f, -664.720459f),
                    ZERO,
                    false,
                    0,
                    1,
                    .1f,
                    32))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3f(46.2760849f, 76.215683f, 2.75151062f), result.velocity());
    assertEquals(ZERO, result.endPosition());
    assertEquals(0, result.trace().orElseThrow().fraction());
    assertEquals(1, world.traces);
  }

  @Test
  void downwardStepPlaneKeepsTheWallInsteadOfStepping() {
    int[] calls = {0};
    var world =
        new FlatWorld(false) {
          @Override
          public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
            var point =
                new Vec3f(
                    (start.x() + end.x()) * .5f,
                    (start.y() + end.y()) * .5f,
                    (start.z() + end.z()) * .5f);
            return new AasPresenceTrace.Result(
                false, .5f, point, 0, 1, 0, ++calls[0] == 1 ? 0 : 1, 1);
          }

          @Override
          public Vec3f planeNormal(int plane) {
            return plane == 0 ? new Vec3f(-1, 0, 0) : new Vec3f(.6f, 0, -.8f);
          }
        };
    // The legacy port refused a downward step plane; it now clips against the original wall.
    var result =
        predictor(world).predict(request(ZERO, new Vec3f(200, 0, 0), ZERO, true, 0, 1, .1f, 0));
    assertTrue(calls[0] >= 2);
    result.ifPresent(prediction -> assertTrue(prediction.endPosition().isFinite()));
  }

  @Test
  void airborneNegativeClippingIncreaseCanReportDamageAfterRepeatedContacts() {
    var world =
        new SlantedCeilingWorld(
            new Vec3f(-.29793909192085266f, .8576924204826355f, -.41904181241989136f));
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3f(-.6876286864280701f, 1.9795116186141968f, -.9671277403831482f),
                    new Vec3f(-56401.0390625f, -53974.625f, 3286.847412109375f),
                    new Vec3f(430.8113708496094f, 350.27978515625f, 400),
                    false,
                    1,
                    5,
                    .1f,
                    60))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3f(-4.71727657f, -1.87703145f, -.487909496f), result.endPosition());
    assertEquals(new Vec3f(-6783.54883f, -2389.43408f, -67.5711365f), result.velocity());
    assertTrue(result.trace().orElseThrow().startSolid());
    assertEquals(11, world.traces);
  }

  @Test
  void airbornePositiveClippingIncreaseIsNotADamagingImpact() {
    var world =
        new SlantedCeilingWorld(
            new Vec3f(-.6875038146972656f, .44995200634002686f, -.569983959197998f));
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3f(-66.35565948486328f, 43.427921295166016f, -55.01301956176758f),
                    new Vec3f(200.32989501953125f, -519.0772094726562f, 382.2943420410156f),
                    new Vec3f(366.4660949707031f, -414.1288757324219f, 0),
                    false,
                    1,
                    22,
                    1,
                    61))
            .orElseThrow();
    assertEquals(0, result.stopEvent());
    assertEquals(22, result.frames());
    assertEquals(59, world.traces);
  }

  @Test
  void unsupportedConditionsAndBudgetsDoNotReturnInventedMovement() {
    var world = new FlatWorld(true);
    var predictor = predictor(world);
    assertTrue(
        predictor
            .predict(request(new Vec3f(0, 0, -10), ZERO, ZERO, false, 0, 1, .1f, 0))
            .isEmpty());
    // The jump pad event (128) is supported now; this fixture has no jump pad areas.
    assertTrue(predictor.predict(request(ZERO, ZERO, ZERO, true, 0, 1, .1f, 128)).isPresent());
    assertThrows(
        IllegalArgumentException.class, () -> request(ZERO, ZERO, ZERO, true, 0, 4097, .1f, 0));
    assertThrows(IllegalArgumentException.class, () -> request(ZERO, ZERO, ZERO, true, 0, 1, 0, 0));
    var tilted =
        new FlatWorld(true) {
          @Override
          public Vec3f planeNormal(int plane) {
            return new Vec3f(.8f, 0, .8f);
          }
        };
    // A non-unit contact plane is clipped against instead of ending the prediction.
    assertDoesNotThrow(
        () -> predictor(tilted).predict(request(ZERO, ZERO, ZERO, false, 0, 1, .1f, 60)));
  }

  private static void assertClearedTrace(AasMovementPredictor.Prediction result) {
    assertEquals(
        new AasPresenceTrace.Result(false, 0, ZERO, 0, 0, 0, 0, 0), result.trace().orElseThrow());
  }

  private static FlatWorld fluidWorld(boolean floor, int contents) {
    var world =
        new FlatWorld(floor) {
          @Override
          public int contents(Vec3f point) {
            return point.z() < 0 ? contents : 0;
          }
        };
    world.contents = contents;
    return world;
  }

  private static void closeVector(Vec3f expected, Vec3f actual) {
    assertEquals(expected.x(), actual.x(), 1e-5);
    assertEquals(expected.y(), actual.y(), 1e-5);
    assertEquals(expected.z(), actual.z(), 1e-5);
  }

  private static AasMovementPredictor predictor(FlatWorld world) {
    return new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults());
  }

  private static AasMovementPredictor.Request request(
      Vec3f origin,
      Vec3f velocity,
      Vec3f command,
      boolean grounded,
      int commandFrames,
      int frames,
      float dt,
      int events) {
    return new AasMovementPredictor.Request(
        3, origin, 2, grounded, velocity, command, commandFrames, frames, dt, events);
  }

  /** Authored floor and solid half-space/step intersection; independent of the predictor. */
  private static final class ObstacleWorld extends FlatWorld {
    private final boolean step;
    private final float wallX, wallY;
    int traces;

    ObstacleWorld(boolean step) {
      this(step, 1, 0);
    }

    ObstacleWorld(boolean step, float wallX, float wallY) {
      super(true);
      this.step = step;
      this.wallX = wallX;
      this.wallY = wallY;
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
      traces++;
      var floor = super.trace(start, end, presence, entity);
      float enter = 0, exit = 1;
      int plane = 1;
      boolean outside = false, intersects = true;
      float[] from = {100 - (wallX * start.x() + wallY * start.y()), start.z() - 16};
      float[] to = {100 - (wallX * end.x() + wallY * end.y()), end.z() - 16};
      for (int i = 0; i < (step ? 2 : 1); i++) {
        outside |= from[i] > 0;
        if (from[i] >= 0 && to[i] >= 0) {
          intersects = false;
          break;
        }
        if (from[i] < 0 && to[i] < 0) continue;
        float fraction = from[i] / (from[i] - to[i]);
        if (from[i] > to[i]) {
          if (fraction >= enter) {
            enter = fraction;
            plane = i == 0 ? 1 : 2;
          }
        } else exit = Math.min(exit, fraction);
      }
      if (intersects && !outside)
        return new AasPresenceTrace.Result(true, 0, start, 0, 1, 0, plane, 1);
      if (intersects && enter < exit && enter < floor.fraction()) {
        var hit =
            new Vec3f(
                start.x() + enter * (end.x() - start.x()),
                start.y() + enter * (end.y() - start.y()),
                start.z() + enter * (end.z() - start.z()));
        return new AasPresenceTrace.Result(false, enter, hit, 0, 1, 0, plane, 1);
      }
      return floor;
    }

    @Override
    public Vec3f planeNormal(int plane) {
      return plane == 1 ? new Vec3f(-wallX, -wallY, 0) : new Vec3f(0, 0, 1);
    }
  }

  private static class FlatWorld implements AasMovementPredictor.World {
    private final boolean floor;
    int contents;

    FlatWorld(boolean floor) {
      this.floor = floor;
    }

    public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
      float fraction = 1;
      boolean solid = false;
      if (floor && end.z() < 0) {
        if (start.z() < 0) {
          solid = true;
          fraction = 0;
          end = start;
        } else {
          fraction = start.z() / (start.z() - end.z());
          end =
              new Vec3f(
                  start.x() + fraction * (end.x() - start.x()),
                  start.y() + fraction * (end.y() - start.y()),
                  start.z() + fraction * (end.z() - start.z()));
        }
      }
      return new AasPresenceTrace.Result(solid, fraction, end, 0, 1, 0, 0, 1);
    }

    public Vec3f planeNormal(int plane) {
      return new Vec3f(0, 0, 1);
    }

    public int area(Vec3f point) {
      return 1;
    }

    public int presence(Vec3f point) {
      return 6;
    }

    public int contents(Vec3f point) {
      return contents;
    }
  }

  /** Authored solid half-space used independently of the predictor's collision handling. */
  private static final class SlantedCeilingWorld extends FlatWorld {
    private final Vec3f normal;
    int traces;

    SlantedCeilingWorld(Vec3f normal) {
      super(false);
      this.normal = normal;
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
      traces++;
      float from = normal.x() * start.x() + normal.y() * start.y() + normal.z() * start.z();
      float to = normal.x() * end.x() + normal.y() * end.y() + normal.z() * end.z();
      float fraction = 1;
      boolean solid = false;
      if (to < 0) {
        if (from < 0) {
          fraction = 0;
          solid = true;
          end = start;
        } else {
          fraction = from / (from - to);
          end =
              new Vec3f(
                  start.x() + fraction * (end.x() - start.x()),
                  start.y() + fraction * (end.y() - start.y()),
                  start.z() + fraction * (end.z() - start.z()));
        }
      }
      return new AasPresenceTrace.Result(solid, fraction, end, 0, 1, 0, 0, 1);
    }

    @Override
    public Vec3f planeNormal(int plane) {
      return normal;
    }
  }

  private static final class CeilingWorld extends FlatWorld {
    CeilingWorld() {
      super(false);
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
      float fraction = 1;
      boolean solid = false;
      if (end.z() > 100) {
        if (start.z() > 100) {
          solid = true;
          fraction = 0;
          end = start;
        } else {
          fraction = (100 - start.z()) / (end.z() - start.z());
          end =
              new Vec3f(
                  start.x() + fraction * (end.x() - start.x()),
                  start.y() + fraction * (end.y() - start.y()),
                  start.z() + fraction * (end.z() - start.z()));
        }
      }
      return new AasPresenceTrace.Result(solid, fraction, end, 0, 1, 0, 0, 1);
    }

    @Override
    public Vec3f planeNormal(int plane) {
      return new Vec3f(0, 0, -1);
    }
  }
}
