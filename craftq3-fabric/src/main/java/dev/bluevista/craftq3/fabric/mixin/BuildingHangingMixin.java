package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.building.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntity.class)
public abstract class BuildingHangingMixin {
  @Inject(method = "lambda$survives$0", at = @At("RETURN"), cancellable = true)
  private void craftq3$support(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
    var entity = (HangingEntity) (Object) this;
    if (!ci.getReturnValueZ()
        && BuildingDecorations.supports(entity.level(), pos, entity.getDirection()))
      ci.setReturnValue(true);
  }

  @Inject(method = "hasLevelCollision", at = @At("RETURN"), cancellable = true)
  private void craftq3$clear(AABB box, CallbackInfoReturnable<Boolean> ci) {
    var entity = (HangingEntity) (Object) this;
    if (!ci.getReturnValueZ() && !BuildingProjectiles.clear(entity.level(), box.deflate(.001)))
      ci.setReturnValue(true);
  }
}
