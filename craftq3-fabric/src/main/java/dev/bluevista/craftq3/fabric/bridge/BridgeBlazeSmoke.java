package dev.bluevista.craftq3.fabric.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Real native blaze goals and original Quake weapons in the private QA world. */
public final class BridgeBlazeSmoke {
  private static UUID playerId;
  private static Blaze blaze;
  private static BlockPos origin;
  private static int stage, since, impacts, burns, coverTicks;
  private static float nativeHealth;
  private static boolean started, finished, invulnerable, weaponRequested;
  private static int startTime, baselineHealth, initialRockets;
  private static dev.bluevista.craftq3.core.math.Vec3 angleDelta;
  private static final Set<UUID> shots = new HashSet<>();
  private static CompletableFuture<Observation> pending;
  private static String evidence;

  private record Observation(boolean attack, Vec3 target, String evidence) {}

  private BridgeBlazeSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeBlazeSmoke");
  }

  public static void impact(SmallFireball ball, ServerPlayer player, boolean accepted) {
    if (enabled() && player.getUUID().equals(playerId) && ball.getOwner() == blaze && accepted) {
      shots.add(ball.getUUID());
      impacts++;
      System.out.println("CraftQ3 blaze AI impact=" + impacts);
    }
  }

  public static void damage(ServerPlayer player, DamageSource source) {
    if (enabled() && player.getUUID().equals(playerId) && source.is(DamageTypes.ON_FIRE)) burns++;
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    playerId = Objects.requireNonNull(client.player).getUUID();
    stage = impacts = burns = coverTicks = 0;
    started = finished = weaponRequested = false;
    angleDelta = null;
    pending = null;
    evidence = null;
    shots.clear();
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Blaze QA requires private save");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(playerId));
          var level = player.level();
          host.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
          origin = player.blockPosition();
          for (int x = -3; x <= 13; x++)
            for (int z = -4; z <= 4; z++)
              for (int y = -1; y <= 6; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1
                            ? Blocks.GLOWSTONE
                            : x == -3 || x == 13 || Math.abs(z) == 4 || y == 6 || x == 4
                                ? Blocks.STONE
                                : Blocks.AIR)
                        .defaultBlockState());
          for (var entity :
              level.getEntities(
                  player,
                  player.getBoundingBox().inflate(24),
                  e -> e instanceof Mob || e instanceof Projectile)) entity.discard();
          blaze =
              Objects.requireNonNull(EntityTypes.BLAZE.create(level, EntitySpawnReason.COMMAND));
          blaze.setPersistenceRequired();
          blaze.setPos(origin.getX() + 10.5, origin.getY(), origin.getZ() + .5);
          level.addFreshEntity(blaze);
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          player.clearFire();
          nativeHealth = player.getHealth();
          invulnerable = player.isInvulnerable();
          player.setInvulnerable(false);
          return null;
        });
  }

  public static void step(Minecraft client, BridgeGame game) {
    if (finished) return;
    if (!started) {
      started = true;
      startTime = game.time();
      game.command("give all");
      game.input().angles(0, 0, 0);
      return;
    }
    if (angleDelta == null) angleDelta = game.viewAngles();
    if (game.time() - startTime < 320) {
      initialRockets = game.loadout().ammo().get(5);
      return;
    }
    if (!weaponRequested) {
      game.command("weapon 5");
      weaponRequested = true;
      return;
    }
    if (game.time() - startTime < 640) return;
    if (pending != null) {
      if (!pending.isDone()) return;
      var observation = pending.join();
      pending = null;
      if (observation.evidence() != null) {
        if (game.loadout().ammo().get(5) >= initialRockets)
          throw new IllegalStateException("Blaze died without original rocket ammo consumption");
        game.input().key(178, false, game.time());
        evidence = observation.evidence();
        finished = true;
        return;
      }
      if (observation.attack()) {
        if (game.selectedWeapon() != 5 || game.loadout().weapon() != 5)
          throw new IllegalStateException("Blaze encounter requires the original rocket launcher");
        var feet = game.feet();
        var direction =
            observation
                .target()
                .subtract(feet.x(), feet.y() + game.playerShape().eyeHeight(), feet.z());
        double yaw = Math.toDegrees(Math.atan2(-direction.z, direction.x));
        double pitch =
            -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        game.input().angles(pitch - angleDelta.x(), yaw - angleDelta.y(), 0);
        game.input().key(178, true, game.time());
      }
    }
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    int health = game.health();
    long killed = game.combat().killedTargets();
    pending =
        host.submit(
            () ->
                advance(
                    Objects.requireNonNull(host.getPlayerList().getPlayer(playerId)),
                    host.getTickCount(),
                    health,
                    killed));
  }

  private static Observation advance(ServerPlayer player, int tick, int health, long killed) {
    var controller = Objects.requireNonNull(BridgePlayerController.get(player));
    if (player.getHealth() != nativeHealth || health <= 0)
      throw new IllegalStateException(
          "Blaze encounter changed native health or killed Quake player");
    if (blaze.isNoAi() || blaze.isNoGravity())
      throw new IllegalStateException("Blaze encounter disabled native AI or physics");
    for (var ball :
        player.level().getEntitiesOfClass(SmallFireball.class, player.getBoundingBox().inflate(24)))
      if (ball.getOwner() == blaze) shots.add(ball.getUUID());
    if (stage == 0) {
      baselineHealth = health;
      blaze.setTarget(player);
      since = tick;
      stage = 1;
    } else if (stage == 1) {
      if (blaze.getSensing().hasLineOfSight(player)
          || !shots.isEmpty()
          || controller.hits() != 0
          || health != baselineHealth)
        throw new IllegalStateException("Native blaze attacked through cover");
      if (tick - since >= 120) {
        coverTicks = tick - since;
        for (int z = -3; z <= 3; z++)
          for (int y = 0; y < 6; y++)
            player
                .level()
                .setBlockAndUpdate(origin.offset(4, y, z), Blocks.AIR.defaultBlockState());
        blaze.setTarget(player);
        since = tick;
        stage = 2;
        System.out.println("CraftQ3 blaze AI cover passed ticks=" + coverTicks);
      }
    } else if (stage == 2 && impacts > 0 && burns > 0 && health < baselineHealth) {
      if (!blaze.getSensing().hasLineOfSight(player) || shots.isEmpty())
        throw new IllegalStateException("Blaze impact lacks native AI sight/projectile evidence");
      stage = 3;
      since = tick;
      System.out.println("CraftQ3 blaze AI return fire health=" + health);
    } else if (stage == 3 && !blaze.isAlive() && killed > 0) {
      for (var ball :
          player
              .level()
              .getEntitiesOfClass(SmallFireball.class, player.getBoundingBox().inflate(24)))
        if (ball.getOwner() == blaze) ball.discard();
      player.clearFire();
      player.setInvulnerable(invulnerable);
      return new Observation(
          false,
          Vec3.ZERO,
          "blaze-ai=true cover-ticks="
              + coverTicks
              + " native-shots="
              + shots.size()
              + " impacts="
              + impacts
              + " burns="
              + burns
              + " original-rockets=true native-death=true minecraft-health=true quake-health="
              + health);
    }
    if (tick - since > 400)
      throw new IllegalStateException(
          "Blaze encounter timeout stage="
              + stage
              + " target="
              + blaze.getTarget()
              + " pos="
              + blaze.position()
              + " shots="
              + shots.size()
              + " impacts="
              + impacts
              + " health="
              + blaze.getHealth());
    return new Observation(stage == 3, blaze.getBoundingBox().getCenter(), null);
  }

  public static String result(BridgeGame game) {
    if (pending != null) pending.join();
    if (!finished
        || coverTicks < 120
        || impacts == 0
        || burns == 0
        || game.combat().killedTargets() == 0
        || game.health() <= 0)
      throw new IllegalStateException("Blaze encounter incomplete stage=" + stage);
    return evidence;
  }
}
