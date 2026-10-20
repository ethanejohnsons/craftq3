package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Arrows stick in BSP surfaces and stay stuck while the surface is there. */
@Mixin(AbstractArrow.class)
public abstract class BuildingArrowMixin {
  @WrapOperation(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
  private BlockHitResult craftq3$bspClip(
      Level level, ClipContext context, Operation<BlockHitResult> original) {
    return BuildingRules.clip(
        level, context.getFrom(), context.getTo(), original.call(level, context));
  }

  @ModifyReturnValue(method = "shouldFall", at = @At("RETURN"))
  private boolean craftq3$heldByBsp(boolean original) {
    if (!original) return false;
    AbstractArrow self = (AbstractArrow) (Object) this;
    AABB around = new AABB(self.position(), self.position()).inflate(0.06);
    return !BuildingRules.blocksSolid(self.level(), around);
  }
}
