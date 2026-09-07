package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.bluevista.craftq3.fabric.building.BuildingNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WalkNodeEvaluator.class)
public abstract class BuildingWalkNodeMixin extends NodeEvaluator {
  @Unique private boolean craftq3$bodyQuery;

  @Shadow
  protected abstract double getFloorLevel(BlockPos pos);

  @WrapMethod(
      method =
          "getPathTypeOfMob(Lnet/minecraft/world/level/pathfinder/PathfindingContext;IIILnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/PathType;")
  private PathType craftq3$bodyScope(
      PathfindingContext context, int x, int y, int z, Mob mob, Operation<PathType> original) {
    boolean previous = craftq3$bodyQuery;
    craftq3$bodyQuery = true;
    try {
      return original.call(context, x, y, z, mob);
    } finally {
      craftq3$bodyQuery = previous;
    }
  }

  @ModifyReturnValue(method = "getNeighbors", at = @At("RETURN"))
  private int craftq3$edges(int count, Node[] neighbors, Node from) {
    if (!BuildingNavigation.active(currentContext.level(), from.x + .5)) return count;
    double width = mob.getBbWidth(), center = Math.floor(width + 1) * .5;
    double floor = getFloorLevel(new BlockPos(from.x, from.y, from.z)) + .005;
    var body =
        new AABB(
            from.x + center - width / 2,
            floor,
            from.z + center - width / 2,
            from.x + center + width / 2,
            floor + mob.getBbHeight() - .005,
            from.z + center + width / 2);
    int kept = 0;
    for (int i = 0; i < count; i++) {
      var to = neighbors[i];
      double targetFloor = getFloorLevel(new BlockPos(to.x, to.y, to.z)) + .005;
      if (BuildingNavigation.edgeClear(
          currentContext.level(), body, to.x + center, targetFloor, to.z + center))
        neighbors[kept++] = to;
    }
    java.util.Arrays.fill(neighbors, kept, count, null);
    return kept;
  }

  @ModifyReturnValue(
      method =
          "getPathType(Lnet/minecraft/world/level/pathfinder/PathfindingContext;III)Lnet/minecraft/world/level/pathfinder/PathType;",
      at = @At("RETURN"))
  private PathType craftq3$type(
      PathType original, PathfindingContext context, int x, int y, int z) {
    return BuildingNavigation.type(context, x, y, z, original, !craftq3$bodyQuery);
  }

  @ModifyReturnValue(
      method =
          "getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D",
      at = @At("RETURN"))
  private static double craftq3$floor(double original, BlockGetter blocks, BlockPos pos) {
    return Math.max(original, BuildingNavigation.floor(blocks, pos).orElse(original));
  }

  @WrapOperation(
      method = "lambda$hasCollisions$0",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/CollisionGetter;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
  private boolean craftq3$collision(
      CollisionGetter blocks, Entity mob, AABB box, Operation<Boolean> original) {
    return original.call(blocks, mob, box) && BuildingNavigation.clear(blocks, box);
  }

  @ModifyReturnValue(
      method =
          "getPathTypeOfMob(Lnet/minecraft/world/level/pathfinder/PathfindingContext;IIILnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/PathType;",
      at = @At("RETURN"))
  private PathType craftq3$body(
      PathType original, PathfindingContext context, int x, int y, int z, Mob mob) {
    if (!BuildingNavigation.active(context.level(), x + .5)) return original;
    double floor =
        original == PathType.OPEN
            ? y
            : WalkNodeEvaluator.getFloorLevel(context.level(), new BlockPos(x, y, z));
    double width = mob.getBbWidth();
    double center = Math.floor(width + 1) * .5;
    var box =
        new AABB(
            x + center - width / 2,
            floor + .005,
            z + center - width / 2,
            x + center + width / 2,
            floor + mob.getBbHeight(),
            z + center + width / 2);
    return BuildingNavigation.clear(context.level(), box) ? original : PathType.BLOCKED;
  }
}
