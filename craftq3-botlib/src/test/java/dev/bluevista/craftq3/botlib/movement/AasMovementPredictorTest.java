package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AasMovementPredictorTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void airGravityUsesNative100msStepWhileDisplacementUsesRequestedTime() {
    var predictor = predictor(new FlatWorld(false));
    var result =
        predictor
            .predict(request(new Vec3(0, 0, 100), ZERO, new Vec3(400, 0, 0), false, 1, 1, .05f, 0))
            .orElseThrow();
    assertEquals(new Vec3(16, 0, -80), result.velocity());
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
            .predict(request(new Vec3(1, 2, 3), ZERO, ZERO, false, 0, 0, .1f, 0))
            .orElseThrow();
    assertEquals(new Vec3(1, 2, 3.25), result.endPosition());
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
            public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
              boolean hit = ++calls[0] <= blocked;
              return new AasPresenceTrace.Result(
                  false, hit ? 0 : 1, hit ? start : end, 0, 1, 0, 0, 1);
            }

            @Override
            public Vec3 planeNormal(int plane) {
              return new Vec3(0, 0, -1);
            }
          };
      var result =
          predictor(world)
              .predict(
                  request(new Vec3(0, 0, -10), new Vec3(100, 0, 0), ZERO, false, 0, 1, .1f, 0));
      assertEquals(blocked == 19, result.isPresent());
      assertEquals(21, calls[0]);
      if (result.isPresent()) assertEquals(new Vec3(10, 0, -9.75), result.get().endPosition());
    }
  }

  @Test
  void groundContactRetainsFullTangentialDisplacementAndExpiredCommandsApplyFriction() {
    var predictor = predictor(new FlatWorld(true));
    var first =
        predictor
            .predict(request(ZERO, ZERO, new Vec3(400, 0, 0), true, 1, 1, .1f, 0))
            .orElseThrow();
    assertEquals(new Vec3(33, 0, 0), first.endPosition());
    var second =
        predictor
            .predict(request(ZERO, ZERO, new Vec3(400, 0, 0), true, 1, 2, .1f, 0))
            .orElseThrow();
    assertEquals(45.8, second.endPosition().x(), 1e-5);
    assertEquals(128, second.velocity().x());
  }

  @Test
  void jumpAddsFiveUnitsAndCrouchUsesStrictCommandThreshold() {
    var predictor = predictor(new FlatWorld(true));
    var jump =
        predictor
            .predict(request(ZERO, ZERO, new Vec3(0, 0, 2), true, 1, 1, .05f, 0))
            .orElseThrow();
    assertEquals(14.75, jump.endPosition().z());
    assertEquals(290, jump.velocity().z());
    var crouch =
        predictor
            .predict(request(ZERO, ZERO, new Vec3(400, 0, -301), true, 1, 1, .1f, 0))
            .orElseThrow();
    assertEquals(4, crouch.presence());
    assertEquals(100, crouch.velocity().x());
    var standing =
        predictor
            .predict(request(ZERO, ZERO, new Vec3(400, 0, -300), true, 1, 1, .1f, 0))
            .orElseThrow();
    assertEquals(2, standing.presence());
    assertEquals(320, standing.velocity().x());
  }

  @Test
  void stopEventsReportZeroBasedFrameTimeAndRetainLastMovementTrace() {
    var predictor = predictor(new FlatWorld(true));
    var grounded =
        predictor
            .predict(request(ZERO, ZERO, new Vec3(400, 0, 0), true, 1, 10, .1f, 1))
            .orElseThrow();
    assertEquals(1, grounded.stopEvent());
    assertEquals(0, grounded.frames());
    assertEquals(0, grounded.time());
    assertEquals(1, grounded.trace().orElseThrow().fraction());
    var falling =
        predictor
            .predict(request(new Vec3(0, 0, 100), ZERO, ZERO, false, 0, 10, .1f, 2))
            .orElseThrow();
    assertEquals(2, falling.stopEvent());
    assertEquals(0, falling.frames());
    assertEquals(92.25, falling.endPosition().z());
  }

  @Test
  void wallsRetainTangentialMotionAndStepsUseTheInsetLandingProbe() {
    var wall = predictor(new ObstacleWorld(false));
    var sliding =
        wall.predict(request(new Vec3(99, 0, 100), new Vec3(50, 50, 0), ZERO, false, 0, 1, .1f, 0))
            .orElseThrow();
    assertEquals(100, sliding.endPosition().x());
    assertEquals(6, sliding.endPosition().y());
    assertEquals(90.65, sliding.endPosition().z(), 1e-5);
    assertEquals(new Vec3(0, 50, -80), sliding.velocity());
    var step = predictor(new ObstacleWorld(true));
    var climbed =
        step.predict(request(new Vec3(99, 0, 0), new Vec3(200, 300, 0), ZERO, true, 0, 1, .1f, 0))
            .orElseThrow();
    closeVector(new Vec3(107, 12.375, 16), climbed.endPosition());
    closeVector(new Vec3(80, 120, 0), climbed.velocity());
  }

  @Test
  void rejectedStepKeepsTheOriginalWallPlaneEvenWhenTheStepTraceIsClear() {
    for (int mode = 0; mode < 3; mode++) {
      int stepMode = mode;
      int[] calls = {0};
      var world =
          new FlatWorld(false) {
            @Override
            public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
              int call = ++calls[0];
              boolean solid = call == 2 && stepMode == 1;
              float fraction = call == 1 || call == 2 && stepMode == 2 ? .5f : solid ? 0 : 1;
              var point =
                  new Vec3(
                      (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
                      (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
                      (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
              return new AasPresenceTrace.Result(
                  solid, fraction, point, 0, 1, 0, call == 1 ? 1 : 0, 1);
            }

            @Override
            public Vec3 planeNormal(int plane) {
              return plane == 1 ? new Vec3(-1, 0, 0) : new Vec3(0, 1, 0);
            }
          };
      var result =
          predictor(world)
              .predict(
                  request(
                      new Vec3(0, 0, 24.625),
                      new Vec3(200, 0, 0),
                      new Vec3(400, 0, 0),
                      true,
                      1,
                      1,
                      .1f,
                      60))
              .orElseThrow();
      assertEquals(new Vec3(16, 0, 12.875), result.endPosition());
      assertEquals(new Vec3(0, 0, -80), result.velocity());
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
            public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
              boolean wall = ++calls[0] == 1;
              var point =
                  wall
                      ? new Vec3(
                          (float) start.x() + .5f * ((float) end.x() - (float) start.x()),
                          (float) start.y() + .5f * ((float) end.y() - (float) start.y()),
                          (float) start.z() + .5f * ((float) end.z() - (float) start.z()))
                      : end;
              return new AasPresenceTrace.Result(
                  false, wall ? .5f : 1, point, 0, 1, 0, wall ? 1 : 0, 1);
            }

            @Override
            public Vec3 planeNormal(int plane) {
              return plane == 1 ? new Vec3(-1, 0, 0) : new Vec3(0, 0, 1);
            }
          };
      var settings =
          AasMovementPredictor.Settings.from(
              Map.of("phys_maxsteepness", Float.toString(steepness)));
      var result =
          new AasMovementPredictor(world, settings)
              .predict(
                  request(
                      new Vec3(0, 0, 24.625),
                      new Vec3(200, 0, 0),
                      new Vec3(400, 0, 0),
                      true,
                      1,
                      1,
                      .1f,
                      60))
              .orElseThrow();
      assertEquals(
          steepness == 1 ? new Vec3(16, 0, 12.875) : new Vec3(31.75, 0, 20.875),
          result.endPosition());
      assertEquals(steepness == 1 ? new Vec3(0, 0, -80) : new Vec3(320, 0, 0), result.velocity());
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
                    new Vec3(99, 0, 0),
                    new Vec3(200, 300, 0),
                    new Vec3(0, 0, 400),
                    true,
                    1,
                    1,
                    .1f,
                    0))
            .orElseThrow();
    closeVector(new Vec3(100, 13.5, 27.25), jumping.endPosition());
    closeVector(new Vec3(0, 120, 240), jumping.velocity());
    var carried =
        step.predict(request(new Vec3(99, 0, 5), new Vec3(50, 50, 300), ZERO, false, 0, 1, .1f, 0))
            .orElseThrow();
    assertEquals(new Vec3(103.75, 5, 16), carried.endPosition());
    assertEquals(new Vec3(50, 50, 0), carried.velocity());
  }

  @Test
  void obliqueWallsPreserveObservedSequentialProjectionAndRepeatedContacts() {
    var world = new ObstacleWorld(false, .6f, .8f);
    var result =
        predictor(world)
            .predict(
                request(new Vec3(59, 79, 100), new Vec3(50, 50, 0), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    closeVector(new Vec3(59.5426178, 80.3430328, 90.6500092), result.endPosition());
    closeVector(new Vec3(-4.57378292, 3.43037391, -80), result.velocity());
    assertEquals(22, world.traces);
    var step =
        predictor(new ObstacleWorld(true, .6f, .8f))
            .predict(request(new Vec3(59, 79, 0), new Vec3(200, 300, 0), ZERO, true, 0, 1, .1f, 60))
            .orElseThrow();
    closeVector(new Vec3(67.0999985, 91.1750031, 16), step.endPosition());
  }

  @Test
  void frictionMultipliesFrameTimeBeforeCoefficientToPreserveWallApproach() {
    var result =
        predictor(new FlatWorld(false))
            .predict(
                request(
                    new Vec3(40.341644287109375, 32.51520919799805, 0),
                    new Vec3(201.42958068847656, -48.528831481933594, 0),
                    new Vec3(416.8821105957031, 220.92019653320312, 400),
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
                    new Vec3(0, 0, 1000),
                    new Vec3(0, 0, -234.05039978027344),
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
                    new Vec3(0, 0, 1000),
                    new Vec3(220.155029296875, -468.81011962890625, 0),
                    new Vec3(84.27628326416016, 271.9375, 0),
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
          public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
            if (start.x() == 100.25 && start.z() > end.z()) stepQueries[0]++;
            return wall.trace(start, end, presence, entity);
          }

          @Override
          public Vec3 planeNormal(int plane) {
            return wall.planeNormal(plane);
          }
        };
    var predictor = new AasMovementPredictor(world, settings);
    predictor
        .predict(
            request(
                new Vec3(99, 0, 100),
                new Vec3(200, 0, 0),
                new Vec3(100, 0, 400),
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
                new Vec3(99, 0, 100),
                new Vec3(200, 0, 0),
                new Vec3(100, 0, 400),
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
    assertTrue(predictor.swimming(new Vec3(0, 0, Math.nextDown(2f))));
    assertFalse(predictor.swimming(new Vec3(0, 0, 2)));
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
                request(new Vec3(0, 0, 30), new Vec3(100, 200, -100), ZERO, false, 0, 2, .1f, 61))
            .orElseThrow();
    assertEquals(new Vec3(10, 20, 12.25), result.endPosition());
    assertEquals(new Vec3(100, 200, -180), result.velocity());
    assertEquals(28, result.stopEvent());
    assertEquals(56, result.endContents());
    assertEquals(0, result.frames());
    assertClearedTrace(result);
    var waterOnly =
        predictor
            .predict(request(new Vec3(0, 0, 30), new Vec3(0, 0, -100), ZERO, false, 0, 1, .1f, 4))
            .orElseThrow();
    assertEquals(4, waterOnly.stopEvent());
    assertEquals(56, waterOnly.endContents());
  }

  @Test
  void fastUpwardFramesSkipFluidEntryButBoundaryDisplacementChecksFeet() {
    var predictor = predictor(fluidWorld(false, 32));
    var boundary =
        predictor
            .predict(request(new Vec3(0, 0, 2), new Vec3(0, 0, 180), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    assertEquals(4, boundary.stopEvent());
    var rising =
        predictor
            .predict(
                request(new Vec3(0, 0, 2), new Vec3(0, 0, 180.001f), ZERO, false, 0, 1, .1f, 60))
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
                request(new Vec3(0, 0, 30), new Vec3(100, 200, -800), ZERO, false, 0, 2, .1f, 63))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3(10, 20, 0), result.velocity());
    assertEquals(0, result.endContents());
    assertEquals(.34375f, result.trace().orElseThrow().fraction());
    assertEquals(0, result.frames());
    var dry = predictor(new FlatWorld(true));
    var safe =
        dry.predict(request(new Vec3(0, 0, 1), new Vec3(0, 0, -552), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(0, safe.stopEvent());
    var damaging =
        dry.predict(request(new Vec3(0, 0, 1), new Vec3(0, 0, -552.5f), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(32, damaging.stopEvent());
  }

  @Test
  void flatCeilingClipsUpwardMotionAndPreservesFullHorizontalAttempt() {
    var result =
        predictor(new CeilingWorld())
            .predict(request(new Vec3(0, 0, 95), new Vec3(50, 0, 300), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    closeVector(new Vec3(6.0795455, 0, 100), result.endPosition());
    assertEquals(new Vec3(50, 0, 0), result.velocity());
    assertEquals(0, result.stopEvent());
    assertEquals(1, result.frames());
    assertClearedTrace(result);
  }

  @Test
  void ceilingImpactRetainsGroundStateFromBeforeJumpForDamageEvent() {
    var predictor = predictor(new CeilingWorld());
    var jump =
        predictor
            .predict(request(ZERO, ZERO, new Vec3(0, 0, 400), true, 1, 1, .5f, 60))
            .orElseThrow();
    assertEquals(32, jump.stopEvent());
    assertEquals(new Vec3(0, 0, 100), jump.endPosition());
    assertEquals(.9975f, jump.trace().orElseThrow().fraction());
    assertEquals(0, jump.frames());
    var airborne =
        predictor
            .predict(request(ZERO, new Vec3(0, 0, 280), ZERO, false, 0, 1, .5f, 60))
            .orElseThrow();
    assertEquals(jump.endPosition(), airborne.endPosition());
    assertEquals(0, airborne.stopEvent());
    assertEquals(1, airborne.frames());
  }

  @Test
  void tiltedCeilingClipsSequentiallyAndChargesGroundedVelocityChange() {
    var normal = new Vec3(.94868326f, 0, -.31622776f);
    var world = new SlantedCeilingWorld(normal);
    var ordinary =
        predictor(world)
            .predict(request(normal, new Vec3(0, 0, 1000), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(new Vec3(31.2783527f, 0, 93.8350525f), ordinary.endPosition());
    assertEquals(new Vec3(303.296692f, 0, 909.890076f), ordinary.velocity());
    assertEquals(0, ordinary.stopEvent());
    assertEquals(9, world.traces);
    var grounded =
        predictor(new SlantedCeilingWorld(normal))
            .predict(request(normal, new Vec3(0, 0, 100000), ZERO, true, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(32, grounded.stopEvent());
    assertEquals(new Vec3(2997.59985f, 0, 9892.08008f), grounded.velocity());
    var airborne =
        predictor(new SlantedCeilingWorld(normal))
            .predict(request(normal, new Vec3(0, 0, 100000), ZERO, false, 0, 1, .1f, 32))
            .orElseThrow();
    assertEquals(0, airborne.stopEvent());
  }

  @Test
  void walkableSlopedFloorClipsSequentiallyAndGroundsAnAirborneImpact() {
    var normal = new Vec3(-.44721359f, 0, .89442718f);
    var world = new SlantedCeilingWorld(normal);
    var result =
        predictor(world)
            .predict(
                request(new Vec3(0, 0, 10), new Vec3(100, 0, -100), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    assertEquals(new Vec3(3.50414085f, 0, 1.75207043f), result.endPosition());
    assertEquals(new Vec3(-9.52380657f, 0, -4.76190376f), result.velocity());
    assertEquals(13, world.traces);
    var impact =
        predictor(new SlantedCeilingWorld(normal))
            .predict(
                request(new Vec3(0, 0, 10), new Vec3(100, 0, -2000), ZERO, false, 0, 1, .1f, 60))
            .orElseThrow();
    assertEquals(32, impact.stopEvent());
    assertEquals(new Vec3(-75.1999969f, 0, -71.6800079f), impact.velocity());
    assertEquals(.0481220633f, impact.trace().orElseThrow().fraction());
    assertEquals(0, impact.frames());
  }

  @Test
  void steepAirborneImpactUsesTheDownwardVelocityBeforeClippingAtTheThreshold() {
    var normal = new Vec3(.67419028f, -.23551539f, .7f);
    var world = new SlantedCeilingWorld(normal);
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3(29.04101944f, -10.14492035f, 30.15278244f),
                    new Vec3(-605.9909668f, -242.5197754f, -454.2550354f),
                    new Vec3(-302.9829102f, 138.6628265f, 0),
                    false,
                    1,
                    1,
                    .2f,
                    32))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3(-23.3847046f, -67.1421738f, -54.5271072f), result.velocity());
    assertTrue(result.velocity().z() * result.velocity().z() < 4000);
    assertEquals(.29810819f, result.trace().orElseThrow().fraction());
    assertEquals(1, world.traces);
    assertEquals(0, result.frames());
  }

  @Test
  void steepAirborneImpactCanProduceDamageEvenWhenClippingTurnsVelocityUpward() {
    var normal = new Vec3(-.58659893f, -.40731034f, .7f);
    var world = new SlantedCeilingWorld(normal);
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3(0, 0, -.25),
                    new Vec3(1645.6309814f, 1300.8729248f, -664.720459f),
                    ZERO,
                    false,
                    0,
                    1,
                    .1f,
                    32))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3(46.2760849f, 76.215683f, 2.75151062f), result.velocity());
    assertEquals(ZERO, result.endPosition());
    assertEquals(0, result.trace().orElseThrow().fraction());
    assertEquals(1, world.traces);
  }

  @Test
  void positiveStepSupportKeepsUnverifiedDownwardStepPlanesExplicit() {
    int[] calls = {0};
    var world =
        new FlatWorld(false) {
          @Override
          public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
            var point =
                new Vec3(
                    (start.x() + end.x()) * .5,
                    (start.y() + end.y()) * .5,
                    (start.z() + end.z()) * .5);
            return new AasPresenceTrace.Result(
                false, .5f, point, 0, 1, 0, ++calls[0] == 1 ? 0 : 1, 1);
          }

          @Override
          public Vec3 planeNormal(int plane) {
            return plane == 0 ? new Vec3(-1, 0, 0) : new Vec3(.6f, 0, -.8f);
          }
        };
    var error =
        assertThrows(
            UnsupportedOperationException.class,
            () ->
                predictor(world)
                    .predict(request(ZERO, new Vec3(200, 0, 0), ZERO, true, 0, 1, .1f, 0)));
    assertTrue(error.getMessage().contains("step plane"));
    assertEquals(2, calls[0]);
  }

  @Test
  void airborneNegativeClippingIncreaseCanReportDamageAfterRepeatedContacts() {
    var world =
        new SlantedCeilingWorld(
            new Vec3(-.29793909192085266f, .8576924204826355f, -.41904181241989136f));
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3(-.6876286864280701f, 1.9795116186141968f, -.9671277403831482f),
                    new Vec3(-56401.0390625f, -53974.625f, 3286.847412109375f),
                    new Vec3(430.8113708496094f, 350.27978515625f, 400),
                    false,
                    1,
                    5,
                    .1f,
                    60))
            .orElseThrow();
    assertEquals(32, result.stopEvent());
    assertEquals(new Vec3(-4.71727657f, -1.87703145f, -.487909496f), result.endPosition());
    assertEquals(new Vec3(-6783.54883f, -2389.43408f, -67.5711365f), result.velocity());
    assertTrue(result.trace().orElseThrow().startSolid());
    assertEquals(11, world.traces);
  }

  @Test
  void airbornePositiveClippingIncreaseIsNotADamagingImpact() {
    var world =
        new SlantedCeilingWorld(
            new Vec3(-.6875038146972656f, .44995200634002686f, -.569983959197998f));
    var result =
        predictor(world)
            .predict(
                request(
                    new Vec3(-66.35565948486328f, 43.427921295166016f, -55.01301956176758f),
                    new Vec3(200.32989501953125f, -519.0772094726562f, 382.2943420410156f),
                    new Vec3(366.4660949707031f, -414.1288757324219f, 0),
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
        predictor.predict(request(new Vec3(0, 0, -10), ZERO, ZERO, false, 0, 1, .1f, 0)).isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () -> predictor.predict(request(ZERO, ZERO, ZERO, true, 0, 1, .1f, 128)));
    assertThrows(
        IllegalArgumentException.class, () -> request(ZERO, ZERO, ZERO, true, 0, 4097, .1f, 0));
    assertThrows(IllegalArgumentException.class, () -> request(ZERO, ZERO, ZERO, true, 0, 1, 0, 0));
    var tilted =
        new FlatWorld(true) {
          @Override
          public Vec3 planeNormal(int plane) {
            return new Vec3(.8f, 0, .8f);
          }
        };
    var failure =
        assertThrows(
            UnsupportedOperationException.class,
            () -> predictor(tilted).predict(request(ZERO, ZERO, ZERO, false, 0, 1, .1f, 60)));
    assertTrue(failure.getMessage().contains("frame=0 plane=0 normal="));
    assertTrue(failure.getMessage().contains("request=Request["));
  }

  private static void assertClearedTrace(AasMovementPredictor.Prediction result) {
    assertEquals(
        new AasPresenceTrace.Result(false, 0, ZERO, 0, 0, 0, 0, 0), result.trace().orElseThrow());
  }

  private static FlatWorld fluidWorld(boolean floor, int contents) {
    var world =
        new FlatWorld(floor) {
          @Override
          public int contents(Vec3 point) {
            return point.z() < 0 ? contents : 0;
          }
        };
    world.contents = contents;
    return world;
  }

  private static void closeVector(Vec3 expected, Vec3 actual) {
    assertEquals(expected.x(), actual.x(), 1e-5);
    assertEquals(expected.y(), actual.y(), 1e-5);
    assertEquals(expected.z(), actual.z(), 1e-5);
  }

  private static AasMovementPredictor predictor(FlatWorld world) {
    return new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults());
  }

  private static AasMovementPredictor.Request request(
      Vec3 origin,
      Vec3 velocity,
      Vec3 command,
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
    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      traces++;
      var floor = super.trace(start, end, presence, entity);
      float enter = 0, exit = 1;
      int plane = 1;
      boolean outside = false, intersects = true;
      float[] from = {
        100 - (wallX * (float) start.x() + wallY * (float) start.y()), (float) start.z() - 16
      };
      float[] to = {
        100 - (wallX * (float) end.x() + wallY * (float) end.y()), (float) end.z() - 16
      };
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
            new Vec3(
                (float) start.x() + enter * ((float) end.x() - (float) start.x()),
                (float) start.y() + enter * ((float) end.y() - (float) start.y()),
                (float) start.z() + enter * ((float) end.z() - (float) start.z()));
        return new AasPresenceTrace.Result(false, enter, hit, 0, 1, 0, plane, 1);
      }
      return floor;
    }

    @Override
    public Vec3 planeNormal(int plane) {
      return plane == 1 ? new Vec3(-wallX, -wallY, 0) : new Vec3(0, 0, 1);
    }
  }

  private static class FlatWorld implements AasMovementPredictor.World {
    private final boolean floor;
    int contents;

    FlatWorld(boolean floor) {
      this.floor = floor;
    }

    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      float fraction = 1;
      boolean solid = false;
      if (floor && end.z() < 0) {
        if (start.z() < 0) {
          solid = true;
          fraction = 0;
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

    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, 1);
    }

    public int area(Vec3 point) {
      return 1;
    }

    public int presence(Vec3 point) {
      return 6;
    }

    public int contents(Vec3 point) {
      return contents;
    }
  }

  /** Authored solid half-space used independently of the predictor's collision handling. */
  private static final class SlantedCeilingWorld extends FlatWorld {
    private final Vec3 normal;
    int traces;

    SlantedCeilingWorld(Vec3 normal) {
      super(false);
      this.normal = normal;
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      traces++;
      float from =
          (float) normal.x() * (float) start.x()
              + (float) normal.y() * (float) start.y()
              + (float) normal.z() * (float) start.z();
      float to =
          (float) normal.x() * (float) end.x()
              + (float) normal.y() * (float) end.y()
              + (float) normal.z() * (float) end.z();
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
              new Vec3(
                  (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
                  (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
                  (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
        }
      }
      return new AasPresenceTrace.Result(solid, fraction, end, 0, 1, 0, 0, 1);
    }

    @Override
    public Vec3 planeNormal(int plane) {
      return normal;
    }
  }

  private static final class CeilingWorld extends FlatWorld {
    CeilingWorld() {
      super(false);
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      float fraction = 1;
      boolean solid = false;
      if (end.z() > 100) {
        if (start.z() > 100) {
          solid = true;
          fraction = 0;
          end = start;
        } else {
          fraction = (100 - (float) start.z()) / ((float) end.z() - (float) start.z());
          end =
              new Vec3(
                  (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
                  (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
                  (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
        }
      }
      return new AasPresenceTrace.Result(solid, fraction, end, 0, 1, 0, 0, 1);
    }

    @Override
    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, -1);
    }
  }
}
