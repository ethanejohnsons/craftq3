package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Buckets against BSP surfaces. The bucket casts its own ray on use ({@code
 * getPlayerPOVHitResult}), which knows only blocks and fluids: with BSP surfaces in it, a filled
 * bucket aimed at a BSP floor or wall empties into the free cell in front of the surface and an
 * empty bucket cannot reach a fluid behind BSP solid ({@link BuildingRules#bucketClip}). Emptying
 * (also from dispensers) never puts a fluid into a cell that overlaps BSP solid.
 */
@Mixin(BucketItem.class)
public abstract class BuildingBucketMixin {
  @WrapOperation(
      method = "use",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/item/BucketItem;getPlayerPOVHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;"))
  private BlockHitResult craftq3$bspSurface(
      Level level, Player player, ClipContext.Fluid fluid, Operation<BlockHitResult> original) {
    BlockHitResult hit = original.call(level, player, fluid);
    Vec3 from = player.getEyePosition();
    Vec3 to = from.add(player.getLookAngle().scale(player.blockInteractionRange()));
    return BuildingRules.bucketClip(level, from, to, hit);
  }

  @WrapMethod(method = "emptyContents")
  private boolean craftq3$notIntoBsp(
      LivingEntity user,
      Level level,
      BlockPos pos,
      BlockHitResult hitResult,
      Operation<Boolean> original) {
    if (BuildingRules.cellInBsp(level, pos)) return false;
    return original.call(user, level, pos, hitResult);
  }
}
