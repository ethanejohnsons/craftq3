package dev.bluevista.craftq3.server;

import java.nio.*;
import java.util.*;

/** Base-game inventory checkpoint; positions, scores and match state belong to the destination. */
public record PlayerLoadout(
    int health,
    int armor,
    int weapons,
    int weapon,
    int holdable,
    List<Integer> ammo,
    List<Integer> powerupMillis) {
  public PlayerLoadout {
    ammo = List.copyOf(ammo);
    powerupMillis = List.copyOf(powerupMillis);
    if (health < 1
        || health > 200
        || armor < 0
        || armor > 200
        || weapons < 0
        || weapons > 2047
        || weapon < 1
        || weapon > 10
        || (weapons & (1 << weapon)) == 0
        || holdable < 0
        || holdable > 255
        || ammo.size() != 16
        || ammo.stream().anyMatch(v -> v < -1 || v > 9999)
        || powerupMillis.size() != 6
        || powerupMillis.stream().anyMatch(v -> v < 0 || v > 86_400_000))
      throw new IllegalArgumentException("Invalid base-game loadout");
  }

  public static PlayerLoadout capture(byte[] state, int time) {
    var ps = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN);
    if (ps.getInt(208) != 100)
      throw new IllegalArgumentException("Loadout requires standard bridge health scale");
    var ammo = new ArrayList<Integer>();
    var powerups = new ArrayList<Integer>();
    for (int i = 0; i < 16; i++) ammo.add(ps.getInt(376 + i * 4));
    for (int i = 1; i <= 6; i++)
      powerups.add(Math.clamp((long) ps.getInt(312 + i * 4) - time, 0, 86_400_000));
    return new PlayerLoadout(
        ps.getInt(184),
        ps.getInt(196),
        ps.getInt(192),
        ps.getInt(144),
        ps.getInt(188),
        ammo,
        powerups);
  }
}
