package dev.bluevista.craftq3.mc.hybrid.bridge;

/**
 * Queued damage from outside the game (Minecraft mobs hitting the Quake player), delivered through
 * the game's own {@code trigger_hurt} so armor, the battle suit, pain, death and respawn stay the
 * original qagame's.
 *
 * <p>A hurt trigger's damage is fixed at spawn, so instead of one trigger per amount (the legacy
 * 255) a pool of {@link #TRIGGERS} triggers with damage 1, 2, 4, ... 128 encodes any amount up to
 * {@link #MAX_PER_TOUCH} as the set of triggers touched together. A touched trigger re-arms {@link
 * #REARM_MILLIS} later ({@code FRAMETIME}), so larger or faster damage is spread over consecutive
 * windows. Each touched trigger is a separate {@code G_Damage} call, so armor rounding applies per
 * trigger.
 *
 * <p>The game attributes this damage to the trigger ({@code hurt_touch} passes itself as attacker),
 * so a death reads "was in the wrong place" rather than naming the mob; no unmodified qagame path
 * names an arbitrary attacker with exact damage (see {@code docs/DIVERGENCES.md}).
 *
 * <p>One queue per client; the triggers themselves are shared ({@code HostTriggerPool} tracks which
 * have re-armed). Owned by the simulation thread.
 */
public final class ExternalDamage {
  /** Hurt triggers in the pool; trigger {@code i} deals {@code 1 << i}. */
  public static final int TRIGGERS = 8;

  /** The most one touch delivers. */
  public static final int MAX_PER_TOUCH = (1 << TRIGGERS) - 1;

  /** {@code FRAMETIME}: a {@code trigger_hurt} fires at most once per this many milliseconds. */
  public static final int REARM_MILLIS = 100;

  /** Damage queued beyond this is dropped. */
  public static final int MAX_QUEUED = 1 << 20;

  private int queued;
  private int readyAt = Integer.MIN_VALUE;
  private long delivered;

  /** Queues {@code amount} (at least 1) points of damage. */
  public void add(int amount) {
    if (amount < 1) throw new IllegalArgumentException("Damage must be positive");
    queued = (int) Math.min((long) queued + amount, MAX_QUEUED);
  }

  public int queued() {
    return queued;
  }

  /** Damage handed out by {@link #take} so far. */
  public long delivered() {
    return delivered;
  }

  /**
   * The triggers to touch at {@code levelTime} as a bit mask (bit {@code i} for the trigger dealing
   * {@code 1 << i}), removing their sum from the queue; 0 when nothing is queued or the pool has
   * not re-armed. The caller must then actually run the player's think with those triggers in
   * place.
   */
  public int take(int levelTime) {
    return take(levelTime, MAX_PER_TOUCH);
  }

  /**
   * As {@link #take(int)}, using only the triggers in {@code armed}: the pool is shared by every
   * client, and a trigger another client touched in this window has not re-armed yet. Hands out the
   * largest amount up to the queue that those triggers can encode (0 when none fits).
   */
  public int take(int levelTime, int armed) {
    if (queued == 0 || levelTime < readyAt) return 0;
    int amount = largestWithin(Math.min(queued, MAX_PER_TOUCH), armed & MAX_PER_TOUCH);
    if (amount == 0) return 0;
    queued -= amount;
    delivered += amount;
    readyAt = levelTime + REARM_MILLIS;
    return amount;
  }

  /** The largest {@code x <= limit} whose bits are all in {@code mask}. */
  static int largestWithin(int limit, int mask) {
    int x = 0;
    for (int bit = Integer.highestOneBit(Math.max(mask, 1)); bit > 0; bit >>>= 1)
      if ((mask & bit) != 0 && x + bit <= limit) x += bit;
    return x;
  }

  /** Puts back damage that {@link #take} handed out but could not be delivered. */
  public void giveBack(int mask) {
    if (mask <= 0) return;
    queued = Math.min(queued + mask, MAX_QUEUED);
    delivered -= mask;
    readyAt = Integer.MIN_VALUE;
  }

  /** Drops queued damage (the player died or left). */
  public void clear() {
    queued = 0;
  }

  /** A new level restarted the game's clock: the triggers are armed. */
  public void levelChanged() {
    readyAt = Integer.MIN_VALUE;
  }
}
