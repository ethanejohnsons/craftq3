package dev.bluevista.craftq3.server.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class HostPacketTimingTest {
  @Test
  void acknowledgementsRequireCompletedKnownMessagesAndCannotResample() {
    var timing = new HostPacketTiming();
    assertEquals(999, timing.ping());
    timing.acknowledge(1, 50);
    timing.sent(1, 1000, false, 100);
    timing.acknowledge(1, 150);
    assertEquals(999, timing.ping());
    timing.sent(1, 100, true, 200);
    timing.acknowledge(1, 90);
    assertEquals(999, timing.ping());
    timing.acknowledge(1, 250);
    assertEquals(150, timing.ping());
    timing.acknowledge(1, 1000);
    assertEquals(150, timing.ping());
    timing.sent(2, 100, true, 300);
    timing.acknowledge(2, 350);
    assertEquals(100, timing.ping());
  }

  @Test
  void wrappedSequenceSlotsRejectOldAndFutureAcknowledgements() {
    var timing = new HostPacketTiming();
    timing.sent(1, 100, true, 0);
    timing.acknowledge(1, 100);
    timing.sent(33, 100, true, 1000);
    timing.acknowledge(1, 1100);
    timing.acknowledge(65, 1100);
    assertEquals(999, timing.ping());
    timing.acknowledge(33, 1200);
    assertEquals(200, timing.ping());
    for (int i = 34; i <= 65; i++) {
      timing.sent(i, 100, true, i * 100L);
      timing.acknowledge(i, i * 100L + 3000);
    }
    assertEquals(999, timing.ping());
  }

  @Test
  void pacingUsesActualSendTimeAndLiveRateChanges() {
    var timing = new HostPacketTiming();
    assertEquals(0, timing.delay(1000, 0, 0, 1, false, 100));
    timing.sent(1, 1000, true, 100);
    assertEquals(1028, timing.delay(1000, 0, 0, 1, false, 100));
    assertEquals(1048, timing.delay(1000, 0, 0, 1, true, 100));
    assertEquals(28, timing.delay(1000, 0, 0, 1, false, 1100));
    assertEquals(0, timing.delay(1000, 0, 0, 1, false, 1200));
    assertEquals(41, timing.delay(25000, 0, 0, 1, false, 100));
    assertEquals(514, timing.delay(1000, 2000, 1000, 1, false, 100));
    assertEquals(2056, timing.delay(1000, 0, 0, .5f, false, 100));
  }

  @Test
  void userinfoDefaultsAndBoundsMatchObservedNativeCases() {
    assertEquals(3000, HostPacketTiming.clientRate(null));
    assertEquals(3000, HostPacketTiming.clientRate(""));
    assertEquals(1000, HostPacketTiming.clientRate("bad"));
    assertEquals(90000, HostPacketTiming.clientRate("999999"));
    assertEquals(50, HostPacketTiming.snapshotInterval(null, 10));
    assertEquals(100, HostPacketTiming.snapshotInterval("20", 10));
    assertEquals(1000, HostPacketTiming.snapshotInterval("0", 20));
    assertEquals(33, HostPacketTiming.snapshotInterval("100", 30));
  }
}
