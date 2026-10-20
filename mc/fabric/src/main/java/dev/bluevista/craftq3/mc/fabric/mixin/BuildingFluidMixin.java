package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fluids stop at BSP surfaces: {@code canPassThroughWall} is the single test behind spreading down
 * and sideways and behind a cell's new level, so a BSP barrier between two cells (or BSP solid in
 * the target) keeps fluid out.
 */
@Mixin(FlowingFluid.class)
public abstract class BuildingFluidMixin {
  @ModifyReturnValue(method = "canPassThroughWall", at = @At("RETURN"))
  private static boolean craftq3$bspWall(
      boolean original,
      Direction direction,
      BlockGetter level,
      BlockPos sourcePos,
      BlockState sourceState,
      BlockPos targetPos,
      BlockState targetState) {
    return original && BuildingRules.fluidCanFlow(level, sourcePos, targetPos);
  }
}
