package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Projectiles (fireballs, tridents, thrown items; arrows have their own clip) hit BSP surfaces: the
 * block clip they make ends at the first BSP surface when it comes before any block.
 */
@Mixin(ProjectileUtil.class)
public abstract class BuildingProjectileMixin {
  @WrapOperation(
      method = {"getHitResult", "getHitEntitiesAlong"},
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
  private static BlockHitResult craftq3$bspClip(
      Level level, ClipContext context, Operation<BlockHitResult> original) {
    return BuildingRules.clip(
        level, context.getFrom(), context.getTo(), original.call(level, context));
  }
}
