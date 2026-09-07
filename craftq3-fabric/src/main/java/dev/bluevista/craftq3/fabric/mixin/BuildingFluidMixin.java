package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.building.BuildingFluids;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class BuildingFluidMixin {
  @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
      method = "getNewLiquid",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/level/block/state/BlockState;isSolid()Z"))
  private boolean craftq3$sourceFloor(
      BlockState below,
      com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original,
      @com.llamalad7.mixinextras.sugar.Local(argsOnly = true)
          net.minecraft.server.level.ServerLevel level,
      @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) BlockPos pos) {
    if (original.call(below)) return true;
    var environment = dev.bluevista.craftq3.fabric.building.BuildingWorlds.at(level, pos.getX());
    return environment != null
        && environment
            .support()
            .supports(
                below,
                level,
                pos.below(),
                Direction.UP,
                net.minecraft.world.level.block.SupportType.FULL);
  }

  @Inject(method = "canPassThroughWall", at = @At("RETURN"), cancellable = true)
  private static void craftq3$flow(
      Direction direction,
      BlockGetter level,
      BlockPos from,
      BlockState source,
      BlockPos to,
      BlockState destination,
      CallbackInfoReturnable<Boolean> ci) {
    // Apply after the native state-pair cache: BSP geometry varies by position and world.
    if (ci.getReturnValueZ() && !BuildingFluids.pass(level, from, to)) ci.setReturnValue(false);
  }

  @Inject(method = "canHoldFluid", at = @At("RETURN"), cancellable = true)
  private static void craftq3$volume(
      BlockGetter level,
      BlockPos pos,
      BlockState state,
      Fluid fluid,
      CallbackInfoReturnable<Boolean> ci) {
    if (ci.getReturnValueZ() && !BuildingFluids.clear(level, pos)) ci.setReturnValue(false);
  }
}
