package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.render.BridgeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class BridgeLocalPlayerMixin {
  @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
  private void craftq3$movement(CallbackInfo ci) {
    if (Minecraft.getInstance().gui.screen() instanceof BridgeScreen) {
      var player = (LocalPlayer) (Object) this;
      player.noPhysics = true;
      player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
      ci.cancel();
    }
  }
}
