package dev.bluevista.craftq3.fabric.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;

/** BSP admission for native fluid cells and bucket rays; native fluid rules remain in charge. */
public final class BuildingFluids {
  private BuildingFluids() {}

  public static boolean clear(BlockGetter world, BlockPos pos) {
    return !(world instanceof Level level)
        || BuildingProjectiles.clear(level, new AABB(pos).deflate(.001));
  }

  public static boolean pass(BlockGetter world, BlockPos from, BlockPos to) {
    return clear(world, to)
        && (!(world instanceof Level level)
            || !BuildingOcclusion.blocked(level, Vec3.atCenterOf(from), Vec3.atCenterOf(to)));
  }

  public static BlockHitResult bucketHit(Level level, Player player, BlockHitResult nativeHit) {
    if (!level.dimension().equals(BuildingSession.DIMENSION)) return nativeHit;
    var start = player.getEyePosition();
    var end = start.add(player.getViewVector(1).scale(player.blockInteractionRange()));
    int first = (int) Math.clamp(Math.floor((Math.min(start.x, end.x) + 2048) / 4096), 0, 4096);
    int last = Math.min(4095, (int) Math.floor((Math.max(start.x, end.x) + 2048) / 4096));
    var result = nativeHit;
    for (int slot = first; slot <= last; slot++) {
      var environment = BuildingWorlds.at(level, slot * 4096.0);
      if (environment != null) result = nearer(environment.geometry(), start, end, result);
    }
    return result;
  }

  static BlockHitResult nearer(
      BuildingGeometry geometry, Vec3 start, Vec3 end, BlockHitResult nativeHit) {
    var hit = geometry.pickSolid(vector(start), vector(end));
    if (hit.isEmpty()) return nativeHit;
    var surface = hit.get();
    var point = new Vec3(surface.point().x(), surface.point().y(), surface.point().z());
    if (start.distanceToSqr(point) >= start.distanceToSqr(nativeHit.getLocation()))
      return nativeHit;
    var n = surface.normal();
    var face = Direction.getApproximateNearest((float) n.x(), (float) n.y(), (float) n.z());
    var cell = BlockPos.containing(surface.cell().x(), surface.cell().y(), surface.cell().z());
    var placement = surface.placementPoint();
    // BucketItem places into hitCell.relative(face). Supply the same legal outside cell
    // used for block placement, including fractional BSP surfaces.
    return new BlockHitResult(
        new Vec3(placement.x(), placement.y(), placement.z()),
        face,
        cell.relative(face.getOpposite()),
        false);
  }

  private static dev.bluevista.craftq3.core.math.Vec3 vector(Vec3 v) {
    return new dev.bluevista.craftq3.core.math.Vec3(v.x, v.y, v.z);
  }
}
