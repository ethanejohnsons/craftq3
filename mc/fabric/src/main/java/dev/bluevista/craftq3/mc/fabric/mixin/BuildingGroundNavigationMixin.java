package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Ground navigation's destination snaps onto BSP floors: {@code findSurfacePosition} walks an air
 * column down to the first block, which in a building region falls through BSP floors (they are not
 * blocks), so a mob sent to a spot over a BSP floor would plan to the bottom of the world. The
 * destination becomes the cell of the BSP floor under it, also when that floor is off the block
 * grid ({@link BuildingRules#navigationSurface}); Minecraft's answer stands when a block surface is
 * higher.
 */
@Mixin(GroundPathNavigation.class)
public abstract class BuildingGroundNavigationMixin extends PathNavigation {
  protected BuildingGroundNavigationMixin(Mob mob, Level level) {
    super(mob, level);
  }

  @WrapMethod(method = "findSurfacePosition")
  private BlockPos craftq3$bspFloor(
      LevelChunk chunk, BlockPos pos, int reachRange, Operation<BlockPos> original) {
    BlockPos surface = original.call(chunk, pos, reachRange);
    return BuildingRules.navigationSurface(level, pos, surface);
  }
}
