package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mobs cannot see through BSP walls (targeting, sensing, ranged attacks). */
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
  private HitResult.Type craftq3$bspSight(
      HitResult.Type original, @Local(name = "from") Vec3 from, @Local(name = "to") Vec3 to) {
    return original == HitResult.Type.MISS
            && BuildingRules.occluded(((LivingEntity) (Object) this).level(), from, to)
        ? HitResult.Type.BLOCK
        : original;
  }
}
