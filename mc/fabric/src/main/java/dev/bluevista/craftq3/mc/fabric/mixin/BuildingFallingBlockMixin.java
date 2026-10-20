package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sand, gravel and other falling blocks rest on BSP floors: the cell below is not free when BSP
 * solid lies right under the block. New (the old bridge had no falling-block hook); the landing
 * half is {@code BuildingFallingBlockEntityMixin}.
 */
@Mixin(FallingBlock.class)
public abstract class BuildingFallingBlockMixin {
  @WrapOperation(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
  private boolean craftq3$bspFloor(
      BlockState below,
      Operation<Boolean> original,
      BlockState state,
      ServerLevel level,
      BlockPos pos,
      RandomSource random) {
    return original.call(below) && !BuildingRules.restsOnBsp(level, pos);
  }
}
