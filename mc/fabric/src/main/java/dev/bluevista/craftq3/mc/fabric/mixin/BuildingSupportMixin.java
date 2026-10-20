package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A block face lying on a BSP surface is sturdy: torches, rails, carpets, doors and everything that
 * needs support can stand on or hang from the map's floors and walls ({@code FaceSupportIndex}).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BuildingSupportMixin {
  @ModifyReturnValue(
      method =
          "isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z",
      at = @At("RETURN"))
  private boolean craftq3$bspSupport(
      boolean original, BlockGetter level, BlockPos pos, Direction direction, SupportType type) {
    return original || BuildingRules.supports(level, pos, direction, type);
  }
}
