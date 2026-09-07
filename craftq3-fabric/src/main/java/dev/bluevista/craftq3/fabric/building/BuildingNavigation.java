package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.mixin.BuildingRegionAccessor;
import java.util.OptionalDouble;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.AABB;

/** On-demand BSP queries for Minecraft's own ground pathfinder; no block-state substitution. */
public final class BuildingNavigation {
  private BuildingNavigation() {}

  private static Level level(BlockGetter blocks) {
    if (blocks instanceof Level level) return level;
    return blocks instanceof BuildingRegionAccessor region ? region.craftq3$level() : null;
  }

  public static boolean active(BlockGetter blocks, double x) {
    var level = level(blocks);
    return level != null && BuildingWorlds.at(level, x) != null;
  }

  public static OptionalDouble floor(BlockGetter blocks, BlockPos pos) {
    var level = level(blocks);
    if (level == null) return OptionalDouble.empty();
    var world = BuildingWorlds.at(level, pos.getX() + .5);
    return world == null
        ? OptionalDouble.empty()
        : world.geometry().navigationFloor(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
  }

  public static boolean clear(BlockGetter blocks, AABB box) {
    var level = level(blocks);
    if (level == null || !level.dimension().equals(BuildingSession.DIMENSION)) return true;
    int first = (int) Math.clamp(Math.floor((box.minX + 2048) / 4096), 0, 4096);
    int last = Math.min(4095, (int) Math.floor((box.maxX + 2048) / 4096));
    for (int slot = first; slot <= last; slot++) {
      var world = BuildingWorlds.at(level, slot * 4096.0);
      if (world != null
          && !world
              .geometry()
              .clear(
                  new BuildingGeometry.Box(
                      new Vec3(box.minX, box.minY, box.minZ),
                      new Vec3(box.maxX, box.maxY, box.maxZ)))) return false;
    }
    return true;
  }

  public static boolean edgeClear(
      BlockGetter blocks, AABB body, double targetX, double targetY, double targetZ) {
    double dx = targetX - (body.minX + body.maxX) / 2, dz = targetZ - (body.minZ + body.maxZ) / 2;
    double lift = Math.max(0, targetY - body.minY);
    if (lift > 0 && !sweep(blocks, body, new Vec3(0, lift, 0))) return false;
    body = body.move(0, lift, 0);
    if (!sweep(blocks, body, new Vec3(dx, 0, dz))) return false;
    body = body.move(dx, 0, dz);
    return sweep(blocks, body, new Vec3(0, targetY - body.minY, 0));
  }

  private static boolean sweep(BlockGetter blocks, AABB body, Vec3 movement) {
    var level = level(blocks);
    if (level == null || !level.dimension().equals(BuildingSession.DIMENSION)) return true;
    int first =
        (int)
            Math.clamp(
                Math.floor((Math.min(body.minX, body.minX + movement.x()) + 2048) / 4096), 0, 4096);
    int last =
        Math.min(
            4095, (int) Math.floor((Math.max(body.maxX, body.maxX + movement.x()) + 2048) / 4096));
    var box =
        new BuildingGeometry.Box(
            new Vec3(body.minX, body.minY, body.minZ), new Vec3(body.maxX, body.maxY, body.maxZ));
    for (int slot = first; slot <= last; slot++) {
      var world = BuildingWorlds.at(level, slot * 4096.0);
      if (world != null && !world.geometry().pathClear(box, movement)) return false;
    }
    return true;
  }

  public static PathType type(
      PathfindingContext context, int x, int y, int z, PathType nativeType, boolean checkCell) {
    var blocks = context.level();
    if (!active(blocks, x + .5)) return nativeType;
    var floor = floor(blocks, new BlockPos(x, y, z));
    double bottom = Math.max(y, floor.orElse(y));
    if (checkCell
        && !clear(blocks, new AABB(x + .01, bottom + .01, z + .01, x + .99, y + .99, z + .99)))
      return PathType.BLOCKED;
    if (nativeType == PathType.OPEN && floor.isPresent())
      return WalkNodeEvaluator.checkNeighbourBlocks(context, x, y, z, PathType.WALKABLE);
    return nativeType;
  }
}
