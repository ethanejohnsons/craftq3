package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import dev.bluevista.craftq3.fabric.bridge.BridgePlayerShape;
import dev.bluevista.craftq3.fabric.render.BridgeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Original Quake dimensions drive native hit queries and aiming without changing saved poses. */
@Mixin(Entity.class)
public abstract class BridgeAvatarMixin {
  @Unique
  private BridgePlayerShape craftq3$shape() {
    if ((Object) this instanceof ServerPlayer player) {
      var controller = BridgePlayerController.get(player);
      return controller == null ? null : controller.shape();
    }
    if ((Object) this instanceof LocalPlayer player
        && Minecraft.getInstance().gui.screen() instanceof BridgeScreen screen)
      return screen.playerShape(player);
    return null;
  }

  @Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
  private void craftq3$bounds(CallbackInfoReturnable<AABB> ci) {
    var shape = craftq3$shape();
    if (shape == null) return;
    var entity = (Entity) (Object) this;
    var min = shape.bounds().min();
    var max = shape.bounds().max();
    ci.setReturnValue(
        new AABB(
            entity.getX() + min.x(),
            entity.getY() + min.y(),
            entity.getZ() + min.z(),
            entity.getX() + max.x(),
            entity.getY() + max.y(),
            entity.getZ() + max.z()));
  }

  @Inject(method = "getBbWidth", at = @At("RETURN"), cancellable = true)
  private void craftq3$width(CallbackInfoReturnable<Float> ci) {
    var shape = craftq3$shape();
    if (shape != null) ci.setReturnValue(shape.width());
  }

  @Inject(method = "getBbHeight", at = @At("RETURN"), cancellable = true)
  private void craftq3$height(CallbackInfoReturnable<Float> ci) {
    var shape = craftq3$shape();
    if (shape != null) ci.setReturnValue(shape.height());
  }

  @Inject(method = "getEyeHeight()F", at = @At("RETURN"), cancellable = true)
  private void craftq3$eye(CallbackInfoReturnable<Float> ci) {
    var shape = craftq3$shape();
    if (shape != null) ci.setReturnValue((float) shape.eyeHeight());
  }
}
