package dev.bluevista.craftq3.fabric.building;

import java.nio.file.Files;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** Native path search, block-change replanning and actual mob walking in the private QA save. */
public final class BuildingNavigationSmoke {
  record Route(Vec3 start, BlockPos target, BlockPos blocker) {}

  private static int ticks;
  private static boolean pending, started, finished;
  private static Mob mob;
  private static Route route;
  private static final Map<BlockPos, BlockState> replaced = new HashMap<>();
  private static String plan;

  private BuildingNavigationSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.navigationSmoke");
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
                  setup(level, session);
                  return null;
                }
                double distance = mob.position().distanceTo(Vec3.atBottomCenterOf(route.target()));
                if (distance < .9) {
                  String result =
                      "PASS navigation native-path=true replan=true walked=true bsp-floor=true"
                          + " wall=true native-types=true "
                          + plan
                          + " distance="
                          + distance;
                  cleanup(level);
                  return result;
                }
                if (ticks > 500 || !mob.isAlive() || mob.getY() < route.start().y - 2)
                  throw new IllegalStateException(
                      "Native navigation did not reach goal: "
                          + mob.position()
                          + " target="
                          + route.target()
                          + " path="
                          + mob.getNavigation().getPath());
                return null;
              } catch (RuntimeException e) {
                cleanup(level);
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
                        throw new IllegalStateException("Navigation smoke failed", error);
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
                                              "Navigation return failed", failure);
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

  private static void setup(ServerLevel level, BuildingSession session) {
    route = find(level, session);
    System.out.println("CraftQ3 navigation fixture " + route);
    mob = Objects.requireNonNull(EntityTypes.COW.create(level, EntitySpawnReason.COMMAND));
    mob.removeAllGoals(goal -> true);
    mob.setPersistenceRequired();
    mob.setPos(route.start().add(0, .05, 0));
    mob.move(MoverType.SELF, new Vec3(0, -.1, 0));
    if (!level.addFreshEntity(mob)) throw new IllegalStateException("Cannot add navigation mob");
    checkTypes(level, session);
    var original = mob.getNavigation().createPath(route.target(), 0);
    validate(session, original);
    int direct = original.getNodeCount();
    for (int y = 0; y < 3; y++) {
      var p = route.blocker().above(y);
      replaced.put(p, level.getBlockState(p));
      level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
    }
    mob.getNavigation().stop();
    var replanned = mob.getNavigation().createPath(route.target(), 0);
    validate(session, replanned);
    boolean deviated = false;
    for (int i = 0; i < replanned.getNodeCount(); i++) {
      var p = replanned.getNodePos(i);
      if (p.getX() != route.target().getX() && route.start().x == route.target().getX() + .5
          || p.getZ() != route.target().getZ() && route.start().z == route.target().getZ() + .5)
        deviated = true;
    }
    if (!deviated)
      throw new IllegalStateException("Native path did not route around placed blocks");
    if (!mob.getNavigation().moveTo(replanned, 1.0))
      throw new IllegalStateException("Native moveTo rejected path");
    plan = "nodes=" + direct + "/" + replanned.getNodeCount();
    System.out.println("CraftQ3 native navigation " + plan + " start=" + mob.position());
  }

  private static void checkTypes(ServerLevel level, BuildingSession session) {
    var evaluator = mob.getNavigation().getNodeEvaluator();
    var wall = new BlockPos(session.region() * 4096 - 4, 37, 11);
    if (WalkNodeEvaluator.getPathTypeStatic(mob, wall) != PathType.OPEN
        || evaluator.getPathType(mob, wall) != PathType.BLOCKED)
      throw new IllegalStateException("Native path nodes did not recognize the original BSP wall");
    var cell = route.blocker();
    var previous = level.getBlockState(cell);
    try {
      for (var block : List.of(Blocks.WATER, Blocks.LAVA, Blocks.STONE)) {
        level.setBlockAndUpdate(cell, block.defaultBlockState());
        var nativeType = WalkNodeEvaluator.getPathTypeStatic(mob, cell);
        if (nativeType == PathType.OPEN || evaluator.getPathType(mob, cell) != nativeType)
          throw new IllegalStateException(
              "BSP navigation replaced native block type: " + block + " " + nativeType);
      }
      level.setBlockAndUpdate(cell, Blocks.MAGMA_BLOCK.defaultBlockState());
      var nativeType = WalkNodeEvaluator.getPathTypeStatic(mob, cell.above());
      if (nativeType == PathType.OPEN || evaluator.getPathType(mob, cell.above()) != nativeType)
        throw new IllegalStateException("BSP navigation lost native surface hazard");
    } finally {
      level.setBlockAndUpdate(cell, previous);
    }
  }

  private static void validate(BuildingSession session, Path path) {
    if (path == null || !path.canReach() || path.getNodeCount() < 2)
      throw new IllegalStateException("Native pathfinder did not reach BSP target: " + path);
    for (int i = 0; i < path.getNodeCount(); i++) {
      var p = path.getNodePos(i);
      var floor = session.geometry().navigationFloor(p.getX() + .5, p.getY(), p.getZ() + .5);
      if (floor.isEmpty()) throw new IllegalStateException("Path node lacks BSP floor: " + p);
      var box =
          new BuildingGeometry.Box(
              new dev.bluevista.craftq3.core.math.Vec3(
                  p.getX() + .05, floor.getAsDouble() + .01, p.getZ() + .05),
              new dev.bluevista.craftq3.core.math.Vec3(
                  p.getX() + .95, floor.getAsDouble() + 1.4, p.getZ() + .95));
      if (!session.geometry().clear(box))
        throw new IllegalStateException("Path node intersects BSP: " + p);
    }
  }

  static Route find(ServerLevel level, BuildingSession session) {
    var map = session.map();
    for (var face : map.faces()) {
      if (face.type() != 1 || face.vertexCount() == 0) continue;
      var normal = session.transform().directionToMinecraft(face.normal()).scale(32);
      if (normal.y() < .999) continue;
      var center = new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0);
      for (int i = 0; i < face.vertexCount(); i++)
        center = center.add(map.vertices().get(face.firstVertex() + i).position());
      center = session.transform().toMinecraft(center.scale(1.0 / face.vertexCount()));
      int nodeY = (int) Math.floor(center.y() + .5);
      for (int axis = 0; axis < 2; axis++)
        for (int sign : new int[] {1, -1}) {
          int x = (int) Math.floor(center.x()), z = (int) Math.floor(center.z());
          boolean valid = true;
          for (int along = 0; along <= 6 && valid; along++)
            for (int across = -2; across <= 2; across++) {
              int sx = x + (axis == 0 ? along * sign : across),
                  sz = z + (axis == 1 ? along * sign : across);
              var f = session.geometry().navigationFloor(sx + .5, nodeY, sz + .5);
              if (f.isEmpty() || Math.abs(f.getAsDouble() - center.y()) > .05) {
                valid = false;
                break;
              }
              var box =
                  new AABB(
                      sx + .01,
                      f.getAsDouble() + .01,
                      sz + .01,
                      sx + .99,
                      f.getAsDouble() + 2,
                      sz + .99);
              if (!BuildingNavigation.clear(level, box) || !level.noCollision(box)) {
                valid = false;
                break;
              }
            }
          if (!valid) continue;
          var start = new Vec3(x + .5, center.y(), z + .5);
          var target =
              new BlockPos(x + (axis == 0 ? 6 * sign : 0), nodeY, z + (axis == 1 ? 6 * sign : 0));
          var blocker =
              new BlockPos(
                  x + (axis == 0 ? 3 * sign : 0),
                  (int) Math.ceil(center.y()),
                  z + (axis == 1 ? 3 * sign : 0));
          if (!level.getBlockState(blocker).isAir()) continue;
          level.getChunkAt(BlockPos.containing(start));
          level.getChunkAt(target);
          return new Route(start, target, blocker);
        }
    }
    throw new IllegalStateException("No original flat BSP navigation area with room for a detour");
  }

  private static void cleanup(ServerLevel level) {
    if (mob != null) {
      mob.discard();
      mob = null;
    }
    replaced.forEach(level::setBlockAndUpdate);
    replaced.clear();
  }
}
