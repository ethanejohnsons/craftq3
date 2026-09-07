package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.building.BuildingWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BuildingSupportMixin {
  @Inject(
      method =
          "isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z",
      at = @At("RETURN"),
      cancellable = true)
  private void craftq3$support(
      BlockGetter getter,
      BlockPos pos,
      Direction face,
      SupportType type,
      CallbackInfoReturnable<Boolean> ci) {
    if (ci.getReturnValue() || !(getter instanceof Level level)) return;
    var environment = BuildingWorlds.at(level, pos.getX());
    if (environment != null
        && environment.support().supports((BlockState) (Object) this, getter, pos, face, type))
      ci.setReturnValue(true);
  }
}
