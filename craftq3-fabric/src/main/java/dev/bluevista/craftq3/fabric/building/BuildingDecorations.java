package dev.bluevista.craftq3.fabric.building;

import net.minecraft.core.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SupportType;

/** Native decoration support and adjustment of an outside BSP placement cell. */
public final class BuildingDecorations {
  private BuildingDecorations() {}

  public static boolean supports(Level level, BlockPos pos, Direction face) {
    var environment = BuildingWorlds.at(level, pos.getX());
    return environment != null
        && environment
            .support()
            .supports(level.getBlockState(pos), level, pos, face, SupportType.FULL);
  }

  public static BlockPos clicked(UseOnContext context, BlockPos pos) {
    var face = context.getClickedFace();
    var behind = pos.relative(face.getOpposite());
    return context.getLevel().getBlockState(pos).isAir()
            && supports(context.getLevel(), behind, face)
        ? behind
        : pos;
  }
}
