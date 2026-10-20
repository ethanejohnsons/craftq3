package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import java.util.Arrays;

/**
 * The spawned {@link HostTriggers} of the running level: found by inline model after the level
 * spawns, kept unlinked (so nothing ever touches them), and linked onto one client only for the
 * duration of that client's think ({@code GameServer.Listener#beforeClientThink} to {@code
 * afterClientThink}).
 */
final class HostTriggerPool {
  private static final Log LOG = Log.get(HostTriggerPool.class);

  private final int[] hurt = new int[ExternalDamage.TRIGGERS];
  private int heal = -1;
  private boolean ready;
  private final float[] origin = new float[3];

  /** Finds the triggers of models {@code firstModel ..}; false if the level has none. */
  boolean locate(GameAccess game, int firstModel) {
    ready = false;
    Arrays.fill(hurt, -1);
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

  /**
   * Links the hurt triggers of {@code hurtMask} (and the heal trigger) onto {@code client}, which
   * must be about to think; {@link #unlink} parks them again right after that think.
   */
  void link(GameAccess game, int client, int hurtMask, boolean healOnce) {
    if (!ready || (hurtMask == 0 && !healOnce)) return;
    game.playerOrigin(client, origin);
    for (int bit = 0; bit < ExternalDamage.TRIGGERS; bit++)
      if ((hurtMask & (1 << bit)) != 0) place(game, hurt[bit]);
    if (healOnce) place(game, heal);
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
