package dev.bluevista.craftq3.fabric.bridge;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Original swimming/world effects over real native fluid cells in the private QA world. */
public final class BridgeFluidSmoke {
  private static BlockPos origin;
  private static float nativeHealth;
  private static CompletableFuture<Void> pending;
  private static int stage, since, previousHealth, drops, drowned, burned, protectedHealth;
  private static double startY, rise;
  private static boolean finished;

  private BridgeFluidSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeFluidSmoke");
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    stage = drops = 0;
    finished = false;
    rise = 0;
    pending = null;
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Fluid QA requires private save");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
          var level = player.level();
          origin = player.blockPosition();
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16)))
            mob.discard();
          for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
              for (int y = -1; y <= 6; y++) {
                boolean shell = y == -1 || Math.abs(x) == 4 || Math.abs(z) == 4;
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (shell ? Blocks.STONE : y < 5 ? Blocks.WATER : Blocks.AIR).defaultBlockState());
              }
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          nativeHealth = player.getHealth();
          return null;
        });
  }

  private static void fill(ServerLevel level, Block block) {
    for (int x = -3; x <= 3; x++)
      for (int z = -3; z <= 3; z++)
        for (int y = 0; y < 5; y++)
          level.setBlockAndUpdate(origin.offset(x, y, z), block.defaultBlockState());
  }

  private static void change(Minecraft client, Block block) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    pending =
        host.submit(
            () -> {
              var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
              if (player.getHealth() != nativeHealth)
                throw new IllegalStateException("Native fluid changed Minecraft health");
              if (BridgePlayerController.get(player).hits() != 0)
                throw new IllegalStateException(
                    "Native environment duplicated original Quake world damage");
              fill(player.level(), block);
              if (block == Blocks.AIR) player.clearFire();
              return null;
            });
  }

  public static void step(Minecraft client, BridgeGame game) {
    if (finished) return;
    if (pending != null) {
      if (!pending.isDone()) return;
      pending.join();
      pending = null;
    }
    int now = game.time(), health = game.health();
    if (stage == 0) {
      game.command("give health");
      since = now;
      stage = 1;
    } else if (stage == 1 && now - since >= 320) {
      if (health != 100) throw new IllegalStateException("Original health command not applied");
      startY = game.feet().y();
      game.input().key(32, true, now);
      since = now;
      stage = 2;
    } else if (stage == 2 && now - since >= 1000) {
      game.input().key(32, false, now);
      rise = game.feet().y() - startY;
      if (rise < 1) throw new IllegalStateException("No native-water Quake swimming: rise=" + rise);
      since = now;
      stage = 3;
    } else if (stage == 3 && now - since >= 6000) {
      if (health != 100)
        throw new IllegalStateException(
            "Water damaged player before original air expired: " + health);
      previousHealth = health;
      since = now;
      stage = 4;
    } else if (stage == 4) {
      if (previousHealth - health > 1) drops++;
      previousHealth = health;
      if (drops >= 2) {
        drowned = health;
        change(client, Blocks.AIR);
        since = now;
        stage = 5;
      } else if (now - since > 12000)
        throw new IllegalStateException("No original drowning in native water");
    } else if (stage == 5 && now - since >= 1500) {
      previousHealth = health;
      since = now;
      stage = 6;
    } else if (stage == 6 && now - since >= 1500) {
      if (health != previousHealth)
        throw new IllegalStateException("Drowning continued in native air");
      game.command("give health");
      since = now;
      stage = 7;
    } else if (stage == 7 && now - since >= 320) {
      if (health != 100) throw new IllegalStateException("Health not restored before lava");
      change(client, Blocks.LAVA);
      since = now;
      stage = 8;
    } else if (stage == 8) {
      if (health < 100) {
        if (health <= 0) throw new IllegalStateException("Duplicate or unexpected lava damage");
        burned = health;
        change(client, Blocks.AIR);
        since = now;
        stage = 9;
      } else if (now - since > 3000)
        throw new IllegalStateException("No Quake damage from native lava");
    } else if (stage == 9 && now - since >= 1000) {
      game.command("give health");
      game.command("give Battle Suit");
      since = now;
      stage = 10;
    } else if (stage == 10 && now - since >= 320) {
      if (health != 100 || game.loadout().powerupMillis().get(1) <= 0)
        throw new IllegalStateException("Original Battle Suit not applied");
      protectedHealth = health;
      change(client, Blocks.LAVA);
      since = now;
      stage = 11;
    } else if (stage == 11 && now - since >= 2500) {
      if (health != protectedHealth)
        throw new IllegalStateException("Battle Suit failed on native lava");
      if (!client.level.getBlockState(origin).is(Blocks.LAVA))
        throw new IllegalStateException("Protected interval did not contain native lava");
      change(client, Blocks.AIR);
      since = now;
      stage = 12;
    } else if (stage == 12 && now - since >= 500) {
      finished = true;
      System.out.println("CraftQ3 bridge fluids " + result(game));
    }
  }

  public static String result(BridgeGame game) {
    if (pending != null) pending.join();
    if (!finished || game.health() <= 0 || game.combat().incomingHits() != 0)
      throw new IllegalStateException(
          "Fluid fixture incomplete: stage=" + stage + " health=" + game.health());
    return "fluids=true swim-rise="
        + rise
        + " drowning=100->"
        + drowned
        + " air-stops-damage=true lava=100->"
        + burned
        + " battle-suit=true minecraft-health=true duplicate-environment-damage=false";
  }
}
