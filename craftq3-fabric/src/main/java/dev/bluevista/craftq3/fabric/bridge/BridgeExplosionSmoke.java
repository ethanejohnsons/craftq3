package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Actual ignited creepers: native cover/resistance and Quake response to an exposed blast. */
public final class BridgeExplosionSmoke {
  private static UUID playerId;
  private static BlockPos origin;
  private static Creeper creeper;
  private static int stage, explosionTick, initialHealth;
  private static volatile int blasts;
  private static volatile Vec3 lastImpulse = Vec3.ZERO;
  private static double resistance, startX, startY, startZ, away, up;
  private static float minecraftHealth;
  private static CompletableFuture<Void> pending;
  private static volatile boolean finished;

  private BridgeExplosionSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeExplosionSmoke");
  }

  public static void observe(ServerPlayer player, Vec3 impulse, Vec3 packetImpulse) {
    if (!enabled() || !player.getUUID().equals(playerId)) return;
    if (packetImpulse.lengthSqr() != 0)
      throw new IllegalStateException("Duplicate native explosion impulse");
    lastImpulse = impulse;
    explosionTick = player.level().getServer().getTickCount();
    blasts++;
    System.out.println(
        "CraftQ3 native creeper blast=" + blasts + " impulse=" + impulse + " packet-zero=true");
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    playerId = Objects.requireNonNull(client.player).getUUID();
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Requires private QA save");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(playerId));
          var level = player.level();
          origin = player.blockPosition();
          host.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
          for (int x = -5; x <= 8; x++)
            for (int z = -5; z <= 5; z++)
              for (int y = -1; y <= 5; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1 || x == -2 || x == 2 ? Blocks.OBSIDIAN : Blocks.AIR)
                        .defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(20)))
            mob.discard();
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          var attribute =
              Objects.requireNonNull(
                  player.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE));
          resistance = attribute.getBaseValue();
          attribute.setBaseValue(0);
          minecraftHealth = player.getHealth();
          stage = blasts = 0;
          away = up = 0;
          finished = false;
          pending = null;
          lastImpulse = Vec3.ZERO;
          return null;
        });
  }

  public static void step(Minecraft client, BridgeGame game, int frame) {
    if (frame == 5) game.command("give all");
    if (frame < 30 || finished) return;
    if (pending != null) {
      if (!pending.isDone()) return;
      pending.join();
      pending = null;
    }
    var feet = game.feet();
    int health = game.health();
    if (stage == 0) {
      startX = feet.x();
      startY = feet.y();
      startZ = feet.z();
      initialHealth = health;
    } else {
      away = Math.max(away, startX - feet.x());
      up = Math.max(up, feet.y() - startY);
      if (feet.x() < origin.getX() - 1 + 15.0 / 32 - .02)
        throw new IllegalStateException("Blast crossed native wall");
      if (health <= 0)
        throw new IllegalStateException("Explosion QA player died before motion checks");
    }
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    pending =
        host.submit(
            () -> {
              var player = Objects.requireNonNull(host.getPlayerList().getPlayer(playerId));
              if (player.getHealth() != minecraftHealth)
                throw new IllegalStateException("Explosion changed Minecraft health");
              if (stage == 0) {
                ignite(player, true);
                stage = 1;
              } else if (blasts == stage
                  && creeper.isRemoved()
                  && host.getTickCount() - explosionTick >= 25) {
                if (stage < 3) {
                  if (lastImpulse.lengthSqr() > 1e-12
                      || away > .02
                      || up > .02
                      || Math.abs(feet.z() - startZ) > .02)
                    throw new IllegalStateException(
                        "Blast cover/resistance failed: stage="
                            + stage
                            + " impulse="
                            + lastImpulse
                            + " away="
                            + away
                            + " up="
                            + up);
                  if (stage == 1) {
                    for (int z = -5; z <= 5; z++)
                      for (int y = 0; y <= 5; y++)
                        player
                            .level()
                            .setBlockAndUpdate(
                                origin.offset(2, y, z), Blocks.AIR.defaultBlockState());
                    Objects.requireNonNull(
                            player.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE))
                        .setBaseValue(1);
                  } else
                    Objects.requireNonNull(
                            player.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE))
                        .setBaseValue(0);
                  ignite(player, false);
                  stage++;
                } else {
                  if (lastImpulse.x() >= -.1
                      || lastImpulse.y() <= 0
                      || away < .25
                      || up < .05
                      || Math.abs(feet.x() - (origin.getX() - 1 + 15.0 / 32)) > .06
                      || health >= initialHealth)
                    throw new IllegalStateException(
                        "Missing explosion response: impulse="
                            + lastImpulse
                            + " away="
                            + away
                            + " up="
                            + up
                            + " health="
                            + health
                            + " feet="
                            + feet);
                  Objects.requireNonNull(
                          player.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE))
                      .setBaseValue(resistance);
                  finished = true;
                }
              }
              return null;
            });
  }

  private static void ignite(ServerPlayer player, boolean covered) {
    creeper =
        Objects.requireNonNull(
            EntityTypes.CREEPER.create(player.level(), EntitySpawnReason.COMMAND));
    creeper.setNoAi(true);
    creeper.setPersistenceRequired();
    creeper.setPos(origin.getX() + 4, origin.getY(), origin.getZ() + .5);
    if (!player.level().addFreshEntity(creeper))
      throw new IllegalStateException("Creeper spawn failed");
    float exposure = ServerExplosion.getSeenPercent(creeper.position(), player);
    if (covered ? exposure != 0 : exposure < .99)
      throw new IllegalStateException("Incorrect native exposure " + exposure);
    creeper.ignite();
  }

  public static String result(BridgeGame game) {
    if (pending != null) pending.join();
    if (!finished || blasts != 3 || game.combat().incomingHits() < 2)
      throw new IllegalStateException(
          "Explosion fixture incomplete: stage="
              + stage
              + " blasts="
              + blasts
              + " hits="
              + game.combat().incomingHits());
    return "explosion=true ignited-creepers=3 cover=true resistance=true packet-zero=true"
        + " quake-motion=true wall=true minecraft-health=true away="
        + away
        + " up="
        + up;
  }
}
