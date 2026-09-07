package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import dev.bluevista.craftq3.server.Q3Server;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Minecraft entity access stays on the integrated-server thread; QVM access stays on the host
 * thread.
 */
public final class MinecraftCombat implements AutoCloseable {
  private static final ResourceKey<DamageType> DAMAGE =
      ResourceKey.create(
          Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("craftq3", "quake"));

  private record Target(UUID id, String name, BspMap.Bounds bounds, float health, float maximum) {}

  private record Body(boolean alive, boolean grounded, BridgePlayerShape shape) {}

  private record Snapshot(
      long acknowledged,
      List<Target> targets,
      List<Integer> damage,
      List<Vec3> impulses,
      long incomingHits) {
    Snapshot {
      targets = List.copyOf(targets);
      damage = List.copyOf(damage);
      impulses = List.copyOf(impulses);
    }
  }

  private static final class Actor {
    final int slot;
    int previousHealth;
    BspMap.Bounds bounds;
    String name;
    boolean deathPresented;
    Vec3 previousVelocity = new Vec3(0, 0, 0);
    float maximum;

    Actor(int slot) {
      this.slot = slot;
    }
  }

  private final net.minecraft.client.server.IntegratedServer host;
  private final ResourceKey<Level> dimension;
  private final UUID player;
  private final CoordinateTransform transform;
  private final CombatLedger ledger = new CombatLedger();
  private final Map<UUID, Actor> actors = new LinkedHashMap<>();
  private CompletableFuture<Snapshot> pending;
  private volatile boolean closed;
  private long deliveredHits, incomingHits;
  private volatile Body body;
  private volatile long appliedHits, killedTargets;

  public MinecraftCombat(Minecraft minecraft, CoordinateTransform transform) {
    host = Objects.requireNonNull(minecraft.getSingleplayerServer());
    player = Objects.requireNonNull(minecraft.player).getUUID();
    dimension = Objects.requireNonNull(minecraft.level).dimension();
    this.transform = transform;
  }

  public long incomingHits() {
    return incomingHits;
  }

  public long appliedHits() {
    return appliedHits;
  }

  public long killedTargets() {
    return killedTargets;
  }

  public int targets() {
    return actors.size();
  }

  public long deliveredHits() {
    return deliveredHits;
  }

  public void pump(Q3Server quake, Vec3 center) {
    if (closed) return;
    publishBody(quake);
    boolean alive = body.alive();
    // Keep obituary names available until original cgame has consumed the damage frame.
    for (var actor : actors.values()) {
      if (actor.previousHealth <= 0 && actor.deathPresented) quake.removeExternalActor(actor.slot);
    }
    if (pending != null && pending.isDone()) {
      var snapshot = pending.join();
      pending = null;
      ledger.acknowledge(snapshot.acknowledged());
      incomingHits = snapshot.incomingHits();
      if (alive) {
        for (int amount : snapshot.damage()) quake.externalDamage(amount);
        for (var impulse : snapshot.impulses()) {
          // Minecraft velocity is blocks/tick; Q3 velocity is units/second, Z up.
          quake.externalImpulse(
              new Vec3(impulse.x(), -impulse.z(), impulse.y())
                  .scale(transform.quakeUnitsPerBlock() * 20));
        }
      }
      var retained = new HashSet<UUID>();
      for (var target : snapshot.targets()) retained.add(target.id());
      for (var iterator = actors.entrySet().iterator(); iterator.hasNext(); ) {
        var entry = iterator.next();
        if (!retained.contains(entry.getKey()) && !unpresentedDeath(entry.getValue())) {
          quake.removeExternalActor(entry.getValue().slot);
          iterator.remove();
        }
      }
      for (var target : snapshot.targets()) {
        int health =
            (int)
                Math.ceil(
                    (target.health() - ledger.outstanding(target.id())) * 100 / target.maximum());
        var actor = actors.get(target.id());
        if (actor != null && unpresentedDeath(actor)) continue;
        if (health <= 0) {
          if (actor != null) {
            quake.removeExternalActor(actor.slot);
            actors.remove(target.id());
          }
          continue;
        }
        if (actor != null && (!quake.isConnected(actor.slot) || health(quake, actor.slot) <= 0)) {
          quake.removeExternalActor(actor.slot);
          actors.remove(target.id());
          actor = null;
        }
        if (actor == null) {
          var used = new BitSet();
          for (var existing : actors.values()) used.set(existing.slot);
          int slot = used.nextClearBit(1);
          if (slot > 31) break;
          actor = new Actor(slot);
          actors.put(target.id(), actor);
        }
        int mirroredHealth = Math.clamp(health, 1, 100);
        // Host snapshots can arrive faster than Minecraft ticks. Original qagame's public
        // health/velocity, observed after every simulation step, still invalidate this fast path.
        if (!target.bounds().equals(actor.bounds)
            || !target.name().equals(actor.name)
            || mirroredHealth != actor.previousHealth
            || actor.previousVelocity.x() != 0
            || actor.previousVelocity.y() != 0
            || actor.previousVelocity.z() != 0) {
          quake.externalActor(actor.slot, target.bounds(), mirroredHealth, target.name());
          actor.bounds = target.bounds();
          actor.name = target.name();
          actor.previousHealth = mirroredHealth;
          actor.previousVelocity = new Vec3(0, 0, 0);
        }
        actor.maximum = target.maximum();
      }
    }
    if (pending == null) {
      var batch = ledger.send();
      pending = host.submit(() -> exchange(batch, center));
    }
  }

