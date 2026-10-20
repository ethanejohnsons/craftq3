package dev.bluevista.craftq3.mc.fabric.bridge;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.net.Protocol68Host;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles;
import dev.bluevista.craftq3.mc.fabric.net.PayloadServerTransport;
import dev.bluevista.craftq3.mc.fabric.net.Q3DatagramPayload;
import dev.bluevista.craftq3.mc.fabric.server.Q3Content;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.bridge.BridgeHost;
import dev.bluevista.craftq3.mc.hybrid.bridge.ExternalActors;
import dev.bluevista.craftq3.mc.hybrid.bridge.Loadout;
import dev.bluevista.craftq3.mc.hybrid.bridge.LoadoutPolicy;
import dev.bluevista.craftq3.mc.hybrid.bridge.MobDamage;
import dev.bluevista.craftq3.mc.hybrid.bridge.PickupPlacement;
import dev.bluevista.craftq3.mc.hybrid.bridge.SyntheticMapSource;
import dev.bluevista.craftq3.mc.hybrid.terrain.GridCollisionSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One level's bridge: the original qagame running {@link SyntheticMapSource#MAP_NAME} over the
 * level's live blocks ({@link GridCollisionSource} over {@link LevelTerrainCells}), on the
 * Minecraft server thread, one game frame per server tick ({@code sv_fps 20}).
 *
 * <ul>
 *   <li><b>Clients</b>: every bridged player's own Quake session connects through a {@link
 *       Protocol68Host} on a {@link PayloadServerTransport} (protocol-68 datagrams inside Minecraft
 *       custom payloads), the same path for the integrated and a dedicated server. Each connected
 *       slot gets a {@link BridgeHost} for its incoming damage and loadout.
 *   <li><b>Mobs</b>: the living entities nearest the bridged players are mirrored as puppets by a
 *       separate actor host; the {@link MobDamage} the game deals comes back as Minecraft damage
 *       attributed to the shooter ({@code craftq3:quake}), with the Quake knockback as a push.
 *   <li><b>Avatars</b>: after each frame every bridged Minecraft player is put at its Quake body
 *       (feet, aim, hull), so mobs, projectiles and other players see it where Quake has it.
 * </ul>
 *
 * Quake coordinates are anchored at the first player's block; players more than {@link
 * #MAX_DISTANCE} blocks from it cannot join (the bridge level's extent). The game ends with its
 * last player.
 */
