package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import java.util.Objects;

/**
 * Which parts of a {@link Loadout} carry over, and how they are put back into a freshly spawned
 * player without cheats.
 *
 * <p>Everything but health lives in the playerState, which the game treats as authoritative (armor
 * absorption reads {@code stats[STAT_ARMOR]}, firing reads {@code ammo[]}, powerup timers are
 * {@code powerups[]} expiry times), so {@link #writeInventory} writes those fields directly, after
 * {@code ClientBegin} has spawned the player. Health is the game's private {@code
 * gentity_t.health}, copied into the playerState every frame, so it is set through the game's own
 * rules instead: {@link #healthPlan} works out how many mega-health grants and how much hurt damage
 * take the spawn health to the saved value, delivered by {@code HostTriggers} with armor and the
 * battle suit cleared first ({@link #prepareForHealth}) so neither absorbs the damage.
 */
public final class LoadoutPolicy {
  /** {@code PW_QUAD} .. {@code PW_FLIGHT}: timed powerups, not flags. */
  public static final int BASE_GAME_POWERUPS = 0b111_1110;

  public static final int PW_BATTLESUIT = 2;

  /** {@code WEAPON_READY}. */
  public static final int WEAPON_READY = 0;

  /** Health one {@code item_health_mega} grants. */
  public static final int MEGA_HEALTH = 100;

  /** The base game's policy: timed powerups carry, flags do not. */
  public static final LoadoutPolicy BASE_GAME = new LoadoutPolicy(BASE_GAME_POWERUPS);

  private final int carriedPowerups;

  /**
   * @param carriedPowerups bit {@code p} set for each powerup {@code p} that carries over
   */
  public LoadoutPolicy(int carriedPowerups) {
    this.carriedPowerups = carriedPowerups;
  }

  public boolean carries(int powerup) {
    return powerup >= 0 && powerup < Loadout.SLOTS && (carriedPowerups & (1 << powerup)) != 0;
  }

  /** The loadout with powerups this policy does not carry cleared. */
  public Loadout filter(Loadout loadout) {
    int[] powerups = loadout.powerupMillis();
    for (int p = 0; p < powerups.length; p++) if (!carries(p)) powerups[p] = 0;
    return new Loadout(
        loadout.health(),
        loadout.armor(),
        loadout.weapons(),
        loadout.weapon(),
        loadout.holdable(),
        loadout.ammo(),
        powerups);
  }

  /** Clears armor and the battle suit so restoration damage reaches health unchanged. */
  public void prepareForHealth(QvmMemory memory, int playerState) {
    memory.writeInt(stat(playerState, Loadout.STAT_ARMOR), 0);
    memory.writeInt(playerState + PlayerStateLayout.POWERUPS + PW_BATTLESUIT * 4, 0);
  }

  /**
   * Writes weapons, the selected weapon (ready to fire), ammo, armor, the holdable item and the
   * carried powerups (as expiry times from {@code levelTime}) into the playerState at {@code
   * playerState}. Powerups the policy does not carry are left as the game has them.
   */
  public void writeInventory(QvmMemory memory, int playerState, Loadout loadout, int levelTime) {
    Objects.requireNonNull(loadout, "loadout");
    memory.writeInt(stat(playerState, Loadout.STAT_WEAPONS), loadout.weapons());
    memory.writeInt(stat(playerState, Loadout.STAT_ARMOR), loadout.armor());
    memory.writeInt(stat(playerState, Loadout.STAT_HOLDABLE_ITEM), loadout.holdable());
    memory.writeInt(playerState + PlayerStateLayout.WEAPON, loadout.weapon());
    memory.writeInt(playerState + PlayerStateLayout.WEAPONSTATE, WEAPON_READY);
    memory.writeInt(playerState + PlayerStateLayout.WEAPON_TIME, 0);
    for (int w = 0; w < Loadout.SLOTS; w++)
      memory.writeInt(playerState + PlayerStateLayout.AMMO + w * 4, loadout.ammo(w));
    for (int p = 0; p < Loadout.SLOTS; p++) {
      if (!carries(p)) continue;
      int left = loadout.powerupMillis(p);
      memory.writeInt(
          playerState + PlayerStateLayout.POWERUPS + p * 4,
          left == 0 ? 0 : (int) Math.min((long) levelTime + left, Integer.MAX_VALUE));
    }
  }

  /** Reads health from a live playerState. */
  public static int health(QvmMemory memory, int playerState) {
    return memory.readInt(stat(playerState, Loadout.STAT_HEALTH));
  }

  public static int maxHealth(QvmMemory memory, int playerState) {
    return memory.readInt(stat(playerState, Loadout.STAT_MAX_HEALTH));
  }

  private static int stat(int playerState, int index) {
    return playerState + PlayerStateLayout.STATS + index * 4;
  }

  /**
   * How to take health from {@code current} to {@code target} with the game's own rules: {@code
   * heals} mega-health grants (each +{@value #MEGA_HEALTH}, capped at twice {@code maxHealth}),
   * then {@code damage} points of armor-free hurt damage. Targets above the cap are lowered to it.
   */
  public record HealthPlan(int heals, int damage, int reached) {}

  public static HealthPlan healthPlan(int current, int maxHealth, int target) {
    if (current < 1) throw new IllegalArgumentException("The player is dead");
    int cap = Math.max(maxHealth, 1) * 2;
    int goal = Math.clamp(target, 1, cap);
    int health = current;
    int heals = 0;
    while (health < goal) {
      health = Math.min(health + MEGA_HEALTH, cap);
      heals++;
    }
    return new HealthPlan(heals, health - goal, goal);
  }
}
