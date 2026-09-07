package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.building.BuildingLighting;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightCoordsUtil.class)
public abstract class BuildingLightCoordsMixin {
  @Inject(
      method =
          "getLightCoords(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I",
      at = @At("RETURN"),
      cancellable = true)
  private static void craftq3$light(
      BlockAndLightGetter getter, BlockPos pos, CallbackInfoReturnable<Integer> ci) {
    if (getter instanceof Level level)
      ci.setReturnValue(
          BuildingLighting.apply(
              level, net.minecraft.world.phys.Vec3.atCenterOf(pos), ci.getReturnValueI()));
  }
}
