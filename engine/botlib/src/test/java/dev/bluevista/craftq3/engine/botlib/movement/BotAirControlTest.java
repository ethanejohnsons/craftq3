package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

final class BotAirControlTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void predictsLandingDriftAndReversesAnOvershootWithoutClampingRawSpeed() {
    var slow = BotAirControl.control(ZERO, new Vec3f(100, 0, 0), new Vec3f(100, 100, -100));
    assertEquals(new Vec3f(.4819187521934509f, .876215934753418f, 0), slow.direction());
    assertEquals(416, slow.speed());
    assertTrue(slow.success());
    assertEquals(
        new Vec3f(-1, 0, 0),
        BotAirControl.control(ZERO, new Vec3f(400, 0, 0), new Vec3f(100, 0, -100)).direction());
  }

  @Test
  void preservesSpeedRampCancellationAndStopsAtAnExactProjectedTarget() {
    assertEquals(
        .01300048828125f, BotAirControl.control(ZERO, ZERO, new Vec3f(.001f, 0, -100)).speed());
    assertEquals(
        328.3908996582031f,
        BotAirControl.control(ZERO, ZERO, new Vec3f(25.260839462280273f, 0, -100)).speed());
    assertEquals(0, BotAirControl.control(ZERO, ZERO, new Vec3f(0, 0, -100)).speed());
    assertEquals(ZERO, BotAirControl.control(ZERO, ZERO, new Vec3f(0, 0, -100)).direction());
  }

  @Test
  void zeroVerticalStepIsFollowedUntilDescendingAndTargetsAboveAreNotClamped() {
    var result = BotAirControl.control(ZERO, new Vec3f(100, 0, 80), new Vec3f(100, 100, 0));
    assertEquals(new Vec3f(.6689647436141968f, .7432941198348999f, 0), result.direction());
    var above = BotAirControl.control(ZERO, new Vec3f(100, 0, 0), new Vec3f(100, 100, 10));
    assertEquals(new Vec3f(.7474093437194824f, .6643638610839844f, 0), above.direction());
  }

  @Test
  void retainsTheVerticalFloatRemainderOfLandingInterpolation() {
    var result =
        BotAirControl.control(
            new Vec3f(0, 0, -255.77276611328125f),
            new Vec3f(100, 0, 420.0553894042969f),
            new Vec3f(100, 100, -50.94496536254883f));
    assertEquals(
        new Vec3f(.9239293932914734f, .38256311416625977f, -1.4593625e-8f), result.direction());
  }

  @Test
  void unreachableTargetsFailAfterFiftyStepsOrOnOverflow() {
    // The original projects fifty 0.1-second steps and then reports failure (zero direction,
    // speed 400), which callers answer by heading straight for the target.
    var rising = BotAirControl.control(ZERO, new Vec3f(0, 0, 100_000_000), ZERO);
    assertFalse(rising.success());
    assertEquals(ZERO, rising.direction());
    assertEquals(400, rising.speed());
    // Falling past the target within the fifty steps still succeeds.
    assertTrue(BotAirControl.control(ZERO, new Vec3f(0, 0, 300), new Vec3f(0, 0, -100)).success());
    assertFalse(BotAirControl.control(ZERO, ZERO, new Vec3f(1e30f, 0, -100)).success());
  }
}
