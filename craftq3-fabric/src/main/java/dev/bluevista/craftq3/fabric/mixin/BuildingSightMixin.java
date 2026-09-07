package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.bluevista.craftq3.fabric.building.BuildingOcclusion;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class BuildingSightMixin {
  @ModifyExpressionValue(
      method =
          "hasLineOfSight(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;D)Z",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/phys/BlockHitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"))
  private HitResult.Type craftq3$sight(
      HitResult.Type original, @Local(ordinal = 0) Vec3 start, @Local(ordinal = 1) Vec3 end) {
    return original == HitResult.Type.MISS
            && BuildingOcclusion.blocked(((LivingEntity) (Object) this).level(), start, end)
        ? HitResult.Type.BLOCK
        : original;
  }
}
