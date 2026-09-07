package dev.bluevista.craftq3.fabric.bridge;

import java.util.function.IntConsumer;

/** Keeps 8 ms player commands independent of the ordinary 50 ms Quake server frame. */
final class BridgeSimulationClock {
  private int time, serverTime, remaining;

  BridgeSimulationClock(int initialTime) {
    time = serverTime = initialTime;
  }

  int time() {
    return time;
  }

  void advance(int elapsed, IntConsumer command, IntConsumer serverFrame) {
    remaining += Math.clamp(elapsed, 0, 200);
    while (remaining >= 8) {
      time += 8;
      remaining -= 8;
      command.accept(time);
      if (serverTime + 50 <= time) {
        serverTime += 50;
        serverFrame.accept(serverTime);
      }
    }
  }
}
