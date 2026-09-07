package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.bluevista.craftq3.fabric.building.BuildingDecorations;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemFrame.class)
public abstract class BuildingItemFrameMixin {
  @WrapOperation(
      method = "survives",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/level/block/state/BlockState;isSolid()Z"))
  private boolean craftq3$support(BlockState state, Operation<Boolean> original) {
    var frame = (ItemFrame) (Object) this;
    return original.call(state)
        || BuildingDecorations.supports(
            frame.level(),
            frame.getPos().relative(frame.getDirection().getOpposite()),
            frame.getDirection());
  }
}
