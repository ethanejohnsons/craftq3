package dev.bluevista.craftq3.mc.fabric.mixin;

import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No hunger, starvation or natural regeneration while Quake owns the player's health. */
@Mixin(FoodData.class)
public abstract class BridgeFoodMixin {
  @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
  private void craftq3$noHunger(ServerPlayer player, CallbackInfo ci) {
    if (BridgedPlayer.of(player) != null) ci.cancel();
  }
}
