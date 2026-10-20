package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DemoServerClockTest {
  private static DemoServerClock.Reader packets(DemoServerClock clock, int... times) {
    var queue = new ArrayDeque<Integer>();
    for (int time : times) queue.add(time);
    return () -> {
      if (queue.isEmpty()) clock.end();
      else clock.snapshot(queue.remove(), 0);
    };
  }

  @Test
  void firstFrameSkipsAndInactiveSnapshotsConsumeOnlyOnePrimedPacket() throws IOException {
    var clock = new DemoServerClock();
    var reads = new AtomicInteger();
    DemoServerClock.Reader reader =
        () -> {
          int read = reads.getAndIncrement();
          clock.snapshot(500 + read * 50, read == 0 ? 2 : 0);
        };
    assertTrue(clock.frame(1000, 0, 1, false, false, 0, reader).isEmpty());
    assertEquals(0, reads.get());
    assertTrue(clock.frame(1016, 0, 1, false, false, 0, reader).isEmpty());
    assertEquals(1, reads.get());
    assertFalse(clock.state().newSnapshot());
    assertEquals(550, clock.frame(1032, 0, 1, false, false, 0, reader).orElseThrow());
    assertEquals(3, reads.get());
    assertEquals(600, clock.state().snapshotTime());
    assertEquals(550, clock.state().previousSnapshotTime());
    assertEquals(-482, clock.state().delta());
    assertTrue(clock.state().newSnapshot());
  }

  @Test
  void activeClockKeepsOffsetClampsNudgeAndFreezesWithoutRescalingRealtime() throws IOException {
    var clock = new DemoServerClock();
    var reader = packets(clock, 500, 550, 600);
    clock.frame(0, 0, 1, false, false, 0, reader);
    assertEquals(500, clock.frame(16, 0, 1, false, false, 0, reader).orElseThrow());
    assertEquals(516, clock.frame(32, 0, 2, false, false, 0, reader).orElseThrow());
    assertEquals(516, clock.frame(48, 100, 0, false, false, 0, reader).orElseThrow());
    assertEquals(516, clock.frame(64, -100, .5f, true, false, 0, reader).orElseThrow());
    assertEquals(564, clock.frame(80, 0, 1, false, false, 0, reader).orElseThrow());
    assertEquals(484, clock.state().delta());
    assertEquals(600, clock.state().snapshotTime());
  }

  @Test
  void gamestateStopsReadAheadAndRetainsConnectionSkip() throws IOException {
    var clock = new DemoServerClock();
    var actions = new ArrayDeque<Runnable>();
    actions.add(() -> clock.snapshot(500, 0));
    actions.add(clock::gamestate);
    actions.add(() -> clock.snapshot(1000, 0));
    actions.add(() -> clock.snapshot(1050, 0));
    DemoServerClock.Reader reader = () -> actions.remove().run();
    clock.frame(0, 0, 1, false, false, 0, reader);
    assertTrue(clock.frame(16, 0, 1, false, false, 0, reader).isEmpty());
    assertEquals(2, actions.size());
    assertFalse(clock.state().hasSnapshot());
    assertTrue(clock.state().firstFrameSkipped());
    assertEquals(500, clock.timedemo().baseTime());
    assertEquals(1000, clock.frame(32, 0, 1, false, false, 0, reader).orElseThrow());
    assertTrue(actions.isEmpty());
  }

  @Test
  void timedemoOverridesFreezeAndPreservesOrdinaryOldTime() throws IOException {
    var clock = new DemoServerClock();
    var reader = packets(clock, 500, 1000000);
    clock.frame(0, 0, 1, false, true, 0, reader);
    assertEquals(550, clock.frame(16, 0, 1, true, true, 1000, reader).orElseThrow());
    assertEquals(Integer.MAX_VALUE, clock.timedemo().minimumDuration());
    assertEquals(500, clock.state().oldTime());
    assertEquals(600, clock.frame(32, 0, 1, true, true, 1016, reader).orElseThrow());
    assertEquals(650, clock.frame(48, 0, 1, true, true, 1500, reader).orElseThrow());
    assertEquals(16, clock.timedemo().minimumDuration());
    assertEquals(484, clock.timedemo().maximumDuration());
    assertEquals(16, clock.timedemoDurations()[0] & 255);
    assertEquals(255, clock.timedemoDurations()[1] & 255);
    byte[] copy = clock.timedemoDurations();
    copy[0] = 99;
    assertEquals(16, clock.timedemoDurations()[0] & 255);
    clock.gamestate();
    assertEquals(3, clock.timedemo().frames());
    clock.reset();
    assertEquals(0, clock.timedemo().frames());
    assertFalse(clock.state().firstFrameSkipped());
    assertEquals(0, clock.timedemoDurations()[0]);
  }

  @Test
  void freezeAtActivationRetainsZeroTimeAndEndSuppressesFurtherReads() throws IOException {
    var clock = new DemoServerClock();
    var reader = packets(clock, 500);
    clock.frame(0, 0, 1, true, false, 0, reader);
    assertEquals(0, clock.frame(16, 0, 1, true, false, 0, reader).orElseThrow());
    assertEquals(500, clock.state().oldTime());
    assertTrue(clock.frame(32, 0, 1, false, false, 0, reader).isEmpty());
    assertTrue(clock.state().ended());
    assertTrue(
        clock.frame(48, 0, 1, false, false, 0, () -> fail("Ended demo must not read")).isEmpty());
  }

  @Test
  void boundsRecursionAndReaderFailureAreExplicit() throws IOException {
    var clock = new DemoServerClock();
    assertThrows(IllegalArgumentException.class, () -> clock.snapshot(0, 256));
    assertThrows(
        IllegalArgumentException.class,
        () -> clock.frame(0, 0, Float.NaN, false, false, 0, () -> fail("Must not read")));
    clock.frame(0, 0, 1, false, false, 0, () -> fail("Initial frame must not read"));
    assertThrows(
        IOException.class,
        () ->
            clock.frame(
                16,
                0,
                1,
                false,
                false,
                0,
                () -> {
                  throw new IOException("fixture");
                }));
    assertThrows(
        IllegalStateException.class,
        () ->
            clock.frame(
                16, 0, 1, false, false, 0, () -> clock.frame(16, 0, 1, false, false, 0, () -> {})));
    var reads = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            clock.frame(
                16,
                0,
                1,
                false,
                false,
                0,
                () -> {
                  if (reads.getAndIncrement() == 0) clock.snapshot(500, 0);
                }));
    assertEquals(DemoServerClock.MAX_READS_PER_FRAME, reads.get());
  }

  @Test
  void backwardSnapshotNeedsGamestateAndArithmeticDoesNotWrap() throws IOException {
    var clock = new DemoServerClock();
    var reader = packets(clock, 500, 550);
    clock.frame(0, 0, 1, false, false, 0, reader);
    clock.frame(16, 0, 1, false, false, 0, reader);
    clock.frame(32, 0, 1, false, false, 0, reader);
    clock.snapshot(400, 0);
    assertThrows(IllegalStateException.class, () -> clock.frame(48, 0, 1, true, false, 0, reader));
    clock.gamestate();
    assertThrows(
        ArithmeticException.class,
        () -> clock.frame(-1, 0, 1, false, false, 0, () -> clock.snapshot(Integer.MAX_VALUE, 0)));
  }
}
