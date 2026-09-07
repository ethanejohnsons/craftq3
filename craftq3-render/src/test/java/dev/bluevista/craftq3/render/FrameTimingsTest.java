package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FrameTimingsTest {
  @Test
  void reportsPresentationRateFromElapsedIntervalsRatherThanAveragingInstantFps() {
    var timings = new FrameTimings();
    assertEquals(new FrameTimings.Snapshot(0, 0, 0), timings.snapshot());
    timings.frame(1_000_000_000);
    timings.frame(1_010_000_000);
    timings.frame(1_040_000_000);
    var snapshot = timings.snapshot();
    assertEquals(50, snapshot.fps(), 1e-8);
    assertEquals(20, snapshot.meanMs(), 1e-8);
    assertEquals(30, snapshot.worstMs(), 1e-8);
  }

  @Test
  void dropsOldestIntervalAfterOneHundredTwentySamples() {
    var timings = new FrameTimings();
    long now = 1_000_000_000;
    timings.frame(now);
    now += 100_000_000;
    timings.frame(now);
    for (int i = 0; i < 119; i++) {
      now += 10_000_000;
      timings.frame(now);
    }
    assertEquals(100, timings.snapshot().worstMs(), 1e-8);
    timings.frame(now + 10_000_000);
    assertEquals(10, timings.snapshot().worstMs(), 1e-8);
    assertEquals(10, timings.snapshot().meanMs(), 1e-8);
    assertEquals(100, timings.snapshot().fps(), 1e-8);
  }

  @Test
  void duplicateTimestampsDoNotCreateInfiniteFps() {
    var timings = new FrameTimings();
    timings.frame(-30_000_000);
    timings.frame(-20_000_000);
    timings.frame(-20_000_000);
    assertEquals(100, timings.snapshot().fps(), 1e-8);
    timings.frame(-10_000_000);
    assertEquals(100, timings.snapshot().fps(), 1e-8);
  }
}
