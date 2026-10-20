package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A falling block that lands on a BSP floor becomes a block there instead of an item. */
@Mixin(FallingBlockEntity.class)
public abstract class BuildingFallingBlockEntityMixin {
  @WrapOperation(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
  private boolean craftq3$bspFloor(
      BlockState below, Operation<Boolean> original, @Local(name = "pos") BlockPos pos) {
    FallingBlockEntity self = (FallingBlockEntity) (Object) this;
    return original.call(below) && !BuildingRules.restsOnBsp(self.level(), pos);
  }
}
