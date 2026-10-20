package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paintings and item frames placed on a BSP surface: the crosshair's BSP pick names the empty cell
 * in front of the surface as the clicked block, so the item treats the BSP side of that cell as the
 * clicked block and hangs the entity in the empty cell ({@link BuildingRules#hangingClicked}).
 * Whether it may hang there is {@code survives} ({@code BuildingHangingMixin}, {@code
 * BuildingItemFrameMixin}).
 */
@Mixin(HangingEntityItem.class)
public abstract class BuildingHangingItemMixin {
  @ModifyExpressionValue(
      method = "useOn",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/item/context/UseOnContext;getClickedPos()Lnet/minecraft/core/BlockPos;"))
  private BlockPos craftq3$bspSurface(
      BlockPos clicked, @Local(argsOnly = true, name = "context") UseOnContext context) {
    return BuildingRules.hangingClicked(context.getLevel(), clicked, context.getClickedFace());
  }
}
