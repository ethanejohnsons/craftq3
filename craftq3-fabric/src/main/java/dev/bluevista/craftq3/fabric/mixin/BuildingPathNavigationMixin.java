package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.fabric.building.BuildingNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PathNavigation.class)
public abstract class BuildingPathNavigationMixin {
  @Shadow @Final protected Level level;

  @ModifyReturnValue(method = "getGroundY", at = @At("RETURN"))
  private double craftq3$ground(double original, Vec3 point) {
    if (!((Object) this instanceof GroundPathNavigation)) return original;
    var pos = BlockPos.containing(point);
    var floor = BuildingNavigation.floor(level, pos);
    if (floor.isEmpty()) return original;
    return level.getBlockState(pos.below()).isAir()
        ? floor.getAsDouble()
        : Math.max(original, floor.getAsDouble());
  }

  @ModifyReturnValue(method = "isStableDestination", at = @At("RETURN"))
  private boolean craftq3$stable(boolean original, BlockPos pos) {
    return original
        || (Object) this instanceof GroundPathNavigation
            && BuildingNavigation.floor(level, pos).isPresent();
  }
}
