package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class RemoteServerClockTest {
  @Test
  void waitsForFirstActiveSnapshotAndConsumesInactiveArrivalsWithoutStarting() {
    var clock = new RemoteServerClock();
    assertEquals(OptionalInt.empty(), clock.frame(1000, 0, 1));
    clock.snapshot(1500, 2);
    assertEquals(OptionalInt.empty(), clock.frame(1000, 0, 1));
    assertFalse(clock.state().newSnapshot());
    assertFalse(clock.state().active());
    assertTrue(clock.state().hasSnapshot());
    assertEquals(OptionalInt.empty(), clock.frame(1050, 0, 1));
    clock.snapshot(1550, 0);
    assertEquals(OptionalInt.of(1550), clock.frame(1100, 0, 1));
    assertEquals(450, clock.state().delta());
    assertEquals(1550, clock.state().previousSnapshotTime());
    assertTrue(clock.state().extrapolated());
    assertFalse(clock.state().newSnapshot());
    // Once active, NOT_ACTIVE is snapshot metadata, not a request to reprime this clock.
    clock.snapshot(1600, 2);
    assertTrue(clock.frame(1150, 0, 1).isPresent());
    assertTrue(clock.state().active());
  }

  @Test
  void firstFrameHonorsNudgeButCannotPrecedeItsInitialSnapshot() {
    for (int nudge : new int[] {Integer.MIN_VALUE, -31, -30, 0, 30, 31, Integer.MAX_VALUE}) {
      var clock = new RemoteServerClock();
      clock.snapshot(500, 0);
      assertEquals(
          500 + Math.max(0, -Math.clamp(nudge, -30, 30)),
          clock.frame(1000, nudge, 1).orElseThrow());
      assertEquals(-500, clock.state().delta());
    }
  }

  @Test
  void monotonicFrameClampUsesBoundedNudgeButExtrapolationUsesUnnudgedTime() {
    var clock = steady();
    assertEquals(1152, clock.frame(1044, 30, 1).orElseThrow());
    assertFalse(clock.state().extrapolated(), "Raw time1144 is six ms before latest snapshot");
    assertEquals(1152, clock.frame(1045, 30, 1).orElseThrow());
    assertTrue(clock.state().extrapolated(), "Raw time1145 reaches the five-ms boundary");
    assertEquals(1230, clock.frame(1100, Integer.MIN_VALUE, 1).orElseThrow());
    assertEquals(1230, clock.frame(1000, Integer.MAX_VALUE, 1).orElseThrow());
    assertEquals(100, clock.state().delta(), "Frames without arrivals never adjust the offset");
  }

  @Test
  void errorThresholdsAreStrictAndAverageRoundsNegativeOddSumsDown() {
    int[] errors = {-501, -500, -499, -101, -100, 100, 101, 500, 501};
    int[] deltas = {-401, -150, -150, 49, 98, 101, 150, 350, 601};
    for (int i = 0; i < errors.length; i++) {
      var clock = steady();
      int snapshot = 2100 + errors[i];
      clock.snapshot(snapshot, 0);
      int time = clock.frame(2000, 0, 1).orElseThrow();
      assertEquals(deltas[i], clock.state().delta(), "Error " + errors[i]);
      assertEquals(Math.abs(errors[i]) > 500 ? snapshot : 2100, time, "Error " + errors[i]);
    }
  }

  @Test
  void fineAdjustmentIsDeferredUntilFollowingFrameAndOnlyRunsAtScaleZeroOrOne() {
    for (float scale : new float[] {0, -0.0f, 1, 0.5f, 2, -1, Math.nextDown(1f), Math.nextUp(1f)}) {
      var clock = steady();
      clock.snapshot(2150, 0);
      assertEquals(2100, clock.frame(2000, 0, scale).orElseThrow());
      boolean fine = scale == 0 || scale == 1;
      assertEquals(fine ? 101 : 100, clock.state().delta());
      assertEquals(fine ? 2111 : 2110, clock.frame(2010, 0, scale).orElseThrow());
      assertFalse(clock.state().extrapolated());
    }
  }

  @Test
  void extrapolationLatchSurvivesWaitingAndNonunitScaleUntilFineAdjustmentConsumesIt() {
    var clock = steady();
    clock.frame(1045, 30, 1);
    clock.snapshot(1210, 0);
    clock.frame(1050, 0, 0.5f);
    assertTrue(clock.state().extrapolated());
    assertEquals(100, clock.state().delta());
    clock.snapshot(1220, 0);
    clock.frame(1100, 0, 1);
    assertFalse(clock.state().extrapolated());
    assertEquals(98, clock.state().delta());
  }

  @Test
  void largeCorrectionsPreserveExtrapolationAndHardResetMayMoveTimeBackward() {
    var clock = steady();
    clock.frame(1045, 0, 1);
    clock.snapshot(2501, 0);
    assertEquals(2100, clock.frame(2000, 0, 1).orElseThrow());
    assertEquals(300, clock.state().delta());
    assertTrue(clock.state().extrapolated());
    assertEquals(4300, clock.frame(4000, 0, 1).orElseThrow());
    clock.snapshot(2600, 0);
    assertEquals(2600, clock.frame(4100, 0, 1).orElseThrow());
    assertEquals(-1500, clock.state().delta());
    assertTrue(clock.state().extrapolated());
    assertEquals(2610, clock.frame(4110, 0, 1).orElseThrow());
  }

  @Test
  void newestAcceptedSnapshotWinsAndRepeatedTimestampsStillTriggerAdjustment() {
    var clock = steady();
    clock.snapshot(2150, 0);
    clock.snapshot(2200, 4);
    clock.frame(2000, 0, 1);
    assertEquals(101, clock.state().delta(), "Only the latest arrival adjusts once");
    assertEquals(4, clock.state().snapshotFlags());
    clock.snapshot(2200, 0);
    clock.frame(2050, 0, 1);
    assertEquals(102, clock.state().delta(), "A new message can repeat serverTime");
  }

  @Test
  void remoteBackwardSnapshotFailsUntilExplicitNewGamestateReset() {
    var clock = steady();
    clock.snapshot(1149, 0);
    var failed = clock.state();
    assertThrows(IllegalStateException.class, () -> clock.frame(1100, 0, 1));
    assertEquals(failed, clock.state());
    clock.reset();
    assertEquals(new RemoteServerClock().state(), clock.state());
    assertEquals(OptionalInt.empty(), clock.frame(5000, 0, 1));
    clock.snapshot(100, 0);
    assertEquals(100, clock.frame(5000, 0, 1).orElseThrow());
    assertEquals(-4900, clock.state().delta());
  }

  @Test
  void invalidInputsAndOverflowLeaveStateUnchanged() {
    var clock = steady();
    var before = clock.state();
    assertThrows(IllegalArgumentException.class, () -> clock.snapshot(1200, -1));
    assertThrows(IllegalArgumentException.class, () -> clock.snapshot(1200, 256));
    assertThrows(IllegalArgumentException.class, () -> clock.frame(1100, 0, Float.NaN));
    assertThrows(
        IllegalArgumentException.class, () -> clock.frame(1100, 0, Float.POSITIVE_INFINITY));
    assertThrows(ArithmeticException.class, () -> clock.frame(Integer.MAX_VALUE, 0, 1));
    assertEquals(before, clock.state());
    clock.reset();
    clock.snapshot(Integer.MAX_VALUE, 0);
    before = clock.state();
    assertThrows(ArithmeticException.class, () -> clock.frame(-1, 0, 1));
    assertEquals(before, clock.state());
  }

  private static RemoteServerClock steady() {
    var clock = new RemoteServerClock();
    clock.snapshot(1102, 0);
    clock.frame(1000, 0, 1);
    clock.snapshot(1150, 0);
    clock.frame(1050, 0, 1);
    assertEquals(100, clock.state().delta());
    assertEquals(1152, clock.state().time());
    assertFalse(clock.state().extrapolated());
    return clock;
  }
}
