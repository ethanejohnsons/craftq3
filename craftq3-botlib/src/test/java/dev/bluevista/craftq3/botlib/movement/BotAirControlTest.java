package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import org.junit.jupiter.api.Test;

final class BotAirControlTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void predictsLandingDriftAndReversesAnOvershootWithoutClampingRawSpeed() {
    var slow = BotAirControl.control(ZERO, new Vec3(100, 0, 0), new Vec3(100, 100, -100));
    assertEquals(new Vec3(.4819187521934509, .876215934753418, 0), slow.direction());
    assertEquals(416, slow.speed());
    assertTrue(slow.success());
    assertEquals(
        new Vec3(-1, 0, 0),
        BotAirControl.control(ZERO, new Vec3(400, 0, 0), new Vec3(100, 0, -100)).direction());
  }

  @Test
  void preservesSpeedRampCancellationAndStopsAtAnExactProjectedTarget() {
    assertEquals(
        .01300048828125f, BotAirControl.control(ZERO, ZERO, new Vec3(.001f, 0, -100)).speed());
    assertEquals(
        328.3908996582031f,
        BotAirControl.control(ZERO, ZERO, new Vec3(25.260839462280273, 0, -100)).speed());
    assertEquals(0, BotAirControl.control(ZERO, ZERO, new Vec3(0, 0, -100)).speed());
    assertEquals(ZERO, BotAirControl.control(ZERO, ZERO, new Vec3(0, 0, -100)).direction());
  }

  @Test
  void zeroVerticalStepIsFollowedUntilDescendingAndTargetsAboveAreNotClamped() {
    var result = BotAirControl.control(ZERO, new Vec3(100, 0, 80), new Vec3(100, 100, 0));
    assertEquals(new Vec3(.6689647436141968, .7432941198348999, 0), result.direction());
    var above = BotAirControl.control(ZERO, new Vec3(100, 0, 0), new Vec3(100, 100, 10));
    assertEquals(new Vec3(.7474093437194824, .6643638610839844, 0), above.direction());
  }

  @Test
  void retainsTheVerticalFloatRemainderOfLandingInterpolation() {
    var result =
        BotAirControl.control(
            new Vec3(0, 0, -255.77276611328125),
            new Vec3(100, 0, 420.0553894042969),
            new Vec3(100, 100, -50.94496536254883));
    assertEquals(
        new Vec3(.9239293932914734, .38256311416625977, (double) -1.4593625e-8f),
        result.direction());
  }

  @Test
  void workBudgetExhaustionAndArithmeticOverflowAreExplicit() {
    assertThrows(
        IllegalStateException.class,
        () -> BotAirControl.control(ZERO, new Vec3(0, 0, 100_000_000), ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> BotAirControl.control(ZERO, ZERO, new Vec3(1e30, 0, -100)));
  }
}
