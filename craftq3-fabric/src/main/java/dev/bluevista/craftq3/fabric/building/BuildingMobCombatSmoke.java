package dev.bluevista.craftq3.fabric.building;

import java.nio.file.Files;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** Native perception and zombie chase/attack in the private original-map QA fixture. */
public final class BuildingMobCombatSmoke {
  private static int ticks;
  private static boolean pending, started, finished;
  private static Mob attacker, victim;
  private static Vec3 start;
  private static float health;

  private BuildingMobCombatSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.mobCombatSmoke");
  }

  public static void tick(Minecraft client, BuildingSession session) {
    if (finished || ++ticks < 60 || pending || ticks % 5 != 0) return;
    pending = true;
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
              try {
                if (!started) {
                  started = true;
                  visibility(level, session);
                  setup(level, session);
                  return null;
                }
                if (victim.getHealth() < health) {
                  double moved = attacker.position().distanceTo(start);
                  if (moved < 3
                      || attacker.getTarget() != victim
                      || !attacker.hasLineOfSight(victim))
                    throw new IllegalStateException(
                        "Attack did not follow a visible native pursuit");
                  String result =
                      "PASS mob-combat bsp-sight=true native-cover=true sensing-cache=true"
                          + " range-dimension=true chase=true attack=true moved="
                          + moved
                          + " health="
                          + health
                          + "/"
                          + victim.getHealth();
                  cleanup();
                  return result;
                }
                if (ticks > 500 || !attacker.isAlive() || attacker.getY() < start.y - 2)
                  throw new IllegalStateException(
                      "Native zombie did not chase and attack: "
                          + attacker.position()
                          + " target="
                          + attacker.getTarget()
                          + " path="
                          + attacker.getNavigation().getPath());
                return null;
              } catch (RuntimeException e) {
                cleanup();
                throw e;
              }
            })
        .whenComplete(
            (result, error) ->
                client.execute(
                    () -> {
                      pending = false;
                      if (error != null) {
                        finished = true;
                        client.stop();
                        throw new IllegalStateException("BSP mob combat failed", error);
                      }
                      if (result == null) return;
                      finished = true;
                      session
                          .leave()
                          .whenComplete(
                              (unused, failure) ->
                                  client.execute(
                                      () -> {
                                        if (failure != null) {
                                          client.stop();
                                          throw new IllegalStateException(
                                              "Mob combat return failed", failure);
                                        }
                                        try {
                                          Files.writeString(
                                              client
                                                  .gameDirectory
                                                  .toPath()
                                                  .resolve("craftq3-bridge.result"),
                                              result + " returned=true\n");
                                          System.out.println(result + " returned=true");
                                        } catch (java.io.IOException e) {
                                          throw new java.io.UncheckedIOException(e);
                                        }
                                        client.stop();
                                      }));
                    }));
  }

  private static void visibility(ServerLevel level, BuildingSession session) {
    var observer =
        Objects.requireNonNull(EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND));
    var target = Objects.requireNonNull(EntityTypes.COW.create(level, EntitySpawnReason.COMMAND));
    try {
      var map = session.map();
      boolean found = false;
      for (var face : map.faces()) {
        if (face.type() != 1 || face.vertexCount() == 0) continue;
        var normal = session.transform().directionToMinecraft(face.normal()).scale(32);
        if (Math.abs(normal.y()) > .001
            || Math.max(Math.abs(normal.x()), Math.abs(normal.z())) < .999) continue;
        var center = new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0);
        for (int i = 0; i < face.vertexCount(); i++)
          center = center.add(map.vertices().get(face.firstVertex() + i).position());
        center = session.transform().toMinecraft(center.scale(1.0 / face.vertexCount()));
        for (double depth : new double[] {1, 2, 3}) {
          observer.setPos(center.x() + normal.x(), center.y() - 1, center.z() + normal.z());
          target.setPos(
              center.x() - normal.x() * depth, center.y() - 1, center.z() - normal.z() * depth);
          if (!BuildingNavigation.clear(level, observer.getBoundingBox())
              || !BuildingNavigation.clear(level, target.getBoundingBox())) continue;
          var nativeRay =
              new ClipContext(
                  observer.getEyePosition(),
                  target.getEyePosition(),
                  ClipContext.Block.COLLIDER,
                  ClipContext.Fluid.NONE,
                  observer);
          if (level.clip(nativeRay).getType() != HitResult.Type.MISS
              || !BuildingOcclusion.blocked(level, nativeRay.getFrom(), nativeRay.getTo()))
            continue;
          observer.getSensing().tick();
          if (observer.hasLineOfSight(target) || observer.getSensing().hasLineOfSight(target))
            throw new IllegalStateException("Original BSP wall did not hide native target");
          var visible = observer.position().add(normal.z() * 4, 0, -normal.x() * 4);
          target.setPos(visible);
          if (!BuildingNavigation.clear(level, target.getBoundingBox())
              || !observer.hasLineOfSight(target)) continue;
          if (observer.getSensing().hasLineOfSight(target))
            throw new IllegalStateException("Native per-tick sensing cache changed");
          observer.getSensing().tick();
          if (!observer.getSensing().hasLineOfSight(target))
            throw new IllegalStateException("Visible target stayed hidden after sensing tick");
          var cell =
              BlockPos.containing(observer.getEyePosition().add(target.getEyePosition()).scale(.5));
          var previous = level.getBlockState(cell);
          if (!previous.isAir()) continue;
          try {
            level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            observer.getSensing().tick();
            if (observer.hasLineOfSight(target) || observer.getSensing().hasLineOfSight(target))
              throw new IllegalStateException("Native block cover lost to BSP sight");
          } finally {
            level.setBlockAndUpdate(cell, previous);
          }
          observer.getSensing().tick();
          if (!observer.getSensing().hasLineOfSight(target))
            throw new IllegalStateException("Removed native cover kept hiding target");
          target.setPos(observer.position().add(129, 0, 0));
          if (observer.hasLineOfSight(target))
            throw new IllegalStateException("Native sight range bypassed");
          var other =
              Objects.requireNonNull(
                  EntityTypes.COW.create(
                      Objects.requireNonNull(level.getServer().getLevel(Level.OVERWORLD)),
                      EntitySpawnReason.COMMAND));
          try {
            other.setPos(observer.position());
            if (observer.hasLineOfSight(other))
              throw new IllegalStateException("Cross-dimension sight allowed");
          } finally {
            other.discard();
          }
          System.out.println(
              "CraftQ3 native BSP sight PASS observer="
                  + observer.position()
                  + " visible="
                  + visible);
          found = true;
          break;
        }
        if (found) break;
      }
      if (!found)
        throw new IllegalStateException(
            "No original BSP sight barrier with visible adjacent target found");
    } finally {
      observer.discard();
      target.discard();
    }
  }

  private static void setup(ServerLevel level, BuildingSession session) {
    var route = BuildingNavigationSmoke.find(level, session);
    level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
    attacker = Objects.requireNonNull(EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND));
    attacker.setPersistenceRequired();
    attacker.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
    start = route.start();
    attacker.setPos(start.add(0, .05, 0));
    attacker.move(MoverType.SELF, new Vec3(0, -.1, 0));
    victim = Objects.requireNonNull(EntityTypes.COW.create(level, EntitySpawnReason.COMMAND));
    victim.setNoAi(true);
    victim.setPersistenceRequired();
    var end = Vec3.atBottomCenterOf(route.target());
    var floor =
        session.geometry().navigationFloor(end.x, route.target().getY(), end.z).orElseThrow();
    victim.setPos(end.x, floor + .05, end.z);
    victim.move(MoverType.SELF, new Vec3(0, -.1, 0));
    health = victim.getHealth();
    if (!level.addFreshEntity(victim) || !level.addFreshEntity(attacker))
      throw new IllegalStateException("Cannot add native chase actors");
    attacker.setTarget(victim);
    System.out.println("CraftQ3 native chase start=" + start + " victim=" + victim.position());
  }

  private static void cleanup() {
    if (attacker != null) {
      attacker.discard();
      attacker = null;
    }
    if (victim != null) {
      victim.discard();
      victim = null;
    }
  }
}
