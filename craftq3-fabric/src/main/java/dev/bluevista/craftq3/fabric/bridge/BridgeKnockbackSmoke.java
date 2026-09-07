package dev.bluevista.craftq3.fabric.bridge;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Native melee knockback, resistance, and original Quake motion into a native wall. */
public final class BridgeKnockbackSmoke {
  private static Mob zombie;
  private static double initialResistance, startX, startY, startZ, wallEdge;
  private static float minecraftHealth;
  private static int stage, firstTick, initialHealth;
  private static double away, up;
  private static CompletableFuture<Void> pending;
  private static volatile boolean finished;

  private BridgeKnockbackSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeKnockbackSmoke");
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Requires private QA save");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
          var level = player.level();
          var origin = player.blockPosition();
          host.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
          for (int x = -5; x <= 5; x++)
            for (int z = -4; z <= 4; z++)
              for (int y = -1; y <= 5; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1 || x == -2 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16)))
            mob.discard();
          zombie =
              Objects.requireNonNull(EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND));
          zombie.setNoAi(true);
          zombie.setPersistenceRequired();
          zombie.setItemSlot(
              EquipmentSlot.HEAD,
              new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
          zombie.setPos(origin.getX() + 1.8, origin.getY(), origin.getZ() + .5);
          level.addFreshEntity(zombie);
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          var resistance =
              Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE));
          initialResistance = resistance.getBaseValue();
          resistance.setBaseValue(1);
          minecraftHealth = player.getHealth();
          wallEdge = origin.getX() - 1;
          stage = 0;
          away = up = 0;
          finished = false;
          pending = null;
          return null;
        });
  }

  public static void step(Minecraft client, BridgeGame game, int frame) {
    if (frame < 30 || finished) return;
    if (pending != null) {
      if (!pending.isDone()) return;
      pending.join();
      pending = null;
    }
    var feet = game.feet();
    if (stage == 0) {
      startX = feet.x();
      startY = feet.y();
      startZ = feet.z();
      initialHealth = game.health();
    } else {
      away = Math.max(away, startX - feet.x());
      up = Math.max(up, feet.y() - startY);
      if (feet.x() < wallEdge + 15.0 / 32 - .02)
        throw new IllegalStateException("Knockback crossed native wall: " + feet);
    }
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    int health = game.health();
    pending =
        host.submit(
            () -> {
              var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
              if (player.getHealth() != minecraftHealth)
                throw new IllegalStateException("Minecraft health changed");
              if (stage == 0) {
                attack(player);
                firstTick = host.getTickCount();
                stage = 1;
              } else if (stage == 1
                  && host.getTickCount() - firstTick >= 15
                  && health < initialHealth) {
                if (Math.abs(feet.x() - startX) > .02
                    || Math.abs(feet.z() - startZ) > .02
                    || up > .02)
                  throw new IllegalStateException(
                      "Full native resistance did not suppress impulse");
                Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE))
                    .setBaseValue(0);
                attack(player);
                firstTick = host.getTickCount();
                stage = 2;
              } else if (stage == 2 && host.getTickCount() - firstTick >= 30) {
                if (away < .25 || up < .1 || Math.abs(feet.x() - (wallEdge + 15.0 / 32)) > .06)
                  throw new IllegalStateException(
                      "Missing Quake knockback/wall response: away="
                          + away
                          + " up="
                          + up
                          + " feet="
                          + feet);
                Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE))
                    .setBaseValue(initialResistance);
                zombie.discard();
                finished = true;
              }
              return null;
            });
  }

  private static void attack(ServerPlayer player) {
    if (!zombie.doHurtTarget(player.level(), player))
      throw new IllegalStateException("Native melee rejected");
  }

  public static String result(BridgeGame game) {
    if (pending != null) pending.join();
    if (!finished || game.combat().incomingHits() != 2)
      throw new IllegalStateException(
          "Knockback fixture incomplete: stage=" + stage + " hits=" + game.combat().incomingHits());
    return "knockback=true native-melee=true resistance=true quake-motion=true wall=true"
        + " minecraft-health=true away="
        + away
        + " up="
        + up;
  }
}
