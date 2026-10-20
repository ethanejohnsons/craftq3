package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * A player's inventory as carried between a Quake match and the bridge: health, armor, owned
 * weapons and the selected one, the holdable item, ammo, and the time left on each powerup.
 * Position, velocity, score and match state belong to the destination. Values are the game module's
 * own (item numbers, weapon numbers), so a loadout is only applied to the module it was captured
 * from (see {@link LoadoutCodec}).
 *
 * @param health {@code stats[STAT_HEALTH]}
 * @param armor {@code stats[STAT_ARMOR]}
 * @param weapons {@code stats[STAT_WEAPONS]}, bit {@code w} for weapon {@code w}
 * @param weapon {@code ps.weapon}
 * @param holdable {@code stats[STAT_HOLDABLE_ITEM]}, an item number or 0
 * @param ammo {@code ammo[MAX_WEAPONS]}; -1 is infinite
 * @param powerupMillis milliseconds left on {@code powerups[MAX_POWERUPS]}, 0 when off
 */
public record Loadout(
    int health, int armor, int weapons, int weapon, int holdable, int[] ammo, int[] powerupMillis) {
  public static final int STAT_HEALTH = 0;
  public static final int STAT_HOLDABLE_ITEM = 1;
  public static final int STAT_WEAPONS = 2;
  public static final int STAT_ARMOR = 3;
  public static final int STAT_MAX_HEALTH = 6;

  /** {@code MAX_WEAPONS} and {@code MAX_POWERUPS}. */
  public static final int SLOTS = PlayerStateLayout.ARRAY_LENGTH;

  /** One day: longer powerups are treated as corrupt. */
  public static final int MAX_POWERUP_MILLIS = 86_400_000;

  public static final int MAX_HEALTH = 999;

  public Loadout {
    Objects.requireNonNull(ammo, "ammo");
    Objects.requireNonNull(powerupMillis, "powerupMillis");
    ammo = ammo.clone();
    powerupMillis = powerupMillis.clone();
    if (ammo.length != SLOTS || powerupMillis.length != SLOTS)
      throw new IllegalArgumentException("Loadouts carry " + SLOTS + " ammo and powerup slots");
    if (health < 1 || health > MAX_HEALTH) throw new IllegalArgumentException("Health " + health);
    if (armor < 0 || armor > MAX_HEALTH) throw new IllegalArgumentException("Armor " + armor);
    if (weapons < 0 || weapons >= 1 << SLOTS) throw new IllegalArgumentException("Weapons");
    if (weapon < 0 || weapon >= SLOTS || (weapon != 0 && (weapons & (1 << weapon)) == 0))
      throw new IllegalArgumentException("Selected weapon " + weapon + " is not owned");
    if (holdable < 0 || holdable > 255) throw new IllegalArgumentException("Holdable " + holdable);
    for (int a : ammo) if (a < -1 || a > 9999) throw new IllegalArgumentException("Ammo " + a);
    for (int p : powerupMillis)
      if (p < 0 || p > MAX_POWERUP_MILLIS) throw new IllegalArgumentException("Powerup " + p);
  }

  @Override
  public int[] ammo() {
    return ammo.clone();
  }

  @Override
  public int[] powerupMillis() {
    return powerupMillis.clone();
  }

  public int ammo(int weaponNumber) {
    return ammo[weaponNumber];
  }

  public int powerupMillis(int powerup) {
    return powerupMillis[powerup];
  }

  /**
   * Reads a living player's loadout from its playerState in canonical (1.32) form (see {@link
   * PlayerStateLayout#readCanonical}); the fields are at the same offsets in both profiles.
   *
   * @param levelTime the game's {@code level.time}, which powerup expiry times are relative to
   * @throws IllegalArgumentException when the player is dead or a value is out of range
   */
  public static Loadout capture(byte[] canonicalPlayerState, int levelTime) {
    if (canonicalPlayerState.length < PlayerStateLayout.SHARED_BYTES)
      throw new IllegalArgumentException("Truncated playerState");
    ByteBuffer ps = ByteBuffer.wrap(canonicalPlayerState).order(ByteOrder.LITTLE_ENDIAN);
    int[] ammo = new int[SLOTS];
    int[] powerups = new int[SLOTS];
    for (int i = 0; i < SLOTS; i++) {
      ammo[i] = ps.getInt(PlayerStateLayout.AMMO + i * 4);
      int expiry = ps.getInt(PlayerStateLayout.POWERUPS + i * 4);
      powerups[i] = expiry == 0 ? 0 : Math.clamp((long) expiry - levelTime, 0, MAX_POWERUP_MILLIS);
    }
    return new Loadout(
        stat(ps, STAT_HEALTH),
        stat(ps, STAT_ARMOR),
        stat(ps, STAT_WEAPONS),
        ps.getInt(PlayerStateLayout.WEAPON),
        stat(ps, STAT_HOLDABLE_ITEM),
        ammo,
        powerups);
  }

  private static int stat(ByteBuffer ps, int index) {
    return ps.getInt(PlayerStateLayout.STATS + index * 4);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Loadout l
        && l.health == health
        && l.armor == armor
        && l.weapons == weapons
        && l.weapon == weapon
        && l.holdable == holdable
        && Arrays.equals(l.ammo, ammo)
        && Arrays.equals(l.powerupMillis, powerupMillis);
  }

  @Override
  public int hashCode() {
    return Objects.hash(health, armor, weapons, weapon, holdable)
        + 31 * Arrays.hashCode(ammo)
        + 17 * Arrays.hashCode(powerupMillis);
  }

  @Override
  public String toString() {
    return "Loadout[health="
        + health
        + ", armor="
        + armor
        + ", weapons=0x"
        + Integer.toHexString(weapons)
        + ", weapon="
        + weapon
        + ", holdable="
        + holdable
        + ", ammo="
        + Arrays.toString(ammo)
        + ", powerupMillis="
        + Arrays.toString(powerupMillis)
        + "]";
  }
}