  /** Called after original qagame has finalized player damage for this simulation step. */
  public void afterFrame(Q3Server quake) {
    publishBody(quake);
    for (var entry : actors.entrySet()) {
      var actor = entry.getValue();
      if (!quake.isConnected(actor.slot)) continue;
      var state = ByteBuffer.wrap(quake.playerState(actor.slot)).order(ByteOrder.LITTLE_ENDIAN);
      int current = state.getInt(184);
      long lost = (long) actor.previousHealth - current;
      var velocity = new Vec3(state.getFloat(32), state.getFloat(36), state.getFloat(40));
      var impulse = velocity.add(actor.previousVelocity.scale(-1));
      if (lost > 0 && actor.previousHealth > 0) {
        ledger.add(entry.getKey(), (float) (lost * actor.maximum / 100.0), impulse);
        deliveredHits++;
      }
      if (current <= 0 && actor.previousHealth > 0) actor.deathPresented = false;
      actor.previousHealth = current;
      actor.previousVelocity = velocity;
    }
  }

  private static boolean unpresentedDeath(Actor actor) {
    return actor.previousHealth <= 0 && !actor.deathPresented;
  }

  /** Original cgame has consumed this snapshot and its obituary names. */
  public void presented() {
    for (var actor : actors.values()) if (actor.previousHealth <= 0) actor.deathPresented = true;
  }

  private void publishBody(Q3Server quake) {
    var ps = ByteBuffer.wrap(quake.playerState(0)).order(ByteOrder.LITTLE_ENDIAN);
    body =
        new Body(
            ps.getInt(184) > 0,
            ps.getInt(68) != 1023,
            BridgePlayerShape.from(
                quake.playerBounds(0),
                new Vec3(ps.getFloat(20), ps.getFloat(24), ps.getFloat(28)),
                ps.getInt(164),
                transform.quakeUnitsPerBlock()));
  }

  private Snapshot exchange(CombatLedger.Batch batch, Vec3 center) {
    if (closed) return new Snapshot(batch.through(), List.of(), List.of(), List.of(), 0);
    var attacker = host.getPlayerList().getPlayer(player);
    if (attacker == null || !attacker.level().dimension().equals(dimension))
      return new Snapshot(batch.through(), List.of(), List.of(), List.of(), 0);
    var controller = BridgePlayerController.get(attacker);
    if (controller != null) {
      var latest = body;
      controller.alive = latest.alive();
      controller.grounded = latest.grounded();
      controller.shape(latest.shape());
    }
    var level = attacker.level();
    var holder = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DAMAGE);
    var damage = new DamageSource(holder, attacker);
    for (var hit : batch.hits()) {
      if (closed) break;
      var entity = level.getEntity(hit.target());
      if (entity instanceof Mob mob && mob.isAlive()) {
        var before = mob.getDeltaMovement();
        if (!mob.hurtServer(level, damage, hit.amount())) continue;
        // This damage type suppresses Minecraft's source-position knockback. Original QVM
        // velocity supplies direction and strength; native resistance and physics still apply.
        double resistance =
            mob.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        var impulse = transform.directionToMinecraft(hit.impulse()).scale((1 - resistance) / 20);
        mob.push(new net.minecraft.world.phys.Vec3(impulse.x(), impulse.y(), impulse.z()));
        BridgeOutgoingImpulseSmoke.observe(mob, hit.impulse(), impulse, before);
        appliedHits++;
        if (!mob.isAlive()) killedTargets++;
      }
    }
    var region =
        new AABB(
            center.x() - 64,
            center.y() - 64,
            center.z() - 64,
            center.x() + 64,
            center.y() + 64,
            center.z() + 64);
    var targets =
        level
            .getEntitiesOfClass(Mob.class, region, mob -> mob.isAlive() && mob.getMaxHealth() > 0)
            .stream()
            .sorted(
                Comparator.comparingDouble(
                    mob -> mob.distanceToSqr(center.x(), center.y(), center.z())))
            .limit(31)
            .map(
                mob ->
                    new Target(
                        mob.getUUID(),
                        mob.getName().getString(),
                        bounds(mob.getBoundingBox()),
                        mob.getHealth(),
                        mob.getMaxHealth()))
            .toList();
    return new Snapshot(
        batch.through(),
        targets,
        controller == null ? List.of() : controller.drain(),
        controller == null ? List.of() : controller.drainImpulses(),
        controller == null ? 0 : controller.hits());
  }

  private BspMap.Bounds bounds(AABB box) {
    var a = transform.toQuake(new Vec3(box.minX, box.minY, box.minZ));
    var b = transform.toQuake(new Vec3(box.maxX, box.maxY, box.maxZ));
    return new BspMap.Bounds(
        new Vec3(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
        new Vec3(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
  }

  private static int health(Q3Server quake, int slot) {
    return ByteBuffer.wrap(quake.playerState(slot)).order(ByteOrder.LITTLE_ENDIAN).getInt(184);
  }

  @Override
  public void close() {
    closed = true;
    if (pending != null) pending.cancel(false);
    actors.clear();
  }
}
