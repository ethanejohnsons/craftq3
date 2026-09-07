package dev.bluevista.craftq3.fabric.building;

import java.nio.file.Files;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** Native projectile ticks in an original BSP, confined to the private development QA world. */
public final class BuildingProjectileSmoke {
  private static boolean started;
  private static int ticks;

  private BuildingProjectileSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.projectileSmoke");
  }

  public static void tick(Minecraft client, BuildingSession session) {
    if (started || ++ticks < 60) return;
    started = true;
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    server
        .submit(
            () -> {
              var level = Objects.requireNonNull(server.getLevel(BuildingSession.DIMENSION));
              if (!server
                  .getWorldPath(LevelResource.ROOT)
                  .toAbsolutePath()
                  .normalize()
                  .getFileName()
                  .toString()
                  .equals("CraftQ3 Bridge QA"))
                throw new IllegalStateException("Requires private QA save");
              verify(level, session);
              return null;
            })
        .whenComplete(
            (unused, error) ->
                client.execute(
                    () -> {
                      if (error != null) {
                        client.stop();
                        throw new IllegalStateException("Native projectile smoke failed", error);
                      }
                      session
                          .leave()
                          .whenComplete(
                              (left, failure) ->
                                  client.execute(
                                      () -> {
                                        if (failure != null)
                                          throw new IllegalStateException(
                                              "Projectile smoke return failed", failure);
                                        try {
                                          String result =
                                              "PASS projectiles floor=true wall=true embedded=true"
                                                  + " snowball=true native-block=true"
                                                  + " entity-front=true entity-cover=true"
                                                  + " returned=true\n";
                                          Files.writeString(
                                              client
                                                  .gameDirectory
                                                  .toPath()
                                                  .resolve("craftq3-bridge.result"),
                                              result);
                                          System.out.print(result);
                                        } catch (java.io.IOException e) {
                                          throw new java.io.UncheckedIOException(e);
                                        }
                                        client.stop();
                                      }));
                    }));
  }

  private static void verify(ServerLevel level, BuildingSession session) {
    double offset = session.region() * 4096.0;
    var floorStart = new Vec3(offset + 28.5, 31, -.5);
    var floorEnd = new Vec3(offset + 28.5, 26, -.5);
    var start = new Vec3(offset - 7, 37.5, 11.5);
    var end = new Vec3(offset - 2, 37.5, 11.5);
    for (var p : new Vec3[] {floorStart, floorEnd, start, end})
      level.getChunkAt(BlockPos.containing(p));
    checkImpact(level, floorStart, floorEnd);
    var wall = checkImpact(level, start, end);
    var snowball =
        Objects.requireNonNull(EntityTypes.SNOWBALL.create(level, EntitySpawnReason.COMMAND));
    snowball.setPos(start);
    snowball.setNoGravity(true);
    snowball.setDeltaMovement(end.subtract(start));
    var hit = ProjectileUtil.getHitResultOnMoveVector(snowball, e -> false);
    if (hit.getType() != HitResult.Type.BLOCK || hit.getLocation().distanceTo(wall) > .01)
      throw new IllegalStateException("Thrown projectile did not select BSP wall");
    snowball.tick();
    if (!snowball.isRemoved()) throw new IllegalStateException("Native snowball did not impact");
    var cell = BlockPos.containing(start.add(1, 0, 0));
    var old = level.getBlockState(cell);
    if (!old.isAir()) throw new IllegalStateException("Native blocker fixture occupied");
    try {
      level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
      var arrow = arrow(level, start, end);
      arrow.tick();
      if (!embedded(arrow) || arrow.getX() >= wall.x - .5)
        throw new IllegalStateException("Nearer Minecraft block lost to BSP");
      arrow.discard();
    } finally {
      level.setBlockAndUpdate(cell, old);
    }
    var zombie =
        Objects.requireNonNull(EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND));
    zombie.setNoAi(true);
    zombie.setNoGravity(true);
    try {
      zombie.setPos(wall.x + .8, start.y - .7, start.z);
      if (!level.addFreshEntity(zombie))
        throw new IllegalStateException("Could not add projectile target");
      var covered = arrow(level, start, end);
      var unoccluded =
          ProjectileUtil.getEntityHitResult(
              level, covered, start, end, new AABB(start, end).inflate(1), e -> e == zombie);
      if (unoccluded == null || unoccluded.getEntity() != zombie)
        throw new IllegalStateException("Covered target is not available to native entity traces");
      covered.tick();
      if (zombie.getHealth() != zombie.getMaxHealth() || !embedded(covered))
        throw new IllegalStateException("BSP did not cover Minecraft entity");
      covered.discard();
      zombie.setPos(start.x + 1, start.y - .7, start.z);
      var exposed = arrow(level, start, end);
      var candidates =
          level.getEntities(exposed, new AABB(start, end).inflate(1), e -> e == zombie);
      System.out.println(
          "CraftQ3 projectile target candidates="
              + candidates.size()
              + " target="
              + zombie.getBoundingBox()
              + " alive="
              + zombie.isAlive());
      if (candidates.isEmpty())
        throw new IllegalStateException(
            "Fixture entity is not accessible to native projectile queries");
      exposed.tick();
      if (zombie.getHealth() >= zombie.getMaxHealth())
        throw new IllegalStateException("Nearer entity was hidden by BSP impact");
      exposed.discard();
    } finally {
      zombie.discard();
    }
  }

  private static Vec3 checkImpact(ServerLevel level, Vec3 start, Vec3 end) {
    var context =
        new ClipContext(
            start,
            end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            net.minecraft.world.phys.shapes.CollisionContext.empty());
    var nativeHit = level.clipIncludingBorder(context);
    if (nativeHit.getType() != HitResult.Type.MISS)
      throw new IllegalStateException("Fixture has native block cover");
    var expected = BuildingProjectiles.clip(level, context, nativeHit);
    if (expected.getType() != HitResult.Type.BLOCK)
      throw new IllegalStateException("Fixture misses original BSP");
    var arrow = arrow(level, start, end);
    try {
      arrow.tick();
      if (!embedded(arrow) || arrow.position().distanceTo(expected.getLocation()) > .1)
        throw new IllegalStateException(
            "Native arrow failed to embed at BSP impact: " + arrow.position());
      var position = arrow.position();
      arrow.move(MoverType.PISTON, Vec3.ZERO);
      for (int i = 0; i < 20; i++) arrow.tick();
      if (!embedded(arrow) || arrow.position().distanceTo(position) > .001)
        throw new IllegalStateException("Embedded arrow fell out of BSP");
      return expected.getLocation();
    } finally {
      arrow.discard();
    }
  }

  private static AbstractArrow arrow(ServerLevel level, Vec3 start, Vec3 end) {
    var arrow = Objects.requireNonNull(EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND));
    arrow.setPos(start);
    arrow.setNoGravity(true);
    arrow.setDeltaMovement(end.subtract(start));
    return arrow;
  }

  private static boolean embedded(AbstractArrow arrow) {
    try {
      var method = AbstractArrow.class.getDeclaredMethod("isInGround");
      method.setAccessible(true);
      return (boolean) method.invoke(arrow);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
