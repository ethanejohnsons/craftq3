package dev.bluevista.craftq3.mc.fabric.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The crosshair picks building-region BSP surfaces: when a BSP surface is nearer than any block,
 * the pick becomes a block hit on it whose position is the empty cell in front of the surface, so
 * using a block item there places the block in that cell (it replaces the "air" it clicked) and
 * entities behind the surface are not picked. Replaces {@code BuildingPickMixin}'s RETURN hook on
 * {@code Minecraft.pick} with a wrap of the block pick inside {@code LocalPlayer.pick}.
 */
@Mixin(LocalPlayer.class)
public abstract class BuildingPickMixin {
  @WrapOperation(
      method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
  private static HitResult craftq3$bspPick(
      Entity camera,
      double range,
      float partialTicks,
      boolean fluids,
      Operation<HitResult> original) {
    HitResult hit = original.call(camera, range, partialTicks, fluids);
    if (!(hit instanceof BlockHitResult block)) return hit;
    Vec3 from = camera.getEyePosition(partialTicks);
    Vec3 to = from.add(camera.getViewVector(partialTicks).scale(range));
    return BuildingRules.clip(camera.level(), from, to, block);
  }
}
