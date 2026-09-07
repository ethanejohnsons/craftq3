package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Native skeleton attack/projectile integration; scheduling replaces only the QA attack goal. */
public final class BridgeRangedSmoke {
  private static final List<BlockPos> wall = new ArrayList<>();
  private static AbstractSkeleton skeleton;
  private static float minecraftHealth;
  private static int lastShot, shots, previousHealth = -1, largestDrop;
  private static boolean covered, finished;
  private static CompletableFuture<String> pending;
  private static String evidence;
  private static UUID playerId;
  private static int impulses, impulseTick;
  private static double resistance, startX, startY, startZ, wallEdge, away, up;
  private static net.minecraft.world.phys.Vec3 lastImpulse;
  private static boolean resisted;

  private BridgeRangedSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeRangedSmoke");
  }

  public static void observe(
      ServerPlayer player,
      net.minecraft.world.phys.Vec3 impulse,
      net.minecraft.world.phys.Vec3 remaining) {
    if (!enabled() || !player.getUUID().equals(playerId)) return;
    if (remaining.lengthSqr() != 0)
      throw new IllegalStateException("Duplicate native arrow impulse");
    lastImpulse = impulse;
    impulseTick = player.level().getServer().getTickCount();
    impulses++;
    System.out.println(
        "CraftQ3 native arrow hit="
            + impulses
            + " impulse="
            + impulse
            + " native-motion-zero=true");
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    playerId = id;
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
          host.setDifficulty(Difficulty.NORMAL, true);
          player.setGameMode(GameType.CREATIVE);
          var origin = player.blockPosition();
          for (int dx = -4; dx <= 9; dx++)
            for (int dz = -3; dz <= 3; dz++)
              for (int dy = -1; dy <= 4; dy++)
                level.setBlockAndUpdate(
                    origin.offset(dx, dy, dz),
                    (dy == -1 || dx == -2 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12)))
            mob.discard();
          wall.clear();
          for (int dy = 0; dy < 4; dy++)
            for (int dz = -3; dz <= 3; dz++) {
              var pos = origin.offset(2, dy, dz);
              wall.add(pos);
              level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            }
          skeleton =
              Objects.requireNonNull(EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND));
          skeleton.setNoAi(true);
          skeleton.setPersistenceRequired();
          var bow = new ItemStack(Items.BOW);
          bow.enchant(
              level
                  .registryAccess()
                  .lookupOrThrow(Registries.ENCHANTMENT)
                  .getOrThrow(Enchantments.PUNCH),
              2);
          skeleton.setItemSlot(EquipmentSlot.MAINHAND, bow);
          skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
          skeleton.setPos(origin.getX() + 6.5, origin.getY(), origin.getZ() + .5);
          level.addFreshEntity(skeleton);
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          minecraftHealth = player.getHealth();
          var attribute =
              Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE));
          resistance = attribute.getBaseValue();
          attribute.setBaseValue(1);
          startX = origin.getX() + .5;
          startY = origin.getY();
          startZ = origin.getZ() + .5;
          wallEdge = origin.getX() - 1;
          away = up = 0;
          impulses = 0;
          lastImpulse = net.minecraft.world.phys.Vec3.ZERO;
          resisted = false;
          lastShot = host.getTickCount() - 40;
          shots = largestDrop = 0;
          previousHealth = -1;
          covered = finished = false;
          pending = null;
          evidence = null;
          return null;
        });
  }

  public static void step(Minecraft client, BridgeGame game) {
    if (previousHealth > 0) largestDrop = Math.max(largestDrop, previousHealth - game.health());
    previousHealth = game.health();
    if (pending != null) {
      if (!pending.isDone()) return;
      var result = pending.join();
      pending = null;
      if (result != null) {
        evidence = result;
        finished = true;
      }
    }
    if (finished) return;
    var feet = game.feet();
    away = Math.max(away, startX - feet.x());
    up = Math.max(up, feet.y() - startY);
    if (feet.x() < wallEdge + 15.0 / 32 - .02)
      throw new IllegalStateException("Arrow knockback crossed native wall: " + feet);
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    double observedAway = away, observedUp = up;
    pending =
        host.submit(
            () ->
                advance(
                    Objects.requireNonNull(host.getPlayerList().getPlayer(id)),
                    host.getTickCount(),
                    feet,
                    observedAway,
                    observedUp));
  }

  private static String advance(
      ServerPlayer player,
      int tick,
      dev.bluevista.craftq3.core.math.Vec3 feet,
      double observedAway,
      double observedUp) {
    var controller = BridgePlayerController.get(player);
    if (controller == null) return null;
    var level = player.level();
    if (player.getHealth() != minecraftHealth)
      throw new IllegalStateException("Arrow changed Minecraft health");
    var arrows =
        level.getEntitiesOfClass(
            AbstractArrow.class,
            player.getBoundingBox().inflate(16),
            arrow -> arrow.getOwner() == skeleton);
    if (!covered) {
      if (controller.hits() != 0)
        throw new IllegalStateException("Arrow passed through native cover");
      if (arrows.stream()
          .anyMatch(
              arrow ->
                  arrow.getDeltaMovement().lengthSqr() == 0
                      && wall.contains(BlockPos.containing(arrow.position().add(-.1, 0, 0))))) {
        covered = true;
        for (var pos : wall) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        for (var arrow : arrows) arrow.discard();
        lastShot = tick - 40;
        System.out.println("CraftQ3 ranged cover PASS native-arrow=true shots=" + shots);
      }
    } else if (!resisted && impulses == 1 && tick - impulseTick >= 15) {
      if (lastImpulse.lengthSqr() != 0
          || observedAway > .02
          || observedUp > .02
          || Math.abs(feet.z() - startZ) > .02)
        throw new IllegalStateException("Arrow ignored full native knockback resistance");
      resisted = true;
      Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).setBaseValue(0);
      lastShot = tick - 40;
      System.out.println("CraftQ3 ranged resistance PASS native-punch=true quake-motion-zero=true");
    } else if (resisted && impulses == 2 && tick - impulseTick >= 40) {
      if (lastImpulse.x >= -.5
          || Math.abs(lastImpulse.y - .1) > 1e-6
          || observedAway < .25
          || observedUp < .01
          || Math.abs(feet.x() - (wallEdge + 15.0 / 32)) > .06)
        throw new IllegalStateException(
            "Missing original Quake arrow response: impulse="
                + lastImpulse
                + " away="
                + observedAway
                + " up="
                + observedUp
                + " feet="
                + feet);
      for (var arrow : arrows) arrow.discard();
      skeleton.discard();
      Objects.requireNonNull(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE))
          .setBaseValue(resistance);
      return "ranged=true cover=true native-arrows=true incomingHits="
          + controller.hits()
          + " minecraft-health=true shots="
          + shots
          + " punch=true resistance=true quake-motion=true native-wall=true"
          + " duplicate-motion=false away="
          + observedAway
          + " up="
          + observedUp;
    }
    if (impulses < (resisted ? 2 : 1) && tick - lastShot >= 40) {
      skeleton.performRangedAttack(player, 1);
      shots++;
      lastShot = tick;
    }
    return null;
  }

  public static String result(BridgeGame game) {
    if (!finished
        || !covered
        || !resisted
        || impulses != 2
        || largestDrop < 5
        || game.combat().incomingHits() != 2)
      throw new IllegalStateException(
          "Ranged bridge incomplete: finished="
              + finished
              + " covered="
              + covered
              + " shots="
              + shots
              + " hits="
              + game.combat().incomingHits()
              + " drop="
              + largestDrop);
    return evidence + " quake-damage=true largest-drop=" + largestDrop;
  }
}
