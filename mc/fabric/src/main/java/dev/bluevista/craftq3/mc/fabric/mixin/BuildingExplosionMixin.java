package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BSP walls shelter entities from explosions and stop blast rays: an exposure sample whose ray to
 * the centre crosses BSP solid counts as blocked, and a propagation ray ends at the first BSP
 * surface (each step is tested from the previous sample, so thin walls count).
 */
@Mixin(ServerExplosion.class)
public abstract class BuildingExplosionMixin {
  @Shadow @Final private Vec3 center;

  @Shadow @Final private ServerLevel level;

  @ModifyExpressionValue(
      method = "getSeenPercent",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/phys/BlockHitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"))
  private static HitResult.Type craftq3$bspExposure(
      HitResult.Type original,
      @Local(argsOnly = true) Vec3 center,
      @Local(argsOnly = true) Entity entity,
      @Local(name = "from") Vec3 from) {
    return original == HitResult.Type.MISS && BuildingRules.occluded(entity.level(), from, center)
        ? HitResult.Type.BLOCK
        : original;
  }

  @WrapOperation(
      method = "calculateExplodedPositions",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/level/ServerLevel;isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z"))
  private boolean craftq3$bspPropagation(
      ServerLevel level,
      BlockPos pos,
      Operation<Boolean> original,
      @Local(name = "xp") double x,
      @Local(name = "yp") double y,
      @Local(name = "zp") double z,
      @Share("craftq3$previousSample") LocalRef<Vec3> previous) {
    if (!original.call(level, pos)) return false;
    if (BuildingRules.at(level, x) == null) return true;
    Vec3 sample = new Vec3(x, y, z);
    // Every ray starts at the centre; later samples continue from the one before.
    Vec3 from =
        previous.get() == null || (x == center.x && y == center.y && z == center.z)
            ? center
            : previous.get();
    previous.set(sample);
    return !BuildingRules.occluded(this.level, from, sample);
  }
}
