package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

/** Development-only combat fixture in the explicitly selected private QA world. */
public final class BridgeCombatSmoke {
  private static UUID target;
  private static final List<BlockPos> wall = new ArrayList<>();
  private static volatile boolean coverPassed;
  private static boolean sawDeath, sawRespawn;
  private static float minecraftHealth;

  private BridgeCombatSmoke() {}

  public static boolean enabled() {
    return dev.bluevista.craftq3.fabric.render.BridgeConsoleSmoke.enabled()
        || dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke.enabled()
        || BridgeTravelSmoke.enabled()
        || BridgePickupSmoke.enabled()
        || BridgeTransferSmoke.enabled()
        || BridgeLoadoutSmoke.enabled()
        || dev.bluevista.craftq3.fabric.render.BridgeCameraSmoke.enabled()
        || BridgeOutgoingImpulseSmoke.enabled()
        || BridgeExplosionSmoke.enabled()
        || BridgeKnockbackSmoke.enabled()
        || BridgeRangedSmoke.enabled()
        || BridgeFluidSmoke.enabled()
        || BridgeFireSmoke.enabled()
        || BridgeFireballSmoke.enabled()
        || BridgeHitboxSmoke.enabled()
        || BridgeBlazeSmoke.enabled()
        || incoming()
        || Boolean.getBoolean("craftq3.bridgeCombatSmoke");
  }

  public static boolean incoming() {
    return Boolean.getBoolean("craftq3.bridgeIncomingSmoke");
  }

