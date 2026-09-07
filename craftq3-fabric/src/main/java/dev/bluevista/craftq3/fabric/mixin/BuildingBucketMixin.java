package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.bluevista.craftq3.fabric.building.BuildingFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BuildingBucketMixin {
  @WrapOperation(
      method = "use",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/item/BucketItem;getPlayerPOVHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;"))
  private BlockHitResult craftq3$surface(
      Level level, Player player, ClipContext.Fluid fluid, Operation<BlockHitResult> original) {
    return BuildingFluids.bucketHit(level, player, original.call(level, player, fluid));
  }

  @Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
  private void craftq3$volume(
      LivingEntity player,
      Level level,
      BlockPos pos,
      BlockHitResult hit,
      CallbackInfoReturnable<Boolean> ci) {
    if (!BuildingFluids.clear(level, pos)) ci.setReturnValue(false);
  }
}
