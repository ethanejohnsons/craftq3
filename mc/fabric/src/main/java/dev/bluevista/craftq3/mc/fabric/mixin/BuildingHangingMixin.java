package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paintings hang on BSP walls: a support cell whose face toward the painting is covered by BSP face
 * polygons ({@link BuildingRules#supports}, {@code SupportType.FULL}) supports it like a solid
 * block (the predicate passed to {@code Stream.allMatch} in {@code survives}, replacing the old
 * lambda target), and BSP solid in the way pops paintings and item frames like a block would. Item
 * frames have their own {@code survives} ({@code BuildingItemFrameMixin}).
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
    Direction facing = self.getDirection();
    Predicate<BlockPos> withBsp =
        pos ->
            predicate.test(pos)
                || BuildingRules.supports(self.level(), pos, facing, SupportType.FULL);
    return original.call(cells, withBsp);
  }

  @ModifyReturnValue(method = "hasLevelCollision", at = @At("RETURN"))
  private boolean craftq3$bspCollision(boolean original, AABB popBox) {
    return original
        || BuildingRules.blocksSolid(((HangingEntity) (Object) this).level(), popBox.deflate(0.01));
  }
}
