package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.building.BuildingNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GroundPathNavigation.class)
public abstract class BuildingGroundNavigationMixin extends PathNavigation {
  protected BuildingGroundNavigationMixin(Mob mob, Level level) {
    super(mob, level);
  }

  @Inject(method = "findSurfacePosition", at = @At("HEAD"), cancellable = true)
  private void craftq3$destination(
      LevelChunk chunk, BlockPos pos, int accuracy, CallbackInfoReturnable<BlockPos> result) {
    if (BuildingNavigation.floor(level, pos).isPresent()) result.setReturnValue(pos);
  }
}
