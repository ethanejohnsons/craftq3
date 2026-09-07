package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.render.QuakeView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class BridgeHandMixin {
  @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
  private void craftq3$hands(CallbackInfo ci) {
    if (Minecraft.getInstance().gui.screen() instanceof QuakeView) ci.cancel();
  }
}
