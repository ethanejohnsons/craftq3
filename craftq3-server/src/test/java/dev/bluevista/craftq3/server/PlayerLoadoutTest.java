package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlayerLoadoutTest {
  @Test
  void capturesInventoryAndRemainingTimersFromPublicState() {
    var state = ByteBuffer.allocate(468).order(ByteOrder.LITTLE_ENDIAN);
    state
        .putInt(184, 73)
        .putInt(196, 57)
        .putInt(192, 38)
        .putInt(144, 5)
        .putInt(188, 27)
        .putInt(208, 100);
    state.putInt(380, -1).putInt(384, 13).putInt(396, 7).putInt(316, 25000).putInt(320, 500);
    var saved = PlayerLoadout.capture(state.array(), 1000);
    assertEquals(73, saved.health());
    assertEquals(57, saved.armor());
    assertEquals(38, saved.weapons());
    assertEquals(5, saved.weapon());
    assertEquals(27, saved.holdable());
    assertEquals(-1, saved.ammo().get(1));
    assertEquals(13, saved.ammo().get(2));
    assertEquals(7, saved.ammo().get(5));
    assertEquals(List.of(24000, 0, 0, 0, 0, 0), saved.powerupMillis());
    state.putInt(184, 0);
    assertThrows(IllegalArgumentException.class, () -> PlayerLoadout.capture(state.array(), 1000));
  }

  @Test
  void validatesAndCopiesInventoryBeforeRestoration() {
    var ammo = new ArrayList<Integer>(Collections.nCopies(16, 0));
    var timers = new ArrayList<Integer>(Collections.nCopies(6, 0));
    var saved = new PlayerLoadout(100, 0, 6, 2, 0, ammo, timers);
    ammo.set(2, 999);
    timers.set(0, 1000);
    assertEquals(0, saved.ammo().get(2));
    assertEquals(0, saved.powerupMillis().getFirst());
    assertThrows(
        IllegalArgumentException.class, () -> new PlayerLoadout(0, 0, 6, 2, 0, ammo, timers));
    assertThrows(
        IllegalArgumentException.class, () -> new PlayerLoadout(100, 201, 6, 2, 0, ammo, timers));
    assertThrows(
        IllegalArgumentException.class, () -> new PlayerLoadout(100, 0, 6, 5, 0, ammo, timers));
    ammo.set(0, -2);
    assertThrows(
        IllegalArgumentException.class, () -> new PlayerLoadout(100, 0, 6, 2, 0, ammo, timers));
  }
}
