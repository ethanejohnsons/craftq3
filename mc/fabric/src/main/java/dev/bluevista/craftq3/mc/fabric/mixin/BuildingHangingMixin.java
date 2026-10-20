package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paintings and item frames hang on BSP walls: a support cell covered by BSP solid supports them
 * (the predicate passed to {@code Stream.allMatch} in {@code survives}, replacing the old lambda
 * target), and BSP solid in the way pops them like a block would.
 */
@Mixin(HangingEntity.class)
public abstract class BuildingHangingMixin {
  @WrapOperation(
      method = "survives",
      at =
          @At(
              value = "INVOKE",
              target = "Ljava/util/stream/Stream;allMatch(Ljava/util/function/Predicate;)Z"))
  private boolean craftq3$bspSupport(
      Stream<BlockPos> cells, Predicate<BlockPos> predicate, Operation<Boolean> original) {
    HangingEntity self = (HangingEntity) (Object) this;
    Predicate<BlockPos> withBsp =
        pos ->
            predicate.test(pos)
                || BuildingRules.blocksSolid(self.level(), new AABB(pos).deflate(0.25));
    return original.call(cells, withBsp);
  }

  @ModifyReturnValue(method = "hasLevelCollision", at = @At("RETURN"))
  private boolean craftq3$bspCollision(boolean original, AABB popBox) {
    return original
        || BuildingRules.blocksSolid(((HangingEntity) (Object) this).level(), popBox.deflate(0.01));
  }
}
