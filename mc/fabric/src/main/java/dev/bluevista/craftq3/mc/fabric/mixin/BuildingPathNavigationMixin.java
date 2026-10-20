package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ground navigation over BSP floors off the block grid: the height a mob aims for at each waypoint
 * ({@code getGroundY}) is the BSP floor in the waypoint's cell when there is one (Minecraft uses
 * the node's own height over air), and a cell standing on a BSP floor is a stable destination
 * ({@code isStableDestination}: random strolls and the like pick it). Only ground navigation;
 * flying, swimming and climbing navigations keep their own rules.
 */
@Mixin(PathNavigation.class)
public abstract class BuildingPathNavigationMixin {
  @Shadow @Final protected Level level;

  @ModifyReturnValue(method = "getGroundY", at = @At("RETURN"))
  private double craftq3$bspGround(double original, Vec3 target) {
    if (!((Object) this instanceof GroundPathNavigation)) return original;
    BlockPos cell = BlockPos.containing(target);
    double floor = BuildingRules.floorIn(level, cell);
    if (Double.isNaN(floor)) return original;
    return level.getBlockState(cell.below()).isAir() ? floor : Math.max(original, floor);
  }

  @ModifyReturnValue(method = "isStableDestination", at = @At("RETURN"))
  private boolean craftq3$bspStable(boolean original, BlockPos pos) {
    return original
        || (Object) this instanceof GroundPathNavigation
            && !Double.isNaN(BuildingRules.floorIn(level, pos));
  }
}
