package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.bluevista.craftq3.fabric.building.BuildingProjectiles;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractArrow.class)
public abstract class BuildingArrowMixin {
  @WrapOperation(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
  private BlockHitResult craftq3$bsp(
      Level level, ClipContext context, Operation<BlockHitResult> original) {
    return BuildingProjectiles.clip(level, context, original.call(level, context));
  }

  @WrapOperation(
      method = "shouldFall",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/phys/AABB;)Z"))
  private boolean craftq3$embedded(Level level, AABB box, Operation<Boolean> original) {
    return original.call(level, box) && BuildingProjectiles.clear(level, box);
  }
}
