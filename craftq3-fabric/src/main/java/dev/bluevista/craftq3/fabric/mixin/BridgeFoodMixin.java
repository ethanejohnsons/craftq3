package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class BridgeFoodMixin {
  @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
  private void craftq3$food(ServerPlayer player, CallbackInfo ci) {
    if (BridgePlayerController.get(player) != null) ci.cancel();
  }
}
