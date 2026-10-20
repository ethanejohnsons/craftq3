package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Drives the crossover rules on a running {@link GameServer}, on its simulation thread (the
 * Minecraft server thread in bridge mode). One tick:
 *
 * <ol>
 *   <li>{@link #syncActors} with the nearby mobs;
 *   <li>the player's commands through {@link #userMove} (instead of {@code GameServer.userMove}),
 *       which delivers queued {@linkplain #damage incoming damage} and loadout restoration by
 *       linking the {@link HostTriggers} onto the player for exactly that think;
 *   <li>{@code GameServer.frame};
 *   <li>{@link #afterFrame}, which returns the {@link MobDamage} the game dealt.
 * </ol>
 *
 * Works on the {@link SyntheticMapSource} bridge level and on any map loaded through {@link
 * HostTriggerMapSource}; on other maps damage and health restoration are unavailable and say so.
 * Register it as a {@link GameServer.Listener} so it finds the triggers when a level spawns.
 */
public final class BridgeHost implements GameServer.Listener {
  private static final Log LOG = Log.get(BridgeHost.class);

  /** The largest impulse added in one call, units per second per axis. */
  public static final float MAX_IMPULSE = 4096;

  /** Minecraft damage to Quake points for incoming hits (one heart = ten points). */
  public static final float QUAKE_PER_MINECRAFT_POINT = 5;

  private final GameAccess game;
  private final HostTriggerPool triggers = new HostTriggerPool();
  private final ExternalDamage damage = new ExternalDamage();
  private final ExternalActors actors;
  private final LoadoutPolicy policy;
  private int player = -1;
  private int spawnSpot = -1;
  private final float[] spawnOrigin = new float[3];

  private Loadout pendingLoadout;
  private Restore restore;
  private int heldDamage;
  private final float[] scratch = new float[3];

  /** Health restoration in progress. */
  private static final class Restore {
    final Loadout loadout;
    int heals;
    int nextHealTime;
    boolean damageQueued;
    int target;

    Restore(Loadout loadout) {
      this.loadout = loadout;
    }
  }

  public BridgeHost(GameServer server, LoadoutPolicy policy, int maxActors) {
    this.game = new GameAccess(server);
    this.policy = Objects.requireNonNull(policy);
    this.actors = new ExternalActors(maxActors);
  }

  public GameServer server() {
    return game.server();
  }

  public ExternalActors actors() {
    return actors;
  }

  /** The client whose damage and loadout this host handles. */
  public void setPlayer(int client) {
    player = client;
  }

  public int player() {
    return player;
  }

  @Override
  public void levelSpawned(GameServer server) {
    damage.levelChanged();
    damage.clear();
    actors.levelChanged();
    restore = null;
    triggers.reset();
    spawnSpot = -1;
    Optional<GameMap> map = server.currentMap();
    if (map.isEmpty()) return;
    int first =
        SyntheticMapSource.MAP_NAME.equals(map.get().name())
            ? SyntheticMapSource.FIRST_HOST_MODEL
            : HostTriggerMapSource.firstHostModel(map.get());
    triggers.locate(game, first);
    findSpawnSpot();
  }

  @Override
  public void levelEnded(GameServer server, String reason) {
    triggers.reset();
    actors.levelChanged();
    spawnSpot = -1;
  }

  /** True when the level has its host triggers (damage and exact health restoration work). */
  public boolean triggersReady() {
    return triggers.ready();
  }

  // ---- incoming damage and impulses ------------------------------------------------------------

  /** Queues Minecraft damage for the player, converted at {@link #QUAKE_PER_MINECRAFT_POINT}. */
  public void minecraftDamage(float minecraftPoints) {
    if (!(minecraftPoints > 0)) return;
    damage(Math.max(1, Math.round(minecraftPoints * QUAKE_PER_MINECRAFT_POINT)));
  }

  /** Queues {@code quakePoints} of damage for the player; it is dropped if the player dies. */
  public void damage(int quakePoints) {
    if (quakePoints < 1) return;
    if (!triggers.ready()) {
      LOG.warnOnce("no-host-damage", "This level has no host triggers; Minecraft damage is lost");
      return;
    }
    if (restore != null) heldDamage += quakePoints;
    else damage.add(quakePoints);
  }

  public int queuedDamage() {
    return damage.queued() + heldDamage;
  }

  /**
   * Adds a velocity change (Quake units per second) to the living player, e.g. Minecraft knockback
   * converted with {@code CoordinateTransform.velocityToQuake}; Quake movement then applies
   * gravity, friction and collision.
   */
  public void impulse(float x, float y, float z) {
    if (player < 0 || !game.ready() || !isActive(player) || !game.alive(player)) return;
    game.playerVelocity(player, scratch);
    game.setPlayerVelocity(
        player,
        scratch[0] + Math.clamp(x, -MAX_IMPULSE, MAX_IMPULSE),
        scratch[1] + Math.clamp(y, -MAX_IMPULSE, MAX_IMPULSE),
        scratch[2] + Math.clamp(z, -MAX_IMPULSE, MAX_IMPULSE));
  }

  // ---- loadouts -------------------------------------------------------------------------------

  /**
   * Restores {@code loadout} (filtered by the policy) into the player once it has spawned: the
   * inventory is written into its playerState and health is reached with the game's own mega-health
   * and hurt rules. Replaces any restoration not yet started.
   */
  public void restoreLoadout(Loadout loadout) {
    pendingLoadout = policy.filter(Objects.requireNonNull(loadout));
  }

  /** True while a loadout is waiting or being restored. */
  public boolean restoring() {
    return pendingLoadout != null || restore != null;
  }

  /** The living player's loadout, or empty when dead, spectating or not in the game. */
  public Optional<Loadout> captureLoadout() {
    if (player < 0 || !game.ready() || !isActive(player) || !game.alive(player))
      return Optional.empty();
    try {
      GameEntities g = game.entities();
      return Optional.of(
          policy.filter(
              Loadout.capture(g.canonicalPlayerState(player), game.server().levelTime())));
    } catch (IllegalArgumentException invalid) {
      LOG.warn("Cannot capture the loadout: " + invalid.getMessage());
      return Optional.empty();
    }
  }

  // ---- the tick ------------------------------------------------------------------------------

  /** Mirrors {@code targets} (nearest first) as puppets; call before the player's commands. */
  public void syncActors(List<ExternalActors.Target> targets) {
    if (spawnSpot < 0) findSpawnSpot();
    actors.sync(game, spot(), targets);
  }

  /**
   * Runs {@code client}'s commands ({@code GameServer.userMove}), with any due host triggers linked
   * onto the player for exactly this think.
   */
  public void userMove(int client, List<UserCmd> commands) {
    GameServer server = game.server();
    if (client != player || !game.ready() || !triggers.ready() || commands.isEmpty()) {
      server.userMove(client, commands);
      return;
    }
    ServerClient slot = server.client(client);
    boolean thinks =
        slot.state() == ServerClient.State.ACTIVE
            && commands.getLast().serverTime() > slot.lastUsercmd().serverTime();
    if (!thinks || !game.alive(client)) {
      if (slot.state() == ServerClient.State.ACTIVE && !game.alive(client)) dropPending();
      server.userMove(client, commands);
      return;
    }
    int time = server.levelTime();
    boolean heal = false;
    if (pendingLoadout != null) beginRestore();
    if (restore != null && restore.heals > 0 && time >= restore.nextHealTime) {
      heal = true;
      restore.heals--;
      restore.nextHealTime = time + 200;
    }
    if (restore != null && restore.heals == 0 && !heal && !restore.damageQueued) {
      int current = game.stat(client, Loadout.STAT_HEALTH);
      int extra = current - restore.target;
      if (extra > 0) damage.add(extra);
      restore.damageQueued = true;
    }
    int mask = heal ? 0 : damage.take(time);
    int before = game.stat(client, Loadout.STAT_HEALTH);
    triggers.around(game, client, mask, heal, () -> server.userMove(client, commands));
    if (mask != 0 && game.ready() && game.stat(client, Loadout.STAT_HEALTH) == before)
      LOG.warnOnce("host-damage-missed", "A host damage touch did not reach the player");
    if (restore != null && restore.damageQueued && damage.queued() == 0 && mask == 0)
      finishRestore();
  }

  private void beginRestore() {
    Loadout loadout = pendingLoadout;
    pendingLoadout = null;
    restore = new Restore(loadout);
    int ps = game.playerState(player);
    policy.prepareForHealth(game.memory(), ps);
    int current = LoadoutPolicy.health(game.memory(), ps);
    LoadoutPolicy.HealthPlan plan =
        LoadoutPolicy.healthPlan(
            current, LoadoutPolicy.maxHealth(game.memory(), ps), loadout.health());
    restore.heals = plan.heals();
    restore.target = plan.reached();
    restore.nextHealTime = Integer.MIN_VALUE;
    // Damage already queued waits until the restored health is in place.
    heldDamage += damage.queued();
    damage.clear();
  }

  private void finishRestore() {
    Restore done = restore;
    restore = null;
    int health = game.stat(player, Loadout.STAT_HEALTH);
    if (health != done.target)
      LOG.warn("Loadout health restored to " + health + " instead of " + done.target);
    policy.writeInventory(
        game.memory(), game.playerState(player), done.loadout, game.server().levelTime());
    if (heldDamage > 0) damage.add(heldDamage);
    heldDamage = 0;
  }

  private void dropPending() {
    damage.clear();
    heldDamage = 0;
  }

  /**
   * After {@code GameServer.frame}: the damage the game dealt to mirrored mobs. Also drops damage
   * queued for a dead player.
   */
  public List<MobDamage> afterFrame() {
    if (!game.ready()) return List.of();
    if (player >= 0 && isActive(player) && !game.alive(player)) dropPending();
    return actors.collect(game);
  }

  /** Removes every puppet (leaving the bridge). */
  public void close() {
    if (game.ready()) actors.clear(game);
  }

  private boolean isActive(int client) {
    return client < game.server().maxClients()
        && game.server().client(client).state() == ServerClient.State.ACTIVE;
  }

  // ---- spawn spot ----------------------------------------------------------------------------

  private void findSpawnSpot() {
    spawnSpot = -1;
    if (!game.ready()) return;
    var world = SyntheticMapSource.MAP_NAME.equals(game.server().map().name());
    if (!world) return;
    float[] expected = new float[3];
    var bsp = game.server().map().bsp();
    for (var e : bsp.entities())
      if ("info_player_deathmatch".equals(e.classname().orElse(""))) {
        String[] parts = e.get("origin").orElse("").trim().split("\\s+");
        if (parts.length != 3) return;
        for (int i = 0; i < 3; i++) expected[i] = Float.parseFloat(parts[i]);
        break;
      }
    GameEntities g = game.entities();
    float[] origin = new float[3];
    for (int e = GameAccess.MAX_CLIENTS; e < g.count(); e++) {
      if (g.bmodel(e) || g.stateNumber(e) != e) continue;
      g.stateOrigin(e, origin);
      if (origin[0] == expected[0] && origin[1] == expected[1] && origin[2] == expected[2]) {
        spawnSpot = e;
        System.arraycopy(expected, 0, spawnOrigin, 0, 3);
        return;
      }
    }
  }

  private ExternalActors.SpawnSpot spot() {
    return new ExternalActors.SpawnSpot() {
      @Override
      public void park(int index) {
        if (spawnSpot < 0) return;
        float[] park = {
          (index % 32) * 128f - 2048, (index / 32) * 128f, SyntheticMapSource.PARK_Z + 512
        };
        game.setStateOrigin(spawnSpot, park);
      }

      @Override
      public void restore() {
        if (spawnSpot >= 0) game.setStateOrigin(spawnSpot, spawnOrigin);
      }
    };
  }

  /** Moves the bridge's spawn point (respawns happen there), e.g. to the player's latest spot. */
  public void moveSpawn(float x, float y, float z) {
    if (spawnSpot < 0) return;
    spawnOrigin[0] = x;
    spawnOrigin[1] = y;
    spawnOrigin[2] = z;
    game.setStateOrigin(spawnSpot, spawnOrigin);
  }
}
