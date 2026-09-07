package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Original bullets and backstop rocket splash transfer direction to native mob physics. */
public final class BridgeOutgoingImpulseSmoke {
  private static Mob target;
  private static BlockPos origin;
  private static int phase, cycle, changed;
  private static long startDelivered;
  private static volatile int observed;
  private static volatile Vec3 lastQuake = new Vec3(0, 0, 0);
  private static volatile Vec3 lastNative = new Vec3(0, 0, 0);
  private static float startingHealth;
  private static double startingX, bulletMovement, splashMovement;
  private static CompletableFuture<Void> pending;
  private static boolean finished;

  private BridgeOutgoingImpulseSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeOutgoingImpulseSmoke");
  }

  public static void observe(
      Mob mob, Vec3 quake, Vec3 converted, net.minecraft.world.phys.Vec3 before) {
    if (!enabled() || mob != target) return;
    var delta = mob.getDeltaMovement().subtract(before);
    if (delta.subtract(converted.x(), converted.y(), converted.z()).lengthSqr() > 1e-12)
      throw new IllegalStateException(
          "Native damage added a duplicate impulse: " + delta + " != " + converted);
    lastQuake = quake;
    lastNative = converted;
    observed++;
    System.out.println(
        "CraftQ3 outgoing impulse cycle=" + cycle + " q3=" + quake + " mc=" + converted);
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
          origin = player.blockPosition();
          for (int x = -3; x <= 12; x++)
            for (int z = -5; z <= 5; z++)
              for (int y = -1; y <= 5; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1 || x == 7 ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(20)))
            mob.discard();
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          target =
              Objects.requireNonNull(
                  EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND));
          prepareTarget();
          target.setInvulnerable(true);
          level.addFreshEntity(target);
          phase = cycle = observed = 0;
          finished = false;
          pending = null;
          bulletMovement = splashMovement = 0;
          return null;
        });
  }

  private static void prepareTarget() {
    target.removeAllGoals(goal -> true);
    target.setPersistenceRequired();
    target.setPos(origin.getX() + 5.5, origin.getY(), origin.getZ() + .5);
    target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    startingHealth = target.getHealth();
    startingX = target.getX();
    observed = 0;
  }

  public static void step(Minecraft client, BridgeGame game, int frame) {
    if (frame == 5) game.command("give all");
    if (frame < 30 || finished) return;
    if (pending != null) {
      if (!pending.isDone()) return;
      pending.join();
      pending = null;
    }
    if (phase == 0) {
      startDelivered = game.combat().deliveredHits();
      game.input().key(178, true, game.time());
      changed = frame;
      phase = 1;
    } else if (phase == 1 && frame - changed >= 30) {
      game.input().key(178, false, game.time());
      changed = frame;
      phase = 2;
    } else if (phase == 2 && frame - changed >= 80) {
      if (game.combat().deliveredHits() <= startDelivered)
        throw new IllegalStateException("Original bullet shots missed the QA target");
      var host = Objects.requireNonNull(client.getSingleplayerServer());
      pending =
          host.submit(
              () -> {
                verifyBullets();
                return null;
              });
      phase = 3;
    } else if (phase == 3) {
      cycle++;
      if (cycle < 3) phase = 0;
      else {
        game.command("weapon 5");
        changed = frame;
        phase = 4;
      }
    } else if (phase == 4 && frame - changed >= 40) {
      game.input().key(178, true, game.time());
      changed = frame;
      phase = 5;
    } else if (phase == 5 && observed > 0) {
      game.input().key(178, false, game.time());
      changed = frame;
      phase = 6;
    } else if (phase == 6 && frame - changed >= 100) {
      var host = Objects.requireNonNull(client.getSingleplayerServer());
      pending =
          host.submit(
              () -> {
                splashMovement = startingX - target.getX();
                if (lastQuake.x() >= -10 || lastNative.x() >= 0 || splashMovement < .05)
                  throw new IllegalStateException(
                      "Backstop splash did not push toward shooter: q3="
                          + lastQuake
                          + " mc="
                          + lastNative
                          + " moved="
                          + splashMovement);
                target.discard();
                return null;
              });
      phase = 7;
    } else if (phase == 7) finished = true;
  }

  private static void verifyBullets() {
    double moved = target.getX() - startingX;
    if (cycle == 0) {
      if (observed != 0 || target.getHealth() != startingHealth || Math.abs(moved) > .01)
        throw new IllegalStateException("Invulnerable mob received damage/impulse");
      target.setInvulnerable(false);
      Objects.requireNonNull(target.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).setBaseValue(1);
    } else if (cycle == 1) {
      if (observed == 0
          || lastQuake.x() <= 0
          || lastNative.x() != 0
          || Math.abs(moved) > .01
          || target.getHealth() >= startingHealth)
        throw new IllegalStateException(
            "Native resistance failed: q3=" + lastQuake + " mc=" + lastNative + " moved=" + moved);
      Objects.requireNonNull(target.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).setBaseValue(0);
    } else {
      bulletMovement = moved;
      if (observed == 0
          || lastQuake.x() <= 0
          || lastNative.x() <= 0
          || moved < .02
          || target.getHealth() >= startingHealth)
        throw new IllegalStateException(
            "Original bullets did not push native mob: q3="
                + lastQuake
                + " mc="
                + lastNative
                + " moved="
                + moved);
      var level = target.level();
      target.discard();
      target = Objects.requireNonNull(EntityTypes.CHICKEN.create(level, EntitySpawnReason.COMMAND));
      prepareTarget();
      target.setAbsorptionAmount(20);
      level.addFreshEntity(target);
      return;
    }
    target.setHealth(target.getMaxHealth());
    prepareTarget();
  }

  public static String result() {
    if (pending != null) pending.join();
    if (!finished)
      throw new IllegalStateException(
          "Outgoing impulse fixture incomplete: phase=" + phase + " cycle=" + cycle);
    return "outgoing-impulse=true invulnerable=true resistance=true bullet-away=true"
        + " splash-toward=true duplicate-free=true bullet-moved="
        + bulletMovement
        + " splash-moved="
        + splashMovement;
  }
}
