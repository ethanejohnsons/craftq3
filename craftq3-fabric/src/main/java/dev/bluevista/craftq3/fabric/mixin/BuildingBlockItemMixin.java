package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.building.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft block placement cannot bury a new collision shape inside the immutable BSP. */
@Mixin(BlockItem.class)
public abstract class BuildingBlockItemMixin {
  @Inject(method = "canPlace", at = @At("RETURN"), cancellable = true)
  private void craftq3$placement(
      BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> ci) {
    if (!ci.getReturnValue()) return;
    var pos = context.getClickedPos();
    var environment = BuildingWorlds.at(context.getLevel(), pos.getX());
    if (environment == null) return;
    var occupied = state.getCollisionShape(context.getLevel(), pos);
    if (occupied.isEmpty()) occupied = state.getShape(context.getLevel(), pos);
    for (var box : occupied.toAabbs()) {
      var shape = box.move(pos).deflate(.001);
      if (shape.maxX <= shape.minX || shape.maxY <= shape.minY || shape.maxZ <= shape.minZ)
        continue;
      if (!environment
          .geometry()
          .clear(
              new BuildingGeometry.Box(
                  new Vec3(shape.minX, shape.minY, shape.minZ),
                  new Vec3(shape.maxX, shape.maxY, shape.maxZ)))) {
        ci.setReturnValue(false);
        return;
      }
    }
  }
}
