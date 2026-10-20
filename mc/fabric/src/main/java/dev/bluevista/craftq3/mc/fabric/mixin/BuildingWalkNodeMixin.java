package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingQueries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ground mobs path around BSP walls and over BSP floors. A node stands on a BSP floor within half a
 * block of its cell's bottom (floors off the block grid included); a node whose mob-sized body
 * above that floor overlaps BSP solid is {@code BLOCKED}; an open node with such a floor is
 * walkable; and the straight line a mob takes to skip nodes is tested against the BSP too ({@code
 * hasCollisions}, replacing the old lambda target).
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class BuildingWalkNodeMixin extends NodeEvaluator {
  @ModifyReturnValue(method = "getPathTypeOfMob", at = @At("RETURN"))
  private PathType craftq3$bspPathType(
      PathType original, PathfindingContext context, int x, int y, int z, Mob mob) {
    BuildingQueries queries = BuildingRules.at(mob.level(), x + 0.5);
    if (queries == null) return original;
    // The node stands on a BSP floor within half a block of its cell's bottom (on or off the
    // block grid); the body above that floor must be free of BSP solid.
    double floor = queries.floorBelow(x + 0.5, y + 0.5, z + 0.5, 1.0);
    double bottom = Double.isNaN(floor) ? y : Math.max(y, floor);
    double height = mob.getBbHeight();
    double top = Math.max(y + Math.max(1, Math.ceil(height)), bottom + height);
    if (!queries.boxClear(
        x + 0.01,
        bottom + 0.01,
        z + 0.01,
        x + 0.99,
        top - 0.01,
        z + 0.99,
        BuildingQueries.MOVEMENT_MASK)) return PathType.BLOCKED;
    if (original == PathType.OPEN && !Double.isNaN(floor)) return PathType.WALKABLE;
    return original;
  }

  @ModifyReturnValue(method = "hasCollisions", at = @At("RETURN"))
  private boolean craftq3$bspCollisions(boolean original, AABB box) {
    return original || (mob != null && BuildingRules.blocksMovement(mob.level(), box));
  }
}
