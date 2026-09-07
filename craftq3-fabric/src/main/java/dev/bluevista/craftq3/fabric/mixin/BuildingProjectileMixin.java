package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.bluevista.craftq3.fabric.building.BuildingProjectiles;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ProjectileUtil.class)
public abstract class BuildingProjectileMixin {
  @WrapOperation(
      method = "getHitResult",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
  private static BlockHitResult craftq3$bsp(
      Level level, ClipContext context, Operation<BlockHitResult> original) {
    return BuildingProjectiles.clip(level, context, original.call(level, context));
  }
}
