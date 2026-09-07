package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.bluevista.craftq3.fabric.building.BuildingDecorations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HangingEntityItem.class)
public abstract class BuildingHangingItemMixin {
  @WrapOperation(
      method = "useOn",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/item/context/UseOnContext;getClickedPos()Lnet/minecraft/core/BlockPos;"))
  private BlockPos craftq3$outside(UseOnContext context, Operation<BlockPos> original) {
    return BuildingDecorations.clicked(context, original.call(context));
  }
}
