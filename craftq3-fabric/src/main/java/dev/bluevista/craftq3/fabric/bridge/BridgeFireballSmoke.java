package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Native small-fireball flight/impact and burn attribution; only QA launch scheduling is synthetic.
 */
public final class BridgeFireballSmoke {
  private static UUID playerId;
  private static Mob blaze;
  private static BlockPos origin;
  private static final Set<UUID> projectiles = new HashSet<>();
  private static int stage,
      since,
      lastShot,
      acceptedTick,
      ownerless,
      rejected,
      accepted,
      burns,
      expectedDamage;
  private static boolean covered, started, finished, invulnerable;
  private static int startTime;
  private static float nativeHealth;
  private static double resistance;
  private static CompletableFuture<String> pending;
  private static String evidence;

  private BridgeFireballSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeFireballSmoke");
  }

  public static void block(SmallFireball ball, BlockPos pos) {
    if (!enabled() || !projectiles.contains(ball.getUUID())) return;
    if (stage == 1 && pos.getX() == origin.getX() + 2) {
      covered = true;
      System.out.println("CraftQ3 fireball native wall impact=" + pos);
    }
  }

  public static void impact(SmallFireball ball, ServerPlayer player, boolean hit, int ticks) {
    if (!enabled() || !player.getUUID().equals(playerId) || !projectiles.contains(ball.getUUID()))
      return;
    if (ticks != 100) throw new IllegalStateException("Native fireball ignition duration changed");
    if (ball.getOwner() == null) {
      if (hit) throw new IllegalStateException("Ownerless fireball entered mob damage path");
      ownerless++;
    } else if (hit) {
      accepted++;
      acceptedTick = player.level().getServer().getTickCount();
    } else rejected++;
    System.out.println(
        "CraftQ3 fireball accepted="
            + hit
            + " mob-owned="
            + (ball.getOwner() == blaze)
            + " ticks="
            + ticks);
  }

  public static void damage(ServerPlayer player, DamageSource source, float amount) {
    if (!enabled() || !player.getUUID().equals(playerId)) return;
    if (source.is(DamageTypes.ON_FIRE)) {
      if (amount != 1) throw new IllegalStateException("Unexpected fireball burn amount");
      burns++;
    } else if (!source.is(DamageTypes.FIREBALL) || source.getEntity() != blaze || amount != 5)
      throw new IllegalStateException("Unexpected native fireball damage " + amount);
    expectedDamage += Math.round(amount * 5);
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    playerId = Objects.requireNonNull(client.player).getUUID();
    stage = ownerless = rejected = accepted = burns = expectedDamage = 0;
    covered = started = finished = false;
    pending = null;
    evidence = null;
    projectiles.clear();
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Fireball QA requires private save");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(playerId));
          var level = player.level();
          host.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
          origin = player.blockPosition();
          for (int x = -3; x <= 9; x++)
            for (int z = -3; z <= 3; z++)
              for (int y = -1; y <= 5; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1
                            ? Blocks.GLOWSTONE
                            : x == -3 || x == 9 || Math.abs(z) == 3 || y == 5 || x == 2
                                ? Blocks.STONE
                                : Blocks.AIR)
                        .defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16)))
            mob.discard();
          for (var projectile :
              level.getEntitiesOfClass(
                  net.minecraft.world.entity.projectile.Projectile.class,
                  player.getBoundingBox().inflate(16))) projectile.discard();
          blaze =
              Objects.requireNonNull(EntityTypes.BLAZE.create(level, EntitySpawnReason.COMMAND));
          blaze.setNoAi(true);
          blaze.setNoGravity(true);
          blaze.setPersistenceRequired();
          blaze.setPos(origin.getX() + 6.5, origin.getY(), origin.getZ() + .5);
          level.addFreshEntity(blaze);
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          player.clearFire();
          nativeHealth = player.getHealth();
          invulnerable = player.isInvulnerable();
          player.setInvulnerable(false);
          var attribute =
              Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE));
          resistance = attribute.getBaseValue();
          attribute.setBaseValue(1);
          return null;
        });
  }

  private static void clearProjectiles(ServerPlayer player) {
    for (var ball :
        player.level().getEntitiesOfClass(SmallFireball.class, player.getBoundingBox().inflate(24)))
      if (projectiles.contains(ball.getUUID())) ball.discard();
  }

  private static void shoot(ServerPlayer player, int tick, boolean mobOwned) {
    var start =
        new net.minecraft.world.phys.Vec3(blaze.getX() - .7, blaze.getY() + 1.1, blaze.getZ());
    var direction = player.position().add(0, 1.1, 0).subtract(start).normalize();
    var ball =
        mobOwned
            ? new SmallFireball(player.level(), blaze, direction)
            : new SmallFireball(player.level(), start.x, start.y, start.z, direction);
    ball.setPos(start);
    projectiles.add(ball.getUUID());
    player.level().addFreshEntity(ball);
    lastShot = tick;
  }

  public static void step(Minecraft client, BridgeGame game) {
    if (finished) return;
    if (!started) {
      started = true;
      startTime = game.time();
      game.command("give health");
    }
    if (game.time() - startTime < 320) return;
    if (pending != null) {
      if (!pending.isDone()) return;
      var result = pending.join();
      pending = null;
      if (result != null) {
        evidence = result;
        finished = true;
        return;
      }
    }
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    int health = game.health();
    pending =
        host.submit(
            () ->
                advance(
                    Objects.requireNonNull(host.getPlayerList().getPlayer(playerId)),
                    host.getTickCount(),
                    health));
  }

  private static String advance(ServerPlayer player, int tick, int health) {
    var controller = Objects.requireNonNull(BridgePlayerController.get(player));
    if (player.getHealth() != nativeHealth)
      throw new IllegalStateException("Native health changed during fireball combat");
    if (stage == 0) {
      if (health != 100) throw new IllegalStateException("Quake health command not applied");
      shoot(player, tick, true);
      since = tick;
      stage = 1;
    } else if (stage == 1 && covered) {
      if (controller.hits() != 0 || player.getRemainingFireTicks() > 0 || health != 100)
        throw new IllegalStateException("Fireball penetrated native cover");
      clearProjectiles(player);
      for (int x = 2; x <= 3; x++)
        for (int z = -2; z <= 2; z++)
          for (int y = 0; y < 5; y++)
            player
                .level()
                .setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
      shoot(player, tick, false);
      since = tick;
      stage = 2;
    } else if (stage == 2 && ownerless > 0) {
      if (controller.hits() != 0 || player.getRemainingFireTicks() > 0 || health != 100)
        throw new IllegalStateException("Ownerless fireball started Quake damage or burn");
      clearProjectiles(player);
      player.setInvulnerable(true);
      shoot(player, tick, true);
      since = tick;
      stage = 3;
    } else if (stage == 3 && rejected > 0) {
      if (controller.hits() != 0 || player.getRemainingFireTicks() > 0 || health != 100)
        throw new IllegalStateException("Rejected mob fireball started damage or burn");
      clearProjectiles(player);
      player.setInvulnerable(false);
      shoot(player, tick, true);
      since = tick;
      stage = 4;
    } else if (stage == 4
        && accepted == 1
        && tick - acceptedTick >= 120
        && player.getRemainingFireTicks() <= 0) {
      if (burns < 2
          || health != 100 - expectedDamage
          || health <= 0
          || controller.hits() != 1 + burns)
        throw new IllegalStateException(
            "Fireball handoff mismatch: health="
                + health
                + " expected="
                + (100 - expectedDamage)
                + " burns="
                + burns);
      clearProjectiles(player);
      blaze.discard();
      player.clearFire();
      player.setInvulnerable(invulnerable);
      Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE))
          .setBaseValue(resistance);
      return "small-fireball=true native-cover=true ownerless-rejected=true"
          + " invulnerable-rejected=true accepted="
          + accepted
          + " burns="
          + burns
          + " quake-health="
          + health
          + " exact-damage=true minecraft-health=true natural-expiry=true";
    }
    boolean waiting =
        stage == 1 && !covered
            || stage == 2 && ownerless == 0
            || stage == 3 && rejected == 0
            || stage == 4 && accepted == 0;
    if (waiting && tick - lastShot >= 40) shoot(player, tick, stage != 2);
    if (tick - since > 300)
      throw new IllegalStateException("Fireball fixture timeout at stage " + stage);
    return null;
  }

  public static String result(BridgeGame game) {
    if (pending != null) pending.join();
    if (!finished
        || !covered
        || ownerless < 1
        || rejected < 1
        || accepted != 1
        || game.health() <= 0)
      throw new IllegalStateException("Fireball fixture incomplete at stage " + stage);
    return evidence;
  }
}
