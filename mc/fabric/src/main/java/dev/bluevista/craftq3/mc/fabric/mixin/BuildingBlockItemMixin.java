package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A placed block may not bury its collision (or outline) shape inside the immutable BSP. */
@Mixin(BlockItem.class)
public abstract class BuildingBlockItemMixin {
  @ModifyReturnValue(method = "canPlace", at = @At("RETURN"))
  private boolean craftq3$notInsideBsp(
      boolean original, BlockPlaceContext context, BlockState state) {
    if (!original) return false;
    Level level = context.getLevel();
    BlockPos pos = context.getClickedPos();
    if (BuildingRules.at(level, pos.getX() + 0.5) == null) return true;
    VoxelShape shape = state.getCollisionShape(level, pos);
    if (shape.isEmpty()) shape = state.getShape(level, pos);
    for (AABB box : shape.toAabbs()) {
      AABB inside = box.move(pos).deflate(0.001);
      if (inside.getXsize() <= 0 || inside.getYsize() <= 0 || inside.getZsize() <= 0) continue;
      if (BuildingRules.blocksSolid(level, inside)) return false;
    }
    return true;
  }
}
