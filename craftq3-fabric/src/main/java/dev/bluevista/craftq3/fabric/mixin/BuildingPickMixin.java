package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.building.BuildingSession;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class BuildingPickMixin {
  @Inject(method = "pick(F)V", at = @At("RETURN"))
  private void craftq3$pick(float partial, CallbackInfo ci) {
    var client = (Minecraft) (Object) this;
    var session = BuildingSession.active();
    if (session == null || client.player == null || !session.matches(client.player)) return;
    var eye = client.player.getEyePosition(partial);
    var end =
        eye.add(client.player.getViewVector(partial).scale(client.player.blockInteractionRange()));
    session
        .geometry()
        .pick(new Vec3(eye.x, eye.y, eye.z), new Vec3(end.x, end.y, end.z))
        .ifPresent(
            hit -> {
              var point =
                  new net.minecraft.world.phys.Vec3(
                      hit.point().x(), hit.point().y(), hit.point().z());
              if (client.hitResult != null
                  && client.hitResult.getType() != HitResult.Type.MISS
                  && client.hitResult.getLocation().distanceToSqr(eye) <= point.distanceToSqr(eye))
                return;
              var normal = hit.normal();
              var cell = hit.cell();
              var placement = hit.placementPoint();
              client.hitResult =
                  new BlockHitResult(
                      new net.minecraft.world.phys.Vec3(
                          placement.x(), placement.y(), placement.z()),
                      Direction.getApproximateNearest(
                          (float) normal.x(), (float) normal.y(), (float) normal.z()),
                      new BlockPos((int) cell.x(), (int) cell.y(), (int) cell.z()),
                      false);
              client.crosshairPickEntity = null;
            });
  }
}
