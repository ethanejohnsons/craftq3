package dev.bluevista.craftq3.fabric.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;

/** Supplies static BSP impacts before Minecraft selects nearer entity hits. */
public final class BuildingProjectiles {
  private BuildingProjectiles() {}

  public static BlockHitResult clip(Level level, ClipContext context, BlockHitResult nativeHit) {
    if (!level.dimension().equals(BuildingSession.DIMENSION)) return nativeHit;
    var start = context.getFrom();
    var end = context.getTo();
    int first = (int) Math.clamp(Math.floor((Math.min(start.x, end.x) + 2048) / 4096), 0, 4096);
    int last = Math.min(4095, (int) Math.floor((Math.max(start.x, end.x) + 2048) / 4096));
    var nearest = nativeHit;
    for (int slot = first; slot <= last; slot++) {
      var environment = BuildingWorlds.at(level, slot * 4096.0);
      if (environment != null) nearest = nearer(environment.geometry(), start, end, nearest);
    }
    return nearest;
  }

  static BlockHitResult nearer(
      BuildingGeometry geometry, Vec3 start, Vec3 end, BlockHitResult hit) {
    var ray = geometry.ray(vector(start), vector(end));
    if (ray.isEmpty()) return hit;
    var point = ray.get().point();
    var position = new Vec3(point.x(), point.y(), point.z());
    if (start.distanceToSqr(position) >= start.distanceToSqr(hit.getLocation())) return hit;
    var n = ray.get().normal();
    var face = Direction.getApproximateNearest((float) n.x(), (float) n.y(), (float) n.z());
    // The result identifies the surface cell for native impact callbacks; no block is created.
    var inside = position.subtract(n.x() * .005, n.y() * .005, n.z() * .005);
    return new BlockHitResult(position, face, BlockPos.containing(inside), false);
  }

  public static boolean clear(Level level, AABB box) {
    if (!level.dimension().equals(BuildingSession.DIMENSION)) return true;
    int first = (int) Math.clamp(Math.floor((box.minX + 2048) / 4096), 0, 4096);
    int last = Math.min(4095, (int) Math.floor((box.maxX + 2048) / 4096));
    for (int slot = first; slot <= last; slot++) {
      var environment = BuildingWorlds.at(level, slot * 4096.0);
      if (environment != null
          && !environment
              .geometry()
              .solidClear(
                  new BuildingGeometry.Box(
                      new dev.bluevista.craftq3.core.math.Vec3(box.minX, box.minY, box.minZ),
                      new dev.bluevista.craftq3.core.math.Vec3(box.maxX, box.maxY, box.maxZ))))
        return false;
    }
    return true;
  }

  private static dev.bluevista.craftq3.core.math.Vec3 vector(Vec3 v) {
    return new dev.bluevista.craftq3.core.math.Vec3(v.x, v.y, v.z);
  }
}
