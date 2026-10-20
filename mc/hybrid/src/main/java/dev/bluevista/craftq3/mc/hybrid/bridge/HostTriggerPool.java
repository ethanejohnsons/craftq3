package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import java.util.Arrays;

/**
 * The spawned {@link HostTriggers} of the running level: found by inline model after the level
 * spawns, kept unlinked (so nothing ever touches them), and linked onto one client only for the
 * duration of that client's think ({@code GameServer.Listener#beforeClientThink} to {@code
 * afterClientThink}).
 *
 * <p>The triggers are shared by every client, and each re-arms on its own game-side timer once
 * touched ({@code trigger_hurt}'s {@code FRAMETIME} timestamp, {@code trigger_multiple}'s {@code
 * wait}), so the pool remembers when each one fires and reports which are {@linkplain #armed armed}
 * for the next think, whichever client it belongs to.
 */
final class HostTriggerPool {
  private static final Log LOG = Log.get(HostTriggerPool.class);

  /** The heal trigger is used at most this often ({@code wait 0.1}, cleared by the next frame). */
  static final int HEAL_REARM_MILLIS = 200;

  private final int[] hurt = new int[ExternalDamage.TRIGGERS];
  private final int[] hurtArmedAt = new int[ExternalDamage.TRIGGERS];
  private int heal = -1;
  private int healArmedAt = Integer.MIN_VALUE;
  private boolean ready;
  private final float[] origin = new float[3];

  /** Finds the triggers of models {@code firstModel ..}; false if the level has none. */
  boolean locate(GameAccess game, int firstModel) {
    ready = false;
    Arrays.fill(hurt, -1);
    // Fresh entities: every trigger is armed.
    Arrays.fill(hurtArmedAt, Integer.MIN_VALUE);
    healArmedAt = Integer.MIN_VALUE;
    heal = -1;
    if (firstModel <= 0 || !game.ready()) return false;
    GameEntities g = game.entities();
    for (int e = GameAccess.MAX_CLIENTS; e < g.count(); e++) {
      if (!g.bmodel(e)) continue;
      int model = g.modelIndex(e);
      int bit = model - firstModel;
      if (bit >= 0 && bit < ExternalDamage.TRIGGERS) hurt[bit] = e;
      else if (model == HostTriggers.healModel(firstModel)) heal = e;
    }
    for (int e : hurt) if (e < 0) return missing(firstModel);
    if (heal < 0) return missing(firstModel);
    for (int e : hurt) game.unlink(e);
    game.unlink(heal);
    ready = true;
    return true;
  }

  private boolean missing(int firstModel) {
    LOG.warnOnce(
        "host-triggers",
        "The level's host triggers (*" + firstModel + "..) did not spawn; external damage is off");
    return false;
  }

  boolean ready() {
    return ready;
  }

  void reset() {
    ready = false;
  }

  /** The hurt triggers (bit {@code i} for the one dealing {@code 1 << i}) armed at {@code time}. */
  int armed(int levelTime) {
    int mask = 0;
    for (int bit = 0; bit < ExternalDamage.TRIGGERS; bit++)
      if (levelTime >= hurtArmedAt[bit]) mask |= 1 << bit;
    return mask;
  }

  /** Whether the heal trigger is armed at {@code levelTime}. */
  boolean healArmed(int levelTime) {
    return levelTime >= healArmedAt;
  }

  /**
   * Links the hurt triggers of {@code hurtMask} (and the heal trigger) onto {@code client}, which
   * must be about to think at {@code levelTime}; {@link #unlink} parks them again right after that
   * think. They count as fired from then on.
   */
  void link(GameAccess game, int client, int hurtMask, boolean healOnce, int levelTime) {
    if (!ready || (hurtMask == 0 && !healOnce)) return;
    game.playerOrigin(client, origin);
    for (int bit = 0; bit < ExternalDamage.TRIGGERS; bit++)
      if ((hurtMask & (1 << bit)) != 0) {
        place(game, hurt[bit]);
        hurtArmedAt[bit] = levelTime + ExternalDamage.REARM_MILLIS;
      }
    if (healOnce) {
      place(game, heal);
      healArmedAt = levelTime + HEAL_REARM_MILLIS;
    }
  }

  /** Unlinks what {@link #link} linked. */
  void unlink(GameAccess game, int hurtMask, boolean healOnce) {
    if (!ready || !game.ready()) return;
    for (int bit = 0; bit < ExternalDamage.TRIGGERS; bit++)
      if ((hurtMask & (1 << bit)) != 0) game.unlink(hurt[bit]);
    if (healOnce) game.unlink(heal);
  }

  /** True when any host trigger is linked (they must be only during the player's think). */
  boolean anyLinked(GameAccess game) {
    if (!ready || !game.ready()) return false;
    GameEntities g = game.entities();
    for (int e : hurt) if (g.linked(e)) return true;
    return g.linked(heal);
  }

  /** The entity of hurt trigger {@code bit}, or -1 before the level's triggers are located. */
  int hurtEntity(int bit) {
    return ready ? hurt[bit] : -1;
  }

  private void place(GameAccess game, int e) {
    game.setEntityOrigin(e, origin[0], origin[1], origin[2]);
    game.link(e);
  }
}
