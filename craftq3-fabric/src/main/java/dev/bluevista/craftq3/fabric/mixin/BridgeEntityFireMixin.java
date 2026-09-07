package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extinguishing ends mob-fire attribution immediately, including same-tick reignition. */
@Mixin(Entity.class)
public abstract class BridgeEntityFireMixin {
  @Inject(method = "setRemainingFireTicks", at = @At("HEAD"))
  private void craftq3$extinguish(int ticks, CallbackInfo ci) {
    if (ticks <= 0 && (Object) this instanceof ServerPlayer player) {
      var controller = BridgePlayerController.get(player);
      if (controller != null) controller.clearMobFire();
    }
  }
}
