package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Native Flame arrows, rejected hits, burn expiration and same-tick extinguish/reignition. */
public final class BridgeFireSmoke {
  private static UUID playerId;
  private static AbstractSkeleton skeleton;
  private static int stage, since, rejected, accepted, burns, savedBurns, startTime;
  private static int expectedDamage, firstHealth, finalHealth, lastShot;
  private static float nativeHealth;
  private static double resistance;
  private static boolean invulnerable, started, finished;
  private static CompletableFuture<String> pending;
  private static String evidence;

  private BridgeFireSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeFireSmoke");
  }

  public static void arrow(ServerPlayer player, boolean hit, int fireTicks) {
    if (!enabled() || !player.getUUID().equals(playerId)) return;
    if (fireTicks != 100)
      throw new IllegalStateException("Expected native five-second Flame arrow");
    if (hit) accepted++;
    else rejected++;
    System.out.println("CraftQ3 Flame arrow accepted=" + hit + " ticks=" + fireTicks);
  }

  public static void damage(ServerPlayer player, DamageSource source, float amount) {
    if (!enabled() || !player.getUUID().equals(playerId)) return;
    expectedDamage += Math.round(amount * 5);
    if (source.is(DamageTypes.ON_FIRE)) {
      if (amount != 1) throw new IllegalStateException("Unexpected native burn amount " + amount);
      burns++;
      System.out.println("CraftQ3 native burn=" + burns + " amount=" + amount);
    }
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    playerId = Objects.requireNonNull(client.player).getUUID();
    stage = rejected = accepted = burns = savedBurns = expectedDamage = 0;
    started = finished = false;
    pending = null;
    evidence = null;
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Flame QA requires private save");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(playerId));
          var level = player.level();
          var origin = player.blockPosition();
          host.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
          for (int x = -3; x <= 9; x++)
            for (int z = -3; z <= 3; z++)
              for (int y = -1; y <= 5; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16)))
            mob.discard();
          skeleton =
              Objects.requireNonNull(EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND));
          skeleton.setNoAi(true);
          skeleton.setPersistenceRequired();
          var bow = new ItemStack(Items.BOW);
          bow.enchant(
              level
                  .registryAccess()
                  .lookupOrThrow(Registries.ENCHANTMENT)
                  .getOrThrow(Enchantments.FLAME),
              1);
          skeleton.setItemSlot(EquipmentSlot.MAINHAND, bow);
          skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
          skeleton.setPos(origin.getX() + 6.5, origin.getY(), origin.getZ() + .5);
          level.addFreshEntity(skeleton);
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          nativeHealth = player.getHealth();
          invulnerable = player.isInvulnerable();
          player.setInvulnerable(false);
          var attribute =
              Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE));
          resistance = attribute.getBaseValue();
          attribute.setBaseValue(1);
          player.clearFire();
          return null;
        });
  }

  private static void removeArrows(ServerPlayer player) {
    for (var arrow :
        player
            .level()
            .getEntitiesOfClass(
                AbstractArrow.class,
                player.getBoundingBox().inflate(20),
                a -> a.getOwner() == skeleton)) arrow.discard();
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
      if ("heal".equals(result)) game.command("give health");
      else if (result != null) {
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
    if (player.getHealth() != nativeHealth)
      throw new IllegalStateException("Burn changed native health");
    var controller = Objects.requireNonNull(BridgePlayerController.get(player));
    if (stage == 0) {
      if (health != 100) throw new IllegalStateException("Initial Quake health not applied");
      player.igniteForSeconds(2);
      since = tick;
      stage = 1;
    } else if (stage == 1 && tick - since >= 50) {
      if (burns != 0 || controller.hits() != 0 || health != 100)
        throw new IllegalStateException("Unattributed native fire damaged Quake");
      player.clearFire();
      player.setInvulnerable(true);
      skeleton.performRangedAttack(player, 1);
      lastShot = tick;
      since = tick;
      stage = 2;
    } else if (stage == 2 && rejected > 0) {
      if (burns != 0
          || accepted != 0
          || controller.hits() != 0
          || player.getRemainingFireTicks() > 0)
        throw new IllegalStateException("Rejected Flame arrow started a burn");
      removeArrows(player);
      player.setInvulnerable(false);
      skeleton.performRangedAttack(player, 1);
      lastShot = tick;
      since = tick;
      stage = 3;
    } else if (stage == 3 && accepted == 1 && burns >= 2) {
      if (health >= 100 || health <= 0)
        throw new IllegalStateException("Native burn did not reach living Quake player");
      savedBurns = burns;
      player.clearFire();
      player.igniteForSeconds(2);
      since = tick;
      stage = 4;
    } else if (stage == 4 && tick - since >= 50) {
      if (burns != savedBurns)
        throw new IllegalStateException("Same-tick reignition inherited mob burn");
      if (health != 100 - expectedDamage)
        throw new IllegalStateException(
            "Burn handoff changed damage: health="
                + health
                + " expected="
                + (100 - expectedDamage));
      firstHealth = health;
      player.clearFire();
      removeArrows(player);
      since = tick;
      stage = 5;
      return "heal";
    } else if (stage == 5 && tick - since >= 20) {
      if (health != 100) throw new IllegalStateException("Health not restored before expiry test");
      expectedDamage = 0;
      skeleton.performRangedAttack(player, 1);
      lastShot = tick;
      since = tick;
      stage = 6;
    } else if (stage == 6
        && accepted == 2
        && tick - since >= 120
        && player.getRemainingFireTicks() <= 0) {
      if (burns <= savedBurns || health >= 100 || health <= 0)
        throw new IllegalStateException("Natural mob burn did not finish correctly");
      savedBurns = burns;
      player.igniteForSeconds(2);
      since = tick;
      stage = 7;
    } else if (stage == 7 && tick - since >= 50) {
      if (burns != savedBurns)
        throw new IllegalStateException("Expired mob attribution reached new fire");
      if (health != 100 - expectedDamage)
        throw new IllegalStateException(
            "Expired burn handoff changed damage: health="
                + health
                + " expected="
                + (100 - expectedDamage));
      finalHealth = health;
      player.clearFire();
      removeArrows(player);
      skeleton.discard();
      player.setInvulnerable(invulnerable);
      Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE))
          .setBaseValue(resistance);
      return "flame=true accepted-arrows="
          + accepted
          + " rejected-arrows="
          + rejected
          + " burn-hits="
          + burns
          + " original-quake-damage=true minecraft-health=true unattributed-fire=false"
          + " extinguish=true expiry=true first-health="
          + firstHealth
          + " final-health="
          + finalHealth
          + " exact-damage=true";
    }
    if ((stage == 2 && rejected == 0 || stage == 3 && accepted == 0 || stage == 6 && accepted < 2)
        && tick - lastShot >= 40) {
      skeleton.performRangedAttack(player, 1);
      lastShot = tick;
    }
    if (stage != 0 && tick - since > 240)
      throw new IllegalStateException("Flame fixture timed out at stage " + stage);
    return null;
  }

  public static String result(BridgeGame game) {
    if (pending != null) pending.join();
    if (!finished || burns < 3 || accepted != 2 || rejected < 1 || game.health() <= 0)
      throw new IllegalStateException(
          "Flame fixture incomplete: stage=" + stage + " burns=" + burns);
    return evidence;
  }
}
