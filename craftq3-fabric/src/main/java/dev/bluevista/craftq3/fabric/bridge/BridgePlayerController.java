package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Integrated-server ownership of the targetable Minecraft avatar; never calls a QVM. */
public final class BridgePlayerController {
  private static final Map<ServerPlayer, BridgePlayerController> ACTIVE = new ConcurrentHashMap<>();
  private final ServerPlayer player;
  private final BridgeReturnState.State original;
  private final ArrayDeque<Integer> incoming = new ArrayDeque<>();
  private final ArrayDeque<dev.bluevista.craftq3.core.math.Vec3> impulses = new ArrayDeque<>();
  private long hits;
  private long mobFireUntil = Long.MIN_VALUE;
  public boolean grounded;
  public volatile boolean alive = true;
  private BridgePlayerShape shape;

  public BridgePlayerShape shape() {
    return shape;
  }

  public void shape(BridgePlayerShape value) {
    shape = Objects.requireNonNull(value);
  }

  private BridgePlayerController(ServerPlayer player) {
    this.player = player;
    original = BridgeReturnState.capture(player);
  }

  public static BridgePlayerController get(ServerPlayer player) {
    return ACTIVE.get(player);
  }

  public static void clear(net.minecraft.server.MinecraftServer server) {
    ACTIVE.keySet().removeIf(player -> player.level().getServer() == server);
  }

  public static BridgePlayerController begin(ServerPlayer player) {
    if (ACTIVE.containsKey(player))
      throw new IllegalStateException("Minecraft bridge already active");
    var controller = new BridgePlayerController(player);
    var marker = (BridgeReturnState.Marker) player;
    if (!marker.craftq3$bridgeMarker().isEmpty())
      throw new IllegalStateException("Unrestored bridge player marker");
    try {
      String token = BridgeReturnState.save(player, controller.original);
      marker.craftq3$bridgeMarker(token);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Could not save bridge return state", error);
    }
    ACTIVE.put(player, controller);
    player.setGameMode(GameType.ADVENTURE);
    controller.hold();
    player.onUpdateAbilities();
    return controller;
  }

  /** Vanilla attack difficulty/cooldowns run before this hook; original Quake owns armor/health. */
  public void damage(float amount) {
    if (!alive || !Float.isFinite(amount) || amount <= 0) return;
    if (incoming.size() >= 1024)
      throw new IllegalStateException("Excessive incoming bridge damage");
    incoming.add(
        Math.clamp(
            Math.round(amount * 5), 1, dev.bluevista.craftq3.server.ExternalWorld.MAX_DAMAGE));
    hits++;
  }

  public List<Integer> drain() {
    var result = List.copyOf(incoming);
    incoming.clear();
    return result;
  }

  public void impulse(net.minecraft.world.phys.Vec3 delta) {
    if (!alive || delta.lengthSqr() == 0) return;
    if (impulses.size() >= 1024)
      throw new IllegalStateException("Excessive incoming bridge impulses");
    impulses.add(new dev.bluevista.craftq3.core.math.Vec3(delta.x, delta.y, delta.z));
  }

  public List<dev.bluevista.craftq3.core.math.Vec3> drainImpulses() {
    var result = List.copyOf(impulses);
    impulses.clear();
    return result;
  }

  public long hits() {
    return hits;
  }

  /** Admit only the native burn belonging to a confirmed mob projectile hit. */
  public boolean acceptsDamage(net.minecraft.world.damagesource.DamageSource source) {
    return alive
        && (source.getEntity() instanceof net.minecraft.world.entity.Mob
            || source.is(net.minecraft.world.damagesource.DamageTypes.ON_FIRE)
                && player.getRemainingFireTicks() > 0
                && player.level().getGameTime() < mobFireUntil);
  }

  public void mobFire(int ticks) {
    if (alive && ticks > 0)
      mobFireUntil = Math.max(mobFireUntil, player.level().getGameTime() + ticks);
  }

  public void clearMobFire() {
    mobFireUntil = Long.MIN_VALUE;
  }

  public void hold() {
    if (!alive || player.getRemainingFireTicks() <= 0) clearMobFire();
    player.noPhysics = true;
    player.setNoGravity(true);
    player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    var state = player.getAbilities();
    state.mayfly = true;
    state.flying = true;
    state.invulnerable = !alive;
  }

  public void close() {
    if (!ACTIVE.remove(player, this)) return;
    incoming.clear();
    impulses.clear();
    original.apply(player);
    ((BridgeReturnState.Marker) player).craftq3$bridgeMarker("");
    player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    player.onUpdateAbilities();
  }
}
