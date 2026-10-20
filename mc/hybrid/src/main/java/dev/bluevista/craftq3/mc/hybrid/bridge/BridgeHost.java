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
 * Drives the crossover rules on a running {@link GameServer}, for all of its clients, on its
 * simulation thread (the Minecraft server thread in bridge mode). One tick:
 *
 * <ol>
 *   <li>{@link #syncActors} with the nearby mobs;
 *   <li>the players' commands, however they reach the server ({@code GameServer.userMove} directly,
 *       or {@code Protocol68Host} for a network or loopback connection): through the server's
 *       per-think hooks this host delivers each {@link Client}'s queued {@linkplain Client#damage
 *       incoming damage} and loadout restoration by linking the {@link HostTriggers} onto that
 *       client for exactly its own think;
 *   <li>{@code GameServer.frame};
 *   <li>{@link #afterFrame}, which returns the {@link MobDamage} the game dealt.
 * </ol>
 *
 * Clients are addressed by slot ({@link #client}); each handle keeps its own damage queue, pending
 * loadout and restoration, and goes inert when its slot is dropped. The triggers are shared: one
 * client's touch disarms them for the rest of the {@code FRAMETIME} window, so damage several
 * clients take at once arrives over consecutive windows as needed, never at another client.
 *
 * <p>Works on the {@link SyntheticMapSource} bridge level and on any map loaded through {@link
 * HostTriggerMapSource}; on other maps damage and health restoration are unavailable and say so.
 * Register it as a {@link GameServer.Listener}: it finds the triggers when a level spawns and links
 * them around the clients' thinks.
 */
public final class BridgeHost implements GameServer.Listener {
  private static final Log LOG = Log.get(BridgeHost.class);

  /** The largest impulse added in one call, units per second per axis. */
  public static final float MAX_IMPULSE = 4096;

  /** Minecraft damage to Quake points for incoming hits (one heart = ten points). */
  public static final float QUAKE_PER_MINECRAFT_POINT = 5;

  private final GameAccess game;
  private final HostTriggerPool triggers = new HostTriggerPool();
  private final ExternalActors actors;
  private final LoadoutPolicy policy;
  private final Client[] clients = new Client[GameAccess.MAX_CLIENTS];
  private int spawnSpot = -1;
  private final float[] spawnOrigin = new float[3];
  private final float[] scratch = new float[3];

  // The think in progress.
  private Client thinking;
  private int linkedMask;
  private boolean linkedHeal;
  private int healthBefore;

  /** Health restoration in progress. */
  private static final class Restore {
    final Loadout loadout;
    int heals;
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

  /**
   * The handle of client {@code slot} (created on first use), through which its damage, impulses
   * and loadout go. It stays valid while the slot is connected; once the slot is dropped it is
   * inert and a later call returns a new handle for whoever takes the slot.
   */
  public Client client(int slot) {
    Objects.checkIndex(slot, clients.length);
    Client c = clients[slot];
    if (c == null) clients[slot] = c = new Client(slot);
    return c;
  }

  @Override
  public void levelSpawned(GameServer server) {
    actors.levelChanged();
    newEntities(server);
  }

  /** {@code map_restart} spawned the level's entities again: find the triggers anew. */
  @Override
  public void levelRestarted(GameServer server) {
    newEntities(server);
  }

  private void newEntities(GameServer server) {
    for (Client c : clients) if (c != null) c.levelChanged();
    thinking = null;
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

  @Override
  public void clientDropped(GameServer server, int client, String reason) {
    if (server != game.server() || client < 0 || client >= clients.length) return;
    Client c = clients[client];
    if (c == null) return;
    c.attached = false;
    clients[client] = null;
  }

  /** True when the level has its host triggers (damage and exact health restoration work). */
  public boolean triggersReady() {
    return triggers.ready();
  }

  // ---- the tick ------------------------------------------------------------------------------

  /** Mirrors {@code targets} (nearest first) as puppets; call before the players' commands. */
  public void syncActors(List<ExternalActors.Target> targets) {
    if (spawnSpot < 0) findSpawnSpot();
    actors.sync(game, spot(), targets);
  }

  /**
   * Before every client think: when the client has a handle, links the host triggers due now
   * (queued damage, or one step of health restoration) onto it, so exactly this think's {@code
   * G_TouchTriggers} touches them. Other clients' thinks never see them.
   */
  @Override
  public void beforeClientThink(GameServer server, int client, UserCmd command) {
    thinking = null;
    linkedMask = 0;
    linkedHeal = false;
    if (server != game.server() || client < 0 || client >= clients.length) return;
    Client c = clients[client];
    if (c == null || !game.ready() || !triggers.ready()) return;
    if (!game.alive(client)) {
      c.dropPending();
      return;
    }
    int time = server.levelTime();
    if (c.pendingLoadout != null) c.beginRestore();
    Restore restore = c.restore;
    boolean heal = false;
    if (restore != null && restore.heals > 0 && triggers.healArmed(time)) {
      heal = true;
      restore.heals--;
    }
    if (restore != null && restore.heals == 0 && !heal && !restore.damageQueued) {
      int extra = game.stat(client, Loadout.STAT_HEALTH) - restore.target;
      if (extra > 0) c.damage.add(extra);
      restore.damageQueued = true;
    }
    int mask = heal ? 0 : c.damage.take(time, triggers.armed(time));
    healthBefore = game.stat(client, Loadout.STAT_HEALTH);
    triggers.link(game, client, mask, heal, time);
    linkedMask = mask;
    linkedHeal = heal;
    thinking = c;
  }

  /** After every client think: parks the triggers linked for that think. */
  @Override
  public void afterClientThink(GameServer server, int client) {
    Client c = thinking;
    if (c == null || c.slot != client) return;
    thinking = null;
    int mask = linkedMask;
    triggers.unlink(game, mask, linkedHeal);
    linkedMask = 0;
    linkedHeal = false;
    if (!game.ready() || !c.attached) return;
    if (mask != 0 && game.stat(client, Loadout.STAT_HEALTH) == healthBefore)
      LOG.warnOnce("host-damage-missed", "A host damage touch did not reach the player");
    Restore restore = c.restore;
    if (restore != null && restore.damageQueued && c.damage.queued() == 0 && mask == 0)
      c.finishRestore();
  }

  /**
   * After {@code GameServer.frame}: the damage the game dealt to mirrored mobs. Also drops damage
   * queued for dead players.
   */
  public List<MobDamage> afterFrame() {
    if (!game.ready()) return List.of();
    for (Client c : clients)
      if (c != null && isActive(c.slot) && !game.alive(c.slot)) c.dropPending();
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

  // ---- one client ------------------------------------------------------------------------------

  /**
   * One client slot's incoming damage, impulses and loadout. Simulation thread only; inert once its
   * slot was dropped ({@link #attached}).
   */
  public final class Client {
    private final int slot;
    private boolean attached = true;
    private final ExternalDamage damage = new ExternalDamage();
    private Loadout pendingLoadout;
    private Restore restore;
    private int heldDamage;

    private Client(int slot) {
      this.slot = slot;
    }

    public int slot() {
      return slot;
    }

    /** False once the slot was dropped: the handle then does nothing. */
    public boolean attached() {
      return attached;
    }

    /** Queues Minecraft damage, converted at {@link #QUAKE_PER_MINECRAFT_POINT}. */
    public void minecraftDamage(float minecraftPoints) {
      if (!(minecraftPoints > 0)) return;
      damage(Math.max(1, Math.round(minecraftPoints * QUAKE_PER_MINECRAFT_POINT)));
    }

    /** Queues {@code quakePoints} of damage; it is dropped if the player dies. */
    public void damage(int quakePoints) {
      if (quakePoints < 1 || !attached) return;
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
     * Adds a velocity change (Quake units per second) to the living player, e.g. Minecraft
     * knockback converted with {@code CoordinateTransform.velocityToQuake}; Quake movement then
     * applies gravity, friction and collision.
     */
    public void impulse(float x, float y, float z) {
      if (!live()) return;
      game.playerVelocity(slot, scratch);
      game.setPlayerVelocity(
          slot,
          scratch[0] + Math.clamp(x, -MAX_IMPULSE, MAX_IMPULSE),
          scratch[1] + Math.clamp(y, -MAX_IMPULSE, MAX_IMPULSE),
          scratch[2] + Math.clamp(z, -MAX_IMPULSE, MAX_IMPULSE));
    }

    /**
     * Restores {@code loadout} (filtered by the policy) into the player once it has spawned: the
     * inventory is written into its playerState and health is reached with the game's own
     * mega-health and hurt rules. Replaces any restoration not yet started.
     */
    public void restoreLoadout(Loadout loadout) {
      Objects.requireNonNull(loadout);
      if (attached) pendingLoadout = policy.filter(loadout);
    }

    /** True while a loadout is waiting or being restored. */
    public boolean restoring() {
      return pendingLoadout != null || restore != null;
    }

    /** The living player's loadout, or empty when dead, spectating, dropped or not in the game. */
    public Optional<Loadout> captureLoadout() {
      if (!live()) return Optional.empty();
      try {
        GameEntities g = game.entities();
        return Optional.of(
            policy.filter(
                Loadout.capture(g.canonicalPlayerState(slot), game.server().levelTime())));
      } catch (IllegalArgumentException invalid) {
        LOG.warn("Cannot capture the loadout: " + invalid.getMessage());
        return Optional.empty();
      }
    }

    private boolean live() {
      return attached && game.ready() && isActive(slot) && game.alive(slot);
    }

    private void beginRestore() {
      Loadout loadout = pendingLoadout;
      pendingLoadout = null;
      restore = new Restore(loadout);
      int ps = game.playerState(slot);
      policy.prepareForHealth(game.memory(), ps);
      int current = LoadoutPolicy.health(game.memory(), ps);
      LoadoutPolicy.HealthPlan plan =
          LoadoutPolicy.healthPlan(
              current, LoadoutPolicy.maxHealth(game.memory(), ps), loadout.health());
      restore.heals = plan.heals();
      restore.target = plan.reached();
      // Damage already queued waits until the restored health is in place.
      heldDamage += damage.queued();
      damage.clear();
    }

    private void finishRestore() {
      Restore done = restore;
      restore = null;
      int health = game.stat(slot, Loadout.STAT_HEALTH);
      if (health != done.target)
        LOG.warn("Loadout health restored to " + health + " instead of " + done.target);
      policy.writeInventory(
          game.memory(), game.playerState(slot), done.loadout, game.server().levelTime());
      if (heldDamage > 0) damage.add(heldDamage);
      heldDamage = 0;
    }

    private void dropPending() {
      damage.clear();
      heldDamage = 0;
    }

    /** A new level: queued damage and a restoration in progress are gone; a pending one waits. */
    private void levelChanged() {
      damage.levelChanged();
      dropPending();
      restore = null;
    }
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
