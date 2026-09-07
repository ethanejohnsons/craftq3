package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.LevelResource;

/** Native projectile rays distinguish original standing, crouched and wider Quake hulls. */
public final class BridgeHitboxSmoke {
  private record NativeShape(double width, double height, double eye) {
    static NativeShape of(Player player) {
      return new NativeShape(player.getBbWidth(), player.getBbHeight(), player.getEyeHeight());
    }
  }

  private static BlockPos origin;
  private static Mob owner;
  private static SmallFireball shot;
  private static NativeShape serverOriginal, clientOriginal;
  private static float nativeHealth;
  private static boolean originalInvulnerable;
  private static double originalResistance;
  private static int stage, since, hits, startTime;
  private static boolean missed, started, finished, waitForCeiling;
  private static CompletableFuture<String> pending;
  private static String evidence;

  private BridgeHitboxSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeHitboxSmoke");
  }

  public static void impact(SmallFireball ball, ServerPlayer player, boolean accepted) {
    if (!enabled() || ball != shot) return;
    if (!accepted)
      throw new IllegalStateException("Hitbox probe did not deliver native impact damage");
    if (stage == 1)
      throw new IllegalStateException("Overhead projectile hit crouched Quake player");
    hits++;
    player.clearFire();
  }

  public static void block(SmallFireball ball, BlockPos pos) {
    if (!enabled() || ball != shot) return;
    if (stage == 1 && pos.getX() == origin.getX() - 4) missed = true;
  }

  public static void restored(Player player) {
    if (!enabled()) return;
    var expected = player instanceof ServerPlayer ? serverOriginal : clientOriginal;
    var actual = NativeShape.of(player);
    if (!expected.equals(actual))
      throw new IllegalStateException(
          "Native shape did not restore: " + actual + " expected=" + expected);
    System.out.println(
        "CraftQ3 avatar restoration PASS side="
            + (player instanceof ServerPlayer ? "server" : "client")
            + " dimensions="
            + actual);
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    clientOriginal = NativeShape.of(client.player);
    stage = hits = 0;
    missed = started = finished = waitForCeiling = false;
    pending = null;
    evidence = null;
    shot = null;
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Hitbox QA requires private save");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
          var level = player.level();
          origin = player.blockPosition();
          serverOriginal = NativeShape.of(player);
          nativeHealth = player.getHealth();
          host.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
          originalInvulnerable = player.isInvulnerable();
          var resistance =
              Objects.requireNonNull(
                  player.getAttribute(
                      net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE));
          originalResistance = resistance.getBaseValue();
          resistance.setBaseValue(1);
          for (int x = -4; x <= 9; x++)
            for (int z = -3; z <= 3; z++)
              for (int y = -1; y <= 5; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1
                            ? Blocks.GLOWSTONE
                            : x == -4 || x == 9 || Math.abs(z) == 3 || y == 5
                                ? Blocks.STONE
                                : Blocks.AIR)
                        .defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16)))
            mob.discard();
          for (var projectile :
              level.getEntitiesOfClass(Projectile.class, player.getBoundingBox().inflate(16)))
            projectile.discard();
          owner =
              Objects.requireNonNull(EntityTypes.BLAZE.create(level, EntitySpawnReason.COMMAND));
          owner.setNoAi(true);
          owner.setNoGravity(true);
          owner.setPos(origin.getX() + 6.5, origin.getY(), origin.getZ() + .5);
          level.addFreshEntity(owner);
          player.clearFire();
          player.setInvulnerable(false);
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          return null;
        });
  }

  private static void verify(Player player, BridgePlayerShape expected) {
    var box = player.getBoundingBox();
    var min = expected.bounds().min();
    var max = expected.bounds().max();
    if (Math.abs(box.minX - player.getX() - min.x()) > 1e-6
        || Math.abs(box.minY - player.getY() - min.y()) > 1e-6
        || Math.abs(box.minZ - player.getZ() - min.z()) > 1e-6
        || Math.abs(box.maxX - player.getX() - max.x()) > 1e-6
        || Math.abs(box.maxY - player.getY() - max.y()) > 1e-6
        || Math.abs(box.maxZ - player.getZ() - max.z()) > 1e-6
        || Math.abs(player.getBbWidth() - expected.width()) > 1e-6
        || Math.abs(player.getBbHeight() - expected.height()) > 1e-6
        || Math.abs(player.getEyeHeight() - expected.eyeHeight()) > 1e-6)
      throw new IllegalStateException(
          "Native avatar disagrees with original Quake shape: " + box + " expected=" + expected);
  }

  private static void shoot(ServerPlayer player, double height, double side) {
    var start =
        new net.minecraft.world.phys.Vec3(
            origin.getX() + 5.8, origin.getY() + height, origin.getZ() + .5 + side);
    shot = new SmallFireball(player.level(), owner, new net.minecraft.world.phys.Vec3(-1, 0, 0));
    shot.setPos(start);
    player.level().addFreshEntity(shot);
  }

  private static void ceiling(ServerPlayer player, boolean present) {
    var block =
        present
            ? Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
            : Blocks.AIR.defaultBlockState();
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++) player.level().setBlockAndUpdate(origin.offset(x, 1, z), block);
  }

  public static void step(Minecraft client, BridgeGame game) {
    if (finished) return;
    if (!started) {
      started = true;
      startTime = game.time();
      game.command("give health");
      game.input().key('c', true, game.time());
    }
    verify(Objects.requireNonNull(client.player), game.playerShape());
    if (game.time() - startTime < 500) return;
    if (waitForCeiling && client.level.getBlockState(origin.above()).is(Blocks.STONE_SLAB)) {
      game.input().key('c', false, game.time());
      waitForCeiling = false;
    }
    if (pending != null) {
      if (!pending.isDone()) return;
      var result = pending.join();
      pending = null;
      if ("stand".equals(result)) game.input().key('c', false, game.time());
      else if ("crouch".equals(result)) game.input().key('c', true, game.time());
      else if ("ceiling".equals(result)) waitForCeiling = true;
      else if (result != null) {
        evidence = result;
        finished = true;
        return;
      }
    }
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = client.player.getUUID();
    var shape = game.playerShape();
    int health = game.health();
    boolean released = !waitForCeiling;
    pending =
        host.submit(
            () ->
                advance(
                    Objects.requireNonNull(host.getPlayerList().getPlayer(id)),
                    host.getTickCount(),
                    shape,
                    health,
                    released));
  }

  private static String advance(
      ServerPlayer player, int tick, BridgePlayerShape desired, int health, boolean released) {
    var controller = Objects.requireNonNull(BridgePlayerController.get(player));
    if (player.getHealth() != nativeHealth)
      throw new IllegalStateException("Native health changed in hitbox test");
    if (!desired.equals(controller.shape())) return null;
    verify(player, desired);
    if (stage == 0 && desired.height() == 1.25f) {
      shoot(player, 1.65, 0);
      stage = 1;
      since = tick;
    } else if (stage == 1 && missed) {
      if (hits != 0 || health != 100)
        throw new IllegalStateException("Crouch did not avoid overhead shot");
      stage = 2;
      since = tick;
      return "stand";
    } else if (stage == 2 && desired.height() == 1.75f) {
      shoot(player, 1.65, 0);
      stage = 3;
      since = tick;
    } else if (stage == 3 && hits == 1 && tick - since >= 30 && health == 75) {
      shoot(player, 1.0, .65);
      stage = 4;
      since = tick;
    } else if (stage == 4 && hits == 2 && health == 50) {
      stage = 5;
      since = tick;
      return "crouch";
    } else if (stage == 5 && desired.height() == 1.25f) {
      ceiling(player, true);
      stage = 6;
      since = tick;
      return "ceiling";
    } else if (stage == 6 && released && tick - since >= 40) {
      if (desired.height() != 1.25f)
        throw new IllegalStateException("Quake stood into low native slab ceiling");
      ceiling(player, false);
      stage = 7;
      since = tick;
    } else if (stage == 7 && desired.height() == 1.75f) {
      owner.discard();
      player.clearFire();
      player.setInvulnerable(originalInvulnerable);
      Objects.requireNonNull(
              player.getAttribute(
                  net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE))
          .setBaseValue(originalResistance);
      return "hitbox=true original-standing-crouch=true client-server-bounds=true eye-height=true"
          + " overhead-miss=true standing-hit=true wide-edge-hit=true low-ceiling=true"
          + " native-health=true quake-health="
          + health;
    }
    if (stage != 0 && tick - since > 160)
      throw new IllegalStateException(
          "Hitbox fixture timeout at stage " + stage + " shape=" + desired);
    return null;
  }

  public static String result(BridgeGame game) {
    if (pending != null) pending.join();
    if (!finished || !missed || hits != 2 || game.health() != 50)
      throw new IllegalStateException("Hitbox fixture incomplete at stage " + stage);
    return evidence;
  }
}
