package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class SnapshotPingHistoryTest {
  @Test
  void newestQualifyingPacketWinsBySignedCommandTimeIncludingEquality() {
    var history = new SnapshotPingHistory();
    history.sent(1, 0, 100);
    history.sent(2, 20, 110);
    history.sent(3, 10, 120);
    assertEquals(80, history.received(1, 20, 200));
    assertEquals(80, history.received(2, 10, 200));
    assertEquals(100, history.received(3, 9, 200));
    assertEquals(999, history.received(4, -1, 200));
  }

  @Test
  void zeroFilledSlotsDefaultAndSignedArithmeticAreNotClamped() {
    var history = new SnapshotPingHistory();
    assertEquals(5000, history.received(1, 0, 5000));
    assertEquals(999, history.received(2, -1, 5000));
    history.sent(1, 100, 1000);
    assertEquals(-100, history.received(3, 100, 900));
    history.sent(2, 100, Integer.MAX_VALUE - 10);
    assertEquals(20, history.received(4, 100, Integer.MIN_VALUE + 9));
  }

  @Test
  void packetScanIncludesExactly32SlotsAndOverwritesOldestMetadata() {
    var history = new SnapshotPingHistory();
    for (int i = 1; i <= 32; i++) history.sent(i, 1000 + i, 10 + i);
    assertEquals(1989, history.received(1, 1001, 2000));
    history.sent(33, 1033, 43);
    assertEquals(999, history.received(2, 1001, 2000));
    assertEquals(1957, history.received(3, 1033, 2000));
  }

  @Test
  void snapshotLookupExpiresByMessageNumberAndNewLevelClearsBothRings() {
    var history = new SnapshotPingHistory();
    history.sent(1, 1, 100);
    for (int i = 1; i <= 33; i++) history.received(i, 1, 200 + i);
    assertEquals(999, history.snapshotPing(1));
    assertEquals(102, history.snapshotPing(2));
    assertEquals(133, history.snapshotPing(33));
    assertEquals(999, history.snapshotPing(34));
    assertEquals(999, history.snapshotPing(-1));
    history.received(70, 1, 300);
    assertEquals(999, history.snapshotPing(33));
    history.clearLevel();
    assertEquals(999, history.snapshotPing(70));
    assertEquals(400, history.received(71, 0, 400));
    assertThrows(IllegalArgumentException.class, () -> history.sent(0, 0, 0));
  }
}
