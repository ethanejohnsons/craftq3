package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.world.Contents;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minecraft mobs as qagame players, so the original weapons, splash, knockback and obituaries apply
 * to them unchanged. Each mob gets a host-driven client slot (a "puppet": connected locally, never
 * sending commands, so it never thinks or moves by itself) whose body the host places at the mob's
 * box every tick and hides from snapshots ({@code SVF_NOCLIENT}: Minecraft draws the mob).
 *
 * <p>Health without cheats: a client spawns with {@code handicap + 25} health and, never thinking,
 * keeps it. A mob at full health maps to {@link #QUAKE_HEALTH_AT_FULL}, so admitting a puppet with
 * the handicap matching the mob's remaining health makes the game kill it when Minecraft would.
 * Health the game takes away comes back as {@link MobDamage}; if Minecraft's health drifts from the
 * puppet's (other damage, healing) by more than {@link #RESYNC_TOLERANCE} the puppet is
 * re-admitted, at most every {@link #RESYNC_MILLIS}. Names reach the game as cleaned userinfo
 * ({@link ActorName}); renaming updates the userinfo of the existing slot.
 *
 * <p>Spawning a client can telefrag whatever stands on the spawn point, so the host first moves the
 * level's spawn point to a private parking spot ({@link SpawnSpot}).
 *
 * <p>Owned by the simulation thread.
 */
public final class ExternalActors {
  /** Spawn health of a puppet with handicap 100: a mob at full health. */
  public static final int QUAKE_HEALTH_AT_FULL = 125;

  /** {@code ClientSpawn}'s health above the maximum. */
  public static final int SPAWN_BONUS = 25;

  public static final int RESYNC_TOLERANCE = 15;
  public static final int RESYNC_MILLIS = 5000;

  /** A dead puppet stays (named, hidden) this long so clients can show its obituary. */
  public static final int OBITUARY_MILLIS = 1000;

  private static final Log LOG = Log.get(ExternalActors.class);

  /**
   * A mob to mirror.
   *
   * @param id stable identity (e.g. the entity's network id)
   * @param mins absolute Quake box of the mob's body
   * @param health its current health as a fraction of its maximum, in (0, 1]
   */
  public record Target(long id, String name, float[] mins, float[] maxs, float health) {
    public Target {
      Objects.requireNonNull(name, "name");
      mins = mins.clone();
      maxs = maxs.clone();
      for (int axis = 0; axis < 3; axis++)
        if (!(mins[axis] < maxs[axis])
            || !Float.isFinite(mins[axis])
            || !Float.isFinite(maxs[axis]))
          throw new IllegalArgumentException("Actor box needs finite mins below maxs");
      if (!(health > 0 && health <= 1)) throw new IllegalArgumentException("Health " + health);
    }

    @Override
    public float[] mins() {
      return mins.clone();
    }

    @Override
    public float[] maxs() {
      return maxs.clone();
    }

    /** Quake health matching this mob's. */
    public int quakeHealth() {
      return Math.max(1, (int) Math.ceil(health * QUAKE_HEALTH_AT_FULL));
    }
  }

  /** The level's spawn point, borrowed while a puppet spawns. */
  interface SpawnSpot {
    void park(int index);

    void restore();
  }

  private static final class Actor {
    final long id;
    int client = -1;
    String name;
    int health;
    int deadSince = Integer.MIN_VALUE;
    int admittedAt;
    final float[] mins = new float[3];
    final float[] maxs = new float[3];

    Actor(long id) {
      this.id = id;
    }
  }

  private final Map<Long, Actor> actors = new LinkedHashMap<>();
  private final int maxActors;
  private final float[] velocity = new float[3];

  public ExternalActors(int maxActors) {
    if (maxActors < 0 || maxActors > 63) throw new IllegalArgumentException("maxActors");
    this.maxActors = maxActors;
  }

  /** Mirrored mobs. */
  public int size() {
    return actors.size();
  }

  /** The client slot of mob {@code id}, or -1. */
  public int client(long id) {
    Actor a = actors.get(id);
    return a == null ? -1 : a.client;
  }

  /**
   * Admits, re-admits, moves, renames and removes puppets to match {@code targets} (the nearest
   * first; beyond the slot limit the rest are skipped). Call before the players' commands run so
   * their shots see this tick's bodies.
   */
  void sync(GameAccess game, SpawnSpot spot, List<Target> targets) {
    if (!game.ready()) return;
    int now = game.server().levelTime();
    Set<Long> wanted = new HashSet<>();
    for (Target t : targets) {
      if (wanted.size() >= maxActors) break;
      wanted.add(t.id());
    }
    for (Iterator<Actor> it = actors.values().iterator(); it.hasNext(); ) {
      Actor a = it.next();
      boolean showingObituary =
          a.deadSince != Integer.MIN_VALUE && now - a.deadSince < OBITUARY_MILLIS;
      if (!wanted.contains(a.id) && !showingObituary) {
        drop(game, a, "Minecraft mob gone");
        it.remove();
      }
    }
    int parked = 0;
    for (Target t : targets) {
      if (!wanted.contains(t.id())) continue;
      Actor a = actors.get(t.id());
      String name = ActorName.clean(t.name());
      if (a != null && a.deadSince != Integer.MIN_VALUE) {
        if (now - a.deadSince < OBITUARY_MILLIS) continue;
        drop(game, a, "respawn");
        a.client = -1;
      }
      if (a != null && a.client >= 0) {
        int drift = t.quakeHealth() - a.health;
        if (Math.abs(drift) > RESYNC_TOLERANCE && now - a.admittedAt >= RESYNC_MILLIS) {
          drop(game, a, "resync");
          a.client = -1;
        }
      }
      if (a == null) {
        if (actors.size() >= maxActors) continue;
        a = new Actor(t.id());
        actors.put(t.id(), a);
      }
      if (a.client < 0 && !admit(game, spot, a, t, name, parked++)) {
        actors.remove(t.id());
        continue;
      }
      if (!name.equals(a.name)) {
        a.name = name;
        game.server().clientCommand(a.client, "userinfo \"" + userinfo(name, t) + "\"", false);
      }
      place(game, a, t);
    }
  }

  private boolean admit(
      GameAccess game, SpawnSpot spot, Actor a, Target t, String name, int index) {
    GameServer server = game.server();
    spot.park(index);
    try {
      GameServer.Admission admission = server.connect(userinfo(name, t), "localhost", true, -1);
      if (!admission.accepted()) {
        LOG.warnOnce(
            "actor-admission", "A Minecraft mob was refused a slot: " + admission.denied());
        return false;
      }
      int c = admission.client();
      server.gamestateSent(c);
      server.userMove(c, List.of(UserCmd.idle(Math.max(1, server.serverTime()))));
      if (server.client(c).state() != dev.bluevista.craftq3.engine.game.ServerClient.State.ACTIVE) {
        server.drop(c, "actor did not enter");
        return false;
      }
      a.client = c;
      a.name = name;
      a.health = game.stat(c, Loadout.STAT_HEALTH);
      a.deadSince = Integer.MIN_VALUE;
      a.admittedAt = server.levelTime();
      return true;
    } finally {
      spot.restore();
    }
  }

  private static String userinfo(String name, Target t) {
    int handicap = Math.clamp(t.quakeHealth() - SPAWN_BONUS, 1, 100);
    return "\\name\\"
        + name
        + "\\model\\sarge\\headmodel\\sarge\\handicap\\"
        + handicap
        + "\\snaps\\20\\rate\\25000\\color1\\4\\color2\\5";
  }

  private void place(GameAccess game, Actor a, Target t) {
    float[] mins = t.mins;
    float[] maxs = t.maxs;
    // The origin sits at the box's horizontal centre, its feet 24 units below as a player's.
    float ox = (mins[0] + maxs[0]) * 0.5f;
    float oy = (mins[1] + maxs[1]) * 0.5f;
    float oz = mins[2] + 24;
    for (int axis = 0; axis < 3; axis++) {
      float o = axis == 0 ? ox : axis == 1 ? oy : oz;
      a.mins[axis] = mins[axis] - o;
      a.maxs[axis] = maxs[axis] - o;
    }
    GameEntities g = game.entities();
    if (a.deadSince == Integer.MIN_VALUE) game.placeClient(a.client, ox, oy, oz);
    g.setMins(a.client, a.mins);
    g.setMaxs(a.client, a.maxs);
    game.link(a.client);
  }

  /**
   * After a server frame: what the game did to each puppet (lost health, knockback, death). Also
   * clears knockback so puppets stay where the host puts them, and hides them from snapshots.
   */
  List<MobDamage> collect(GameAccess game) {
    if (!game.ready() || actors.isEmpty()) return List.of();
    List<MobDamage> out = new ArrayList<>();
    int now = game.server().levelTime();
    for (Actor a : actors.values()) {
      if (a.client < 0) continue;
      int health = game.stat(a.client, Loadout.STAT_HEALTH);
      game.playerVelocity(a.client, velocity);
      boolean pushed = velocity[0] != 0 || velocity[1] != 0 || velocity[2] != 0;
      int lost = a.deadSince == Integer.MIN_VALUE ? Math.max(0, a.health - health) : 0;
      boolean killed = a.deadSince == Integer.MIN_VALUE && health <= 0;
      if (lost > 0 || killed || pushed)
        out.add(
            new MobDamage(
                a.id,
                lost,
                velocity[0],
                velocity[1],
                velocity[2],
                game.persistant(a.client, GameAccess.PERS_ATTACKER),
                killed));
      if (pushed) game.setPlayerVelocity(a.client, 0, 0, 0);
      if (killed) a.deadSince = now;
      a.health = health;
    }
    hide(game);
    for (Actor a : actors.values()) if (a.client >= 0) game.discardReliable(a.client);
    return out;
  }

  /** Keeps puppets out of snapshots: Minecraft renders the mob itself. */
  void hide(GameAccess game) {
    GameEntities g = game.entities();
    for (Actor a : actors.values())
      if (a.client >= 0) g.setSvFlags(a.client, g.svFlags(a.client) | Contents.SVF_NOCLIENT);
  }

  /** Drops every puppet (leaving the bridge). */
  void clear(GameAccess game) {
    for (Actor a : actors.values()) drop(game, a, "bridge closed");
    actors.clear();
  }

  /** Forgets puppets whose slots a new level freed. */
  void levelChanged() {
    actors.clear();
  }

  private static void drop(GameAccess game, Actor a, String reason) {
    if (a.client < 0 || !game.ready()) return;
    game.server().drop(a.client, reason);
  }
}
