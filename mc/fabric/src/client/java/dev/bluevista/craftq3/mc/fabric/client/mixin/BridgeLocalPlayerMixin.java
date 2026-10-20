package dev.bluevista.craftq3.mc.fabric.client.mixin;

import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The local player does not move by itself while Quake owns its body (the bridge places it at the
 * Quake player every frame) or while a building region it is about to stand on is still loading (so
 * it does not fall through a BSP floor the client does not know yet).
 */
@Mixin(LocalPlayer.class)
public abstract class BridgeLocalPlayerMixin {
  @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
  private void craftq3$held(CallbackInfo callback) {
    if (CraftQ3ClientMod.holdsLocalPlayer(Minecraft.getInstance())) callback.cancel();
  }
}
