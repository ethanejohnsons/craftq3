package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeSimulationClockTest {
  @Test
  void retains125HzInputWith20HzServerAndOrdersCompletedDamageBeforeNextInput() {
    var clock = new BridgeSimulationClock(1000);
    var commands = new ArrayList<Integer>();
    var frames = new ArrayList<Integer>();
    for (int i = 0; i < 100; i++) {
      clock.advance(
          10,
          time -> {
            if (!frames.isEmpty()) assertTrue(frames.getLast() < time);
            commands.add(time);
          },
          time -> {
            assertTrue(time <= commands.getLast());
            assertTrue(commands.getLast() - time < 8);
            frames.add(time);
          });
    }
    assertEquals(125, commands.size());
    assertEquals(20, frames.size());
    for (int i = 0; i < commands.size(); i++) assertEquals(1008 + i * 8, commands.get(i));
    for (int i = 0; i < frames.size(); i++) assertEquals(1050 + i * 50, frames.get(i));
    assertEquals(2000, clock.time());
  }

  @Test
  void renderFramePartitionDoesNotChangeSimulationAndStallsHaveBoundedCatchup() {
    assertEquals(events(List.of(200, 200, 200)), events(java.util.Collections.nCopies(100, 6)));
    assertEquals(events(List.of(200)), events(List.of(-10, 0, 10000)));
  }

  private static List<String> events(List<Integer> elapsed) {
    var clock = new BridgeSimulationClock(1000);
    var events = new ArrayList<String>();
    for (int duration : elapsed)
      clock.advance(
          duration, time -> events.add("command " + time), time -> events.add("frame " + time));
    return events;
  }
}
