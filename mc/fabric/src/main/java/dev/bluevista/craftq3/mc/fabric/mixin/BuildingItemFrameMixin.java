package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.SupportType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Item frames (glow frames too) hang on BSP walls, floors and ceilings: {@code ItemFrame.survives}
 * (its own, not {@code HangingEntity}'s) asks whether the block behind the frame is solid; a BSP
 * face polygon supporting the centre of that block's face toward the frame counts as solid ({@link
 * BuildingRules#supports} with {@link SupportType#CENTER}, the frame's footprint).
 */
@Mixin(ItemFrame.class)
public abstract class BuildingItemFrameMixin {
  @ModifyExpressionValue(
      method = "survives",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/level/block/state/BlockState;isSolid()Z"))
  private boolean craftq3$bspSupport(boolean solid) {
    if (solid) return true;
    ItemFrame frame = (ItemFrame) (Object) this;
    Direction facing = frame.getDirection();
    return BuildingRules.supports(
        frame.level(), frame.getPos().relative(facing.getOpposite()), facing, SupportType.CENTER);
  }
}
