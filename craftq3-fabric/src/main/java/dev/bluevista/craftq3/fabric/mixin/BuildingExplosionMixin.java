package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.bluevista.craftq3.fabric.building.BuildingOcclusion;
import dev.bluevista.craftq3.fabric.building.BuildingSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerExplosion.class)
public abstract class BuildingExplosionMixin {
  @Shadow @Final private Vec3 center;

  @ModifyExpressionValue(
      method = "getSeenPercent",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/phys/BlockHitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"))
  private static HitResult.Type craftq3$exposure(
      HitResult.Type nativeType,
      @Local(argsOnly = true) Vec3 center,
      @Local(argsOnly = true) Entity entity,
      @Local(ordinal = 1) Vec3 sample) {
    return nativeType == HitResult.Type.MISS
            && BuildingOcclusion.blocked(entity.level(), sample, center)
        ? HitResult.Type.BLOCK
        : nativeType;
  }

  @WrapOperation(
      method = "calculateExplodedPositions",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/level/ServerLevel;isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z"))
  private boolean craftq3$propagation(
      ServerLevel level,
      BlockPos pos,
      Operation<Boolean> original,
      @Local(index = 15) double x,
      @Local(index = 17) double y,
      @Local(index = 19) double z,
      @Share("craftq3$previousSample") LocalRef<Vec3> previous) {
    boolean nativeBounds = original.call(level, pos);
    if (!nativeBounds || !level.dimension().equals(BuildingSession.DIMENSION)) return nativeBounds;
    // MC 26.2's current exact ray sample, before native resistance and block selection.
    // Test the segment, not a block center or sampled solid occupancy: thin BSP walls count.
    var sample = new Vec3(x, y, z);
    // Every native propagation ray begins at the exact explosion center.
    var from = sample.equals(center) || previous.get() == null ? center : previous.get();
    previous.set(sample);
    return !BuildingOcclusion.blocked(level, from, sample);
  }
}
