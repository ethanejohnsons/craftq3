package dev.bluevista.craftq3.mc.fabric.bridge;

import dev.bluevista.craftq3.mc.hybrid.bridge.BridgeHost;
import dev.bluevista.craftq3.mc.hybrid.bridge.Loadout;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * One Minecraft player playing Quake in a level's {@link BridgeGame}: its synthetic network
 * address, the qagame client slot its Quake session connected to, its handle on the game's {@link
 * BridgeHost} (which delivers its incoming Minecraft damage, pushes and loadout), and the Minecraft
 * state it returns to.
 *
 * <p>Server thread only.
 */
public final class BridgeSession {
  private final BridgeGame game;
  private final UUID id;
  private final String addressText;
  private final Vec3 entry;
  private final float entryYaw;
  private Optional<Loadout> loadout;
  private ServerPlayer player;
  private int slot = -1;
  private BridgeHost.Client host;
  private boolean alive = true;
  private boolean grounded = true;
  private Vec3 velocity = Vec3.ZERO;
  private boolean ended;
  private long incomingHits;
  private long incomingBurns;
  private long joinedTick;

  BridgeSession(
      BridgeGame game,
      ServerPlayer player,
      String addressText,
      Optional<Loadout> loadout,
      long joinedTick) {
    this.game = Objects.requireNonNull(game);
    this.player = Objects.requireNonNull(player);
    this.id = player.getUUID();
    this.addressText = Objects.requireNonNull(addressText);
    this.loadout = Objects.requireNonNull(loadout);
    this.entry = player.position();
    this.entryYaw = player.getYRot();
    this.joinedTick = joinedTick;
  }

  public BridgeGame game() {
    return game;
  }

  public UUID id() {
    return id;
  }

  public ServerPlayer player() {
    return player;
  }

  void player(ServerPlayer current) {
    player = Objects.requireNonNull(current);
  }

  /** The address text the game server records for this player's connection. */
  public String addressText() {
    return addressText;
  }

  /** Where the player entered the bridge: Quake respawns happen here. */
  public Vec3 entry() {
    return entry;
  }

  float entryYaw() {
    return entryYaw;
  }

  long joinedTick() {
    return joinedTick;
  }

  /** The qagame client slot, or -1 before the Quake session has connected. */
  public int slot() {
    return slot;
  }

  /** The host's handle on this player's slot (damage and loadout), or null before it connected. */
  public BridgeHost.Client host() {
    return host;
  }

  void bind(int client, BridgeHost.Client handle) {
    slot = client;
    host = handle;
    loadout.ifPresent(handle::restoreLoadout);
    loadout = Optional.empty();
  }

  void unbind() {
    slot = -1;
    host = null;
  }

  /** Whether the Quake player is alive (as of the last tick). */
  public boolean alive() {
    return alive;
  }

  void alive(boolean value) {
    alive = value;
  }

  /** Whether the Quake player stands on something (as of the last tick). */
  public boolean grounded() {
    return grounded;
  }

  void grounded(boolean value) {
    grounded = value;
  }

  /** The Quake player's velocity in Minecraft blocks per tick (as of the last tick). */
  public Vec3 velocity() {
    return velocity;
  }

  void velocity(Vec3 value) {
    velocity = value;
  }

  public boolean ended() {
    return ended;
  }

  void end() {
    ended = true;
  }

  /** Accepted Minecraft hits handed to Quake so far. */
  public long incomingHits() {
    return incomingHits;
  }

  /** Accepted burning damage (fire, campfires, the on-fire ticks) handed to Quake so far. */
  public long incomingBurns() {
    return incomingBurns;
  }

  /**
   * Minecraft damage reached the player ({@code Player.actuallyHurt}): mob and player attacks,
   * their projectiles, every explosion and burning become Quake damage; Quake owns falling,
   * drowning, lava and the rest, so other environmental damage is ignored. Minecraft health never
   * changes while bridged.
   *
   * <p>Burning is whatever set the body alight: a burning zombie's hit, a flaming arrow, a blaze's
   * fireball, Fire Aspect, lightning, or fire, campfires and lava the Quake hull stands in (the
   * player's own tick applies block effects to the hull: {@code Player.tick} clears {@code
   * noPhysics} before them). Fire blocks and campfires hurt on contact ({@code in_fire}, {@code
   * campfire}), and the burn goes on as {@code on_fire} ticks, which Minecraft skips while in lava;
   * lava's own damage is Quake's lava. Water and rain put it out, a dead Quake player is put out
   * ({@link BridgeRules#hold}).
   */
  public void minecraftDamage(DamageSource source, float amount) {
    if (ended || host == null || !alive || !(amount > 0)) return;
    if (burning(source)) incomingBurns++;
    else if (!accepts(source)) return;
    host.minecraftDamage(amount);
    incomingHits++;
  }

  /** Fire touching the body and the on-fire ticks; lava's own damage is Quake's lava. */
  private static boolean burning(DamageSource source) {
    return source.is(DamageTypes.ON_FIRE)
        || source.is(DamageTypes.IN_FIRE)
        || source.is(DamageTypes.CAMPFIRE);
  }

  private boolean accepts(DamageSource source) {
    Entity cause = source.getEntity();
    if (cause instanceof LivingEntity living && living != player) {
      // Other bridged players fight with Quake weapons, never with Minecraft melee.
      return BridgedPlayer.of(living) == null;
    }
    if (source.getDirectEntity() instanceof Projectile) return cause != null;
    return source.is(DamageTypeTags.IS_EXPLOSION);
  }

  /**
   * A Minecraft push (blocks per tick, e.g. melee knockback or an explosion) as a Quake velocity
   * change; Quake movement then handles gravity, friction and collision.
   */
  public void impulse(Vec3 delta) {
    if (ended || host == null || !alive || delta.lengthSqr() == 0) return;
    float[] q = new float[3];
    game.transform().velocityToQuake(delta.x, delta.y, delta.z, q);
    host.impulse(q[0], q[1], q[2]);
  }
}