  /** Recover only the selected QA copy after an interrupted smoke, before waiting for chunks. */
  public static void prepare(Minecraft client) {
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    server.execute(
        () -> {
          var player = Objects.requireNonNull(server.getPlayerList().getPlayer(id));
          if (player
              .level()
              .dimension()
              .equals(dev.bluevista.craftq3.fabric.building.BuildingSession.DIMENSION)) {
            var overworld =
                Objects.requireNonNull(server.getLevel(net.minecraft.world.level.Level.OVERWORLD));
            player.teleportTo(
                overworld, 0.5, overworld.getMinY() + 8, 0.5, java.util.Set.of(), -90, 0, true);
          }
          player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
          player.getAbilities().flying = true;
          player.onUpdateAbilities();
          var pos = player.blockPosition();
          int y = Math.max(pos.getY(), player.level().getMinY() + 8);
          for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
              player
                  .level()
                  .setBlockAndUpdate(
                      new BlockPos(pos.getX() + dx, y - 1, pos.getZ() + dz),
                      Blocks.STONE.defaultBlockState());
          player.connection.teleport(pos.getX() + .5, y, pos.getZ() + .5, -90, 0);
          dev.bluevista.craftq3.fabric.building.BuildingRecoverySmoke.seed(player);
          BridgeRecoverySmoke.seed(player);
        });
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    if (dev.bluevista.craftq3.fabric.render.BridgeConsoleSmoke.enabled()
        || dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke.enabled())
      return dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke.setup(client);
    if (BridgeTravelSmoke.enabled()) return BridgeTravelSmoke.setup(client);
    if (BridgePickupSmoke.enabled()) return BridgePickupSmoke.setup(client);
    if (BridgeTransferSmoke.enabled()) return BridgeTransferSmoke.setup(client);
    if (BridgeLoadoutSmoke.enabled()) return BridgeLoadoutSmoke.setup(client);
    if (dev.bluevista.craftq3.fabric.render.BridgeCameraSmoke.enabled())
      return dev.bluevista.craftq3.fabric.render.BridgeCameraSmoke.setup(client);
    if (BridgeOutgoingImpulseSmoke.enabled()) return BridgeOutgoingImpulseSmoke.setup(client);
    if (BridgeExplosionSmoke.enabled()) return BridgeExplosionSmoke.setup(client);
    if (BridgeKnockbackSmoke.enabled()) return BridgeKnockbackSmoke.setup(client);
    if (BridgeRangedSmoke.enabled()) return BridgeRangedSmoke.setup(client);
    if (BridgeFluidSmoke.enabled()) return BridgeFluidSmoke.setup(client);
    if (BridgeFireSmoke.enabled()) return BridgeFireSmoke.setup(client);
    if (BridgeFireballSmoke.enabled()) return BridgeFireballSmoke.setup(client);
    if (BridgeHitboxSmoke.enabled()) return BridgeHitboxSmoke.setup(client);
    if (BridgeBlazeSmoke.enabled()) return BridgeBlazeSmoke.setup(client);
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    return server.submit(
        () -> {
          var player = server.getPlayerList().getPlayer(id);
          var level = Objects.requireNonNull(player).level();
          var position = player.blockPosition();
          var origin =
              new BlockPos(
                  position.getX(), Math.max(position.getY(), level.getMinY() + 8), position.getZ());
          minecraftHealth = player.getHealth();
          sawDeath = sawRespawn = false;
          if (incoming()) server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
          double x = origin.getX() + .5, y = origin.getY(), z = origin.getZ() + .5;
          // Only the private QA copy is selected by this smoke path.
          for (int dx = -1; dx <= 7; dx++)
            for (int dz = -2; dz <= 2; dz++)
              for (int dy = -1; dy <= 3; dy++)
                level.setBlockAndUpdate(
                    origin.offset(dx, dy, dz),
                    (dy == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
          wall.clear();
          coverPassed = false;
          // Cover belongs to the outgoing-shot fixture. It overlaps the incoming zombie spawn.
          if (!incoming())
            for (int dy = 0; dy < 3; dy++)
              for (int dz = -1; dz <= 1; dz++) {
                var pos = origin.offset(2, dy, dz);
                wall.add(pos);
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
              }
          var mob =
              Objects.requireNonNull(
                  (incoming() ? EntityTypes.ZOMBIE : EntityTypes.IRON_GOLEM)
                      .create(level, EntitySpawnReason.COMMAND));
          mob.setNoAi(!incoming());
          if (!incoming())
            mob.setCustomName(net.minecraft.network.chat.Component.literal("Bridge Guardian"));
          if (incoming()) {
            mob.setItemSlot(
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            Objects.requireNonNull(
                    mob.getAttribute(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE))
                .setBaseValue(18);
          }
          mob.setPersistenceRequired();
          mob.setPos(x + (incoming() ? 1.3 : 5), y, z);
          level.addFreshEntity(mob);
          target = mob.getUUID();
          player.connection.teleport(x, y, z, -90, 0);
          return null;
        });
  }

  public static void step(Minecraft client, BridgeGame game, int frame) {
    if (dev.bluevista.craftq3.fabric.render.BridgeConsoleSmoke.enabled()
        || dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke.enabled()) return;
    if (BridgeTravelSmoke.enabled()) {
      BridgeTravelSmoke.step(client, game, frame);
      return;
    }
    if (BridgePickupSmoke.enabled()) {
      BridgePickupSmoke.step(game, frame);
      return;
    }
    if (BridgeTransferSmoke.enabled()) {
      BridgeTransferSmoke.step(game, frame);
      return;
    }
    if (BridgeLoadoutSmoke.enabled()) {
      BridgeLoadoutSmoke.step(game, frame);
      return;
    }
    if (dev.bluevista.craftq3.fabric.render.BridgeCameraSmoke.enabled()) {
      dev.bluevista.craftq3.fabric.render.BridgeCameraSmoke.step(client, game, frame);
      return;
    }
    if (BridgeOutgoingImpulseSmoke.enabled()) {
      BridgeOutgoingImpulseSmoke.step(client, game, frame);
      return;
    }
    if (BridgeExplosionSmoke.enabled()) {
      BridgeExplosionSmoke.step(client, game, frame);
      return;
    }
    if (BridgeKnockbackSmoke.enabled()) {
      BridgeKnockbackSmoke.step(client, game, frame);
      return;
    }
    if (BridgeBlazeSmoke.enabled()) {
      BridgeBlazeSmoke.step(client, game);
      return;
    }
    if (BridgeHitboxSmoke.enabled()) {
      BridgeHitboxSmoke.step(client, game);
      return;
    }
    if (BridgeFireballSmoke.enabled()) {
      BridgeFireballSmoke.step(client, game);
      return;
    }
    if (BridgeFireSmoke.enabled()) {
      BridgeFireSmoke.step(client, game);
      return;
    }
    if (BridgeFluidSmoke.enabled()) {
      BridgeFluidSmoke.step(client, game);
      return;
    }
    if (BridgeRangedSmoke.enabled()) {
      BridgeRangedSmoke.step(client, game);
      return;
    }
    if (incoming()) {
      if (frame == 20 || frame == 100 || frame == 200) {
        var host = Objects.requireNonNull(client.getSingleplayerServer());
        var id = Objects.requireNonNull(client.player).getUUID();
        host.execute(
            () -> {
              var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
              var entity = player.level().getEntity(target);
              if (entity instanceof Mob mob) {
                if (frame == 20) mob.setTarget(player);
                System.out.println(
                    "CraftQ3 incoming fixture frame="
                        + frame
                        + " mob="
                        + mob.position()
                        + " target="
                        + mob.getTarget()
                        + " clear="
                        + player.level().noCollision(mob, mob.getBoundingBox())
                        + " dimension="
                        + player.level().dimension()
                        + " invulnerable="
                        + player.getAbilities().invulnerable
                        + " distance="
                        + mob.distanceTo(player));
              }
            });
      }
      if (game.health() <= 0 && !sawDeath) {
        sawDeath = true;
        var server = Objects.requireNonNull(client.getSingleplayerServer());
        var id = Objects.requireNonNull(client.player).getUUID();
        server.execute(
            () -> {
              var player = Objects.requireNonNull(server.getPlayerList().getPlayer(id));
              var mob = player.level().getEntity(target);
              if (mob != null) mob.discard();
              if (player.getHealth() != minecraftHealth)
                throw new IllegalStateException("Minecraft health changed during Quake damage");
            });
      }
      if (sawDeath) {
        game.input().key(178, true, game.time());
        sawRespawn |= game.health() > 0;
      }
      return;
    }
    if (frame == 25) game.input().key(178, true, game.time());
    if (frame == 80) {
      var server = Objects.requireNonNull(client.getSingleplayerServer());
      var id = Objects.requireNonNull(client.player).getUUID();
      server.execute(
          () -> {
            var player = server.getPlayerList().getPlayer(id);
            var level = Objects.requireNonNull(player).level();
            var mob = level.getEntity(target);
            if (!(mob instanceof Mob living) || living.getHealth() != living.getMaxHealth())
              throw new IllegalStateException("Minecraft wall failed to block Quake damage");
            coverPassed = true;
            for (var pos : wall) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
          });
    }
    if (frame == 230) game.input().key(178, false, game.time());
  }

  public static String result(BridgeGame game) {
    if (dev.bluevista.craftq3.fabric.render.BridgeConsoleSmoke.enabled())
      return dev.bluevista.craftq3.fabric.render.BridgeConsoleSmoke.result();
    if (dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke.enabled())
      return dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke.result();
    if (BridgeTravelSmoke.enabled()) return BridgeTravelSmoke.result(game);
    if (BridgePickupSmoke.enabled()) return BridgePickupSmoke.result(game);
    if (BridgeTransferSmoke.enabled()) return BridgeTransferSmoke.result(game);
    if (BridgeLoadoutSmoke.enabled()) return BridgeLoadoutSmoke.result(game);
    if (dev.bluevista.craftq3.fabric.render.BridgeCameraSmoke.enabled())
      return dev.bluevista.craftq3.fabric.render.BridgeCameraSmoke.result(Minecraft.getInstance());
    if (BridgeOutgoingImpulseSmoke.enabled()) return BridgeOutgoingImpulseSmoke.result();
    if (BridgeExplosionSmoke.enabled()) return BridgeExplosionSmoke.result(game);
    if (BridgeKnockbackSmoke.enabled()) return BridgeKnockbackSmoke.result(game);
    if (BridgeRangedSmoke.enabled()) return BridgeRangedSmoke.result(game);
    if (BridgeFluidSmoke.enabled()) return BridgeFluidSmoke.result(game);
    if (BridgeFireSmoke.enabled()) return BridgeFireSmoke.result(game);
    if (BridgeFireballSmoke.enabled()) return BridgeFireballSmoke.result(game);
    if (BridgeHitboxSmoke.enabled()) return BridgeHitboxSmoke.result(game);
    if (BridgeBlazeSmoke.enabled()) return BridgeBlazeSmoke.result(game);
    if (incoming()) {
      if (!sawDeath || !sawRespawn || game.combat().incomingHits() < 2)
        throw new IllegalStateException(
            "Incoming bridge combat incomplete: hits="
                + game.combat().incomingHits()
                + " health="
                + game.health()
                + " death="
                + sawDeath
                + " respawn="
                + sawRespawn);
      return "incomingHits="
          + game.combat().incomingHits()
          + " death=true respawn=true health="
          + game.health();
    }
    if (!coverPassed
        || game.combat() == null
        || game.combat().appliedHits() < 10
        || game.combat().killedTargets() < 1)
      throw new IllegalStateException(
          "Minecraft combat incomplete: cover="
              + coverPassed
              + " applied="
              + (game.combat() == null ? 0 : game.combat().appliedHits()));
    return "cover=true appliedHits="
        + game.combat().appliedHits()
        + " killedTargets="
        + game.combat().killedTargets();
  }
}
