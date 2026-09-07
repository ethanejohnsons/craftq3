package dev.bluevista.craftq3.client.net;

import java.util.Arrays;

/** The observed native out-packet selection rule and a 32-message result lookup. */
final class SnapshotPingHistory {
  private final int[] packetTimes = new int[32], packetRealtimes = new int[32];
  private final int[] snapshotNumbers = new int[32], snapshotPings = new int[32];
  private int outgoingSequence = 1, latestSnapshot;

  void sent(int sequence, int commandTime, int realtime) {
    sequence(sequence);
    packetTimes[sequence & 31] = commandTime;
    packetRealtimes[sequence & 31] = realtime;
    outgoingSequence = sequence + 1;
  }

  int received(int sequence, int commandTime, int realtime) {
    sequence(sequence);
    int ping = 999;
    for (int age = 0; age < 32; age++) {
      int slot = (outgoingSequence - 1 - age) & 31;
      if (commandTime >= packetTimes[slot]) {
        // Native signed int arithmetic has no zero/999 clamp, including timer wraparound.
        ping = realtime - packetRealtimes[slot];
        break;
      }
    }
    snapshotNumbers[sequence & 31] = sequence;
    snapshotPings[sequence & 31] = ping;
    latestSnapshot = sequence;
    return ping;
  }

  int snapshotPing(int number) {
    if (number < 1
        || number > latestSnapshot
        || latestSnapshot - number >= 32
        || snapshotNumbers[number & 31] != number) return 999;
    return snapshotPings[number & 31];
  }

  /** Gamestate clears client-active packet metadata, while the connection sequence continues. */
  void clearLevel() {
    Arrays.fill(packetTimes, 0);
    Arrays.fill(packetRealtimes, 0);
    Arrays.fill(snapshotNumbers, 0);
    Arrays.fill(snapshotPings, 0);
    latestSnapshot = 0;
  }

  private static void sequence(int sequence) {
    if (sequence < 1 || sequence > 0x7ffffffe)
      throw new IllegalArgumentException("Invalid ping message sequence");
  }
}