public final class BridgeGame implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(BridgeGame.class);

  /** Mobs mirrored at once. */
  public static final int MAX_ACTORS = 31;

  /** Bridged players at once. */
  public static final int MAX_PLAYERS = 64 - MAX_ACTORS;

  /** How far around each bridged player mobs are mirrored, in blocks. */
  public static final double SEARCH_RADIUS = 48;

  /** How far from the anchor a player may join, in blocks. */
  public static final double MAX_DISTANCE = 900;

  /** Pickups this close to the anchor are spawned, in blocks. */
  public static final double PICKUP_RADIUS = 256;

  /** Server milliseconds per Minecraft tick: one {@code sv_fps 20} frame. */
  public static final int TICK_MILLIS = 50;

  /** {@code ENTITYNUM_NONE}: no ground entity. */
  private static final int ENTITYNUM_NONE = 1023;

  /** {@code PM_DEAD}. */
  private static final int PM_DEAD = 3;

  private final ServerLevel level;
  private final Q3Content content;
  private final CoordinateTransform transform;
  private final GridCollisionSource terrain;
  private final SyntheticMapSource maps = new SyntheticMapSource();
  private final ThreadOwner owner = new ThreadOwner("bridge server");
  private final GameServer server;
  private final Protocol68Host host;
  private final PayloadServerTransport transport;
  private final BridgeHost actorHost;
  private final Map<UUID, BridgeSession> sessions = new LinkedHashMap<>();
  private final BlockPos anchor;
  private long now = 1000;
  private long ticks;
  private boolean closed;

  // Per tick, reused.
  private final float[] origin = new float[3];
  private final float[] angles = new float[3];
  private final float[] velocity = new float[3];
  private final float[] mins = new float[3];
  private final float[] maxs = new float[3];
  private final double[] push = new double[3];
  private final List<ExternalActors.Target> targets = new ArrayList<>();
  private final Map<Integer, LivingEntity> mirrored = new HashMap<>();
  private long lastTickNanos;
  private long tickNanosTotal;
  private long tickCount;

  private BridgeGame(ServerLevel level, Q3Content content, BlockPos anchor) throws IOException {
    this.level = level;
    this.content = content;
    this.anchor = anchor.immutable();
    this.transform = CoordinateTransform.at(anchor.getX(), anchor.getY(), anchor.getZ());
    this.terrain = new GridCollisionSource(new LevelTerrainCells(level), transform, true);
    owner.claim();
    this.transport = new PayloadServerTransport(this::sendDatagram);
    HomeStore home = openHome(level, content.game());
    var view = content.searchPath().view();
    server =
        GameServer.builder()
            .owner(owner)
            .output(line -> LOGGER.info("[Q3 bridge] {}", line.stripTrailing()))
            .files(new FileViewAssets(view))
            .vmFiles(new FsVmFiles(view, home, null))
            .maps(maps)
            .clocks(() -> now, java.time.Clock.systemDefaultZone())
            .seed(level.getSeed() ^ System.nanoTime())
            .build();
    var cvars = server.cvars();
    cvars.set("sv_maxclients", "64");
    cvars.set("sv_pure", "0");
    cvars.set("dedicated", "0");
    cvars.set("g_gametype", "0");
    cvars.set("fraglimit", "0");
    cvars.set("timelimit", "0");
    cvars.set("g_doWarmup", "0");
    cvars.set("g_inactivity", "0");
    cvars.set("bot_enable", "0");
    cvars.set("sv_fps", "20");
    cvars.set("sv_hostname", "Minecraft bridge");
    if (!content.game().equalsIgnoreCase("baseq3")) cvars.set("fs_game", content.game());
    host = new Protocol68Host(server, name -> Optional.empty(), new Random());
    host.addTransport(transport, false);
    actorHost = new BridgeHost(server, LoadoutPolicy.BASE_GAME, MAX_ACTORS);
    server.addListener(actorHost);
    server.addListener(
        new GameServer.Listener() {
          @Override
          public void beforeClientThink(GameServer source, int client, UserCmd command) {
            // A respawn (and the first spawn) happens at that player's entry point.
            BridgeSession session = sessionOfSlot(client);
            if (session != null) moveSpawn(session);
          }
        });
  }

  /**
   * Starts a bridge on {@code level} anchored at {@code anchor}, with the level's pickups for the
   * game. Server thread; blocks for the qagame load and level spawn.
   *
   * @throws IOException when the game module or level cannot start
   */
  public static BridgeGame start(ServerLevel level, Q3Content content, BlockPos anchor, float yaw)
      throws IOException {
    BridgeGame game = new BridgeGame(level, content, anchor);
    try {
      game.spawnLevel(yaw);
      return game;
    } catch (IOException | RuntimeException failure) {
      game.close();
      throw failure;
    }
  }

  private void spawnLevel(float yaw) throws IOException {
    List<SyntheticMapSource.ItemSpawn> items = new ArrayList<>();
    try {
      for (PickupPlacement p :
          PickupPlacement.near(
              BridgeData.pickups(level, content.game()),
              anchor.getX() + 0.5,
              anchor.getZ() + 0.5,
              PICKUP_RADIUS,
              PickupPlacement.MAX_PLACEMENTS)) {
        Vec3f q = p.quakeOrigin(transform);
        items.add(
            new SyntheticMapSource.ItemSpawn(
                p.classname(), new Vec3f(q.x(), q.y(), q.z() + ITEM_LIFT)));
      }
    } catch (IOException malformed) {
      LOGGER.warn(
          "CraftQ3: this level's pickup placements are unreadable: {}", malformed.getMessage());
    }
    Vec3f spawn = spawnPoint(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
    maps.prepare(
        new SyntheticMapSource.World(
            terrain,
            spawn,
            CoordinateTransform.quakeYaw(yaw),
            items,
            "Minecraft: " + level.dimension().identifier()));
    now += TICK_MILLIS;
    if (!server.spawn(SyntheticMapSource.MAP_NAME, true))
      throw new IOException("The bridge level did not start: " + server.dropReason());
    LOGGER.info(
        "CraftQ3: bridge started in {} at {} with {} pickups",
        level.dimension(),
        anchor,
        items.size());
  }

  /** Units an item spawns above its placement; the game drops it to the floor. */
  private static final float ITEM_LIFT = 16;

  /** The Quake spawn point for feet at the Minecraft position (a spot is 24 units above feet). */
  private Vec3f spawnPoint(double x, double y, double z) {
    return new Vec3f(transform.quakeX(x), transform.quakeY(z), transform.quakeZ(y) + 25);
  }

  private void moveSpawn(BridgeSession session) {
    Vec3f spot = spawnPoint(session.entry().x, session.entry().y, session.entry().z);
    actorHost.moveSpawn(Math.round(spot.x()), Math.round(spot.y()), Math.round(spot.z()));
  }

  private static HomeStore openHome(ServerLevel level, String game) {
    Path root =
        level.getServer().getWorldPath(LevelResource.ROOT).resolve("craftq3").resolve("home");
    try {
      return HomeStore.open(root.resolve(game));
    } catch (IOException | RuntimeException failure) {
      LOGGER.warn("CraftQ3: bridge home {} unusable: {}", root, failure.getMessage());
      return null;
    }
  }

  // ---- accessors ------------------------------------------------------------------------------

  public ServerLevel level() {
    return level;
  }

  public Q3Content content() {
    return content;
  }

  public CoordinateTransform transform() {
    return transform;
  }

  public BlockPos anchor() {
    return anchor;
  }

  public GameServer server() {
    return server;
  }

  public BridgeHost actorHost() {
    return actorHost;
  }

  public Collection<BridgeSession> sessions() {
    return sessions.values();
  }

  public boolean running() {
    return !closed && server.state() == GameServer.State.RUNNING;
  }

  /** Average server-thread time of a bridge tick so far, in nanoseconds. */
  public long averageTickNanos() {
    return tickCount == 0 ? 0 : tickNanosTotal / tickCount;
  }

  /** Living entities offered to the game as puppets in the last tick (nearest first, capped). */
  public int targetCount() {
    return targets.size();
  }

  /** The time of the last bridge tick, in nanoseconds. */
  public long lastTickNanos() {
    return lastTickNanos;
  }

  /** Why {@code position} cannot join, or empty. */
  public Optional<String> refusal(double x, double z) {
    if (sessions.size() >= MAX_PLAYERS) return Optional.of("the bridge is full");
    double dx = x - anchor.getX(), dz = z - anchor.getZ();
    if (dx * dx + dz * dz > MAX_DISTANCE * MAX_DISTANCE)
      return Optional.of(
          "you are more than " + (int) MAX_DISTANCE + " blocks from this level's bridge");
    return Optional.empty();
  }

  // ---- sessions -------------------------------------------------------------------------------

  /** Adds {@code player}; its Quake session then connects through the payload transport. */
  public BridgeSession join(ServerPlayer player, Optional<Loadout> loadout) {
    if (sessions.containsKey(player.getUUID())) return sessions.get(player.getUUID());
    transport.open(player.getUUID());
    BridgeSession session =
        new BridgeSession(this, player, transport.addressText(player.getUUID()), loadout, ticks);
    sessions.put(player.getUUID(), session);
    return session;
  }

  /**
   * Removes {@code session}: its loadout is returned (empty when dead or never connected), its slot
   * dropped and its address closed.
   */
  public Optional<Loadout> leave(BridgeSession session, String reason) {
    if (sessions.remove(session.id()) == null) return Optional.empty();
    Optional<Loadout> loadout = Optional.empty();
    if (session.slot() >= 0 && running()) {
      BridgeHost h = session.host();
      if (h != null) {
        loadout = h.captureLoadout();
        server.removeListener(h);
      }
      server.drop(session.slot(), reason);
    }
    // Flush the drop's disconnect before the address goes away.
    if (running()) host.frame(now);
    transport.close(session.id());
    session.unbind();
    session.end();
    return loadout;
  }

  /** A datagram {@code player} sent. */
  public void receive(ServerPlayer player, byte[] data) {
    if (sessions.containsKey(player.getUUID())) transport.receive(player.getUUID(), data);
  }

  private void sendDatagram(Object key, byte[] data) {
    BridgeSession session = sessions.get((UUID) key);
    if (session == null) return;
    ServerPlayer player = session.player();
    if (player.hasDisconnected()) return;
    ServerPlayNetworking.send(player, new Q3DatagramPayload(data));
  }

  private BridgeSession sessionOfSlot(int client) {
    for (BridgeSession s : sessions.values()) if (s.slot() == client) return s;
    return null;
  }

  // ---- the tick -------------------------------------------------------------------------------

  /**
   * One bridge tick after the level's entities have moved: mobs into puppets, the network and one
   * game frame, then Quake damage to mobs and Minecraft avatars to their Quake bodies. Returns the
   * sessions whose Quake connection went away (the caller ends them).
   */
  public List<BridgeSession> tick() {
    long start = System.nanoTime();
    List<BridgeSession> lost = new ArrayList<>();
    if (closed) return lost;
    ticks++;
    terrain.invalidate();
    bindSlots(lost);
    BridgeSession waiting = null;
    for (BridgeSession s : sessions.values())
      if (s.slot() < 0 || server.client(s.slot()).state() != ServerClient.State.ACTIVE) {
        waiting = s;
        break;
      }
    if (waiting != null) moveSpawn(waiting);
    gatherTargets();
    actorHost.syncActors(targets);
    hideNewPuppets();
    now += TICK_MILLIS;
    host.frame(now);
    if (!running()) {
      lost.addAll(sessions.values());
      return lost;
    }
    List<MobDamage> damage = actorHost.afterFrame();
    for (BridgeSession s : sessions.values()) if (s.host() != null) s.host().afterFrame();
    for (MobDamage d : damage) applyDamage(d);
    for (BridgeSession s : sessions.values()) updateAvatar(s);
    lastTickNanos = System.nanoTime() - start;
    tickNanosTotal += lastTickNanos;
    tickCount++;
    return lost;
  }

  /**
   * Puppets admitted this tick are hidden before the frame's snapshots go out ({@code
   * ExternalActors} hides them only after the frame, so a new puppet's body and name otherwise
   * reach the clients for one snapshot; reported for {@code mc:hybrid}).
   */
  private void hideNewPuppets() {
    GameEntities g = server.entities().orElse(null);
    if (g == null || !g.located()) return;
    for (ExternalActors.Target t : targets) {
      int client = actorHost.actors().client(t.id());
      if (client >= 0)
        g.setSvFlags(
            client,
            g.svFlags(client) | dev.bluevista.craftq3.engine.game.world.Contents.SVF_NOCLIENT);
    }
  }

  /** Finds the slot each session's connection was given, and notices connections that ended. */
  private void bindSlots(List<BridgeSession> lost) {
    for (BridgeSession s : sessions.values()) {
      if (s.slot() >= 0) {
        ServerClient client = server.client(s.slot());
        if (client.state() == ServerClient.State.FREE
            || client.state() == ServerClient.State.ZOMBIE
            || !s.addressText().equals(client.address())) {
          if (s.host() != null) server.removeListener(s.host());
          s.unbind();
          lost.add(s);
        }
        continue;
      }
      for (int i = 0; i < server.maxClients(); i++) {
        ServerClient client = server.client(i);
        if (client.state() == ServerClient.State.FREE
            || client.state() == ServerClient.State.ZOMBIE
            || client.isPuppet()
            || !s.addressText().equals(client.address())) continue;
        BridgeHost h = new BridgeHost(server, LoadoutPolicy.BASE_GAME, 0);
        server.addListener(h);
        h.levelSpawned(server);
        h.setPlayer(i);
        s.bind(i, h);
        break;
      }
      // A Quake session that never connects (a client without the mod's client side, a failed
      // start) is given up after a minute.
      if (s.slot() < 0 && ticks - s.joinedTick() > 60 * 20) lost.add(s);
    }
  }

  private void gatherTargets() {
    targets.clear();
    mirrored.clear();
    if (sessions.isEmpty()) return;
    List<LivingEntity> found = new ArrayList<>();
    boolean pvp = level.isPvpAllowed();
    for (BridgeSession s : sessions.values()) {
      AABB around = s.player().getBoundingBox().inflate(SEARCH_RADIUS);
      for (LivingEntity e :
          level.getEntitiesOfClass(
              LivingEntity.class,
              around,
              e ->
                  e.isAlive()
                      && !e.isSpectator()
                      && !(e instanceof ArmorStand)
                      && BridgedPlayer.of(e) == null
                      && (pvp || !(e instanceof Player)))) {
        if (mirrored.putIfAbsent(e.getId(), e) == null) found.add(e);
      }
    }
    found.sort((a, b) -> Double.compare(nearestSessionDistance(a), nearestSessionDistance(b)));
    float[] boxMins = new float[3], boxMaxs = new float[3];
    for (LivingEntity e : found) {
      if (targets.size() >= MAX_ACTORS) break;
      AABB box = e.getBoundingBox();
      transform.boxToQuake(
          box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, boxMins, boxMaxs);
      float health = Math.clamp(e.getHealth() / Math.max(e.getMaxHealth(), 1e-3f), 1e-3f, 1f);
      try {
        targets.add(
            new ExternalActors.Target(
                e.getId(), e.getName().getString(), boxMins, boxMaxs, health));
      } catch (IllegalArgumentException degenerate) {
        // A zero-sized hitbox (some modded entities) cannot be a Quake body.
      }
    }
  }

  private double nearestSessionDistance(Entity e) {
    double best = Double.MAX_VALUE;
    for (BridgeSession s : sessions.values()) best = Math.min(best, s.player().distanceToSqr(e));
    return best;
  }

  private void applyDamage(MobDamage d) {
    Entity entity = level.getEntity((int) d.target());
    if (!(entity instanceof LivingEntity living) || !living.isAlive()) return;
    BridgeSession attacker = d.attacker() >= 0 ? sessionOfSlot(d.attacker()) : null;
    if (d.quakeDamage() > 0) {
      DamageSource source = QuakeDamage.source(level, attacker == null ? null : attacker.player());
      float amount = d.minecraftDamage(living.getMaxHealth());
      // Quake has its own damage rate; Minecraft's hurt cooldown would drop most bullets.
      living.invulnerableTime = 0;
      if (!living.hurtServer(level, source, amount)) return;
    }
    transform.velocityToMinecraft(d.impulseX(), d.impulseY(), d.impulseZ(), push);
    double resistance =
        Math.clamp(living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0, 1.0);
    double scale = 1 - resistance;
    if (scale > 0 && (push[0] != 0 || push[1] != 0 || push[2] != 0)) {
      living.push(push[0] * scale, push[1] * scale, push[2] * scale);
      living.hurtMarked = true;
    }
  }

  /** Puts the Minecraft player at its Quake body: feet, aim, hull. */
  private void updateAvatar(BridgeSession s) {
    ServerPlayer player = s.player();
    BridgeRules.hold(player, s.alive());
    if (s.slot() < 0) return;
    ServerClient client = server.client(s.slot());
    if (client.state() != ServerClient.State.ACTIVE) return;
    GameEntities g = server.entities().orElse(null);
    if (g == null || !g.located()) return;
    int c = s.slot();
    g.playerVec(c, PlayerStateLayout.ORIGIN, origin);
    g.playerVec(c, PlayerStateLayout.VIEWANGLES, angles);
    g.mins(c, mins);
    g.maxs(c, maxs);
    int viewHeight = g.playerInt(c, PlayerStateLayout.VIEWHEIGHT);
    int pmType = g.playerInt(c, PlayerStateLayout.PM_TYPE);
    int health = g.playerInt(c, PlayerStateLayout.STATS);
    s.alive(health > 0 && pmType != PM_DEAD);
    s.grounded(g.playerInt(c, PlayerStateLayout.GROUND_ENTITY_NUM) != ENTITYNUM_NONE);
    g.playerVec(c, PlayerStateLayout.VELOCITY, velocity);
    transform.velocityToMinecraft(velocity[0], velocity[1], velocity[2], push);
    s.velocity(new net.minecraft.world.phys.Vec3(push[0], push[1], push[2]));
    double x = transform.minecraftX(origin[0]);
    double y = transform.minecraftY(origin[2] + mins[2]);
    double z = transform.minecraftZ(origin[1]);
    float yaw = CoordinateTransform.minecraftYaw(angles[1]);
    float pitch = Math.clamp(CoordinateTransform.minecraftPitch(angles[0]), -90f, 90f);
    if (maxs[0] > mins[0] && maxs[1] > mins[1] && maxs[2] > mins[2]) {
      try {
        ((BridgeAvatar) player)
            .craftq3$setAvatar(
                AvatarShape.ofQuake(mins, maxs, viewHeight, transform.unitsPerBlock()));
      } catch (IllegalArgumentException odd) {
        ((BridgeAvatar) player).craftq3$setAvatar(null);
      }
    }
    BridgeRules.place(player, x, y, z, yaw, pitch);
    player.setOnGround(s.grounded());
  }

  /** Ends the game: every puppet and client dropped, the qagame shut down. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      if (server.state() == GameServer.State.RUNNING) actorHost.close();
      host.close();
    } catch (RuntimeException e) {
      LOGGER.warn("CraftQ3: bridge host did not close cleanly", e);
    } finally {
      try {
        server.close();
      } finally {
        transport.close();
        owner.release();
      }
    }
  }
}
