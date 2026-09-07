package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.render.QuakeInputView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The active Q3 input screen consumes raw window deltas without turning Minecraft's player. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
  @Shadow private double accumulatedDX;
  @Shadow private double accumulatedDY;

  @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true)
  private void craftq3$mouse(CallbackInfo callback) {
    var minecraft = Minecraft.getInstance();
    if (!(minecraft.gui.screen() instanceof QuakeInputView screen)) return;
    if (!minecraft.isWindowActive() || minecraft.gui.overlay() != null) screen.inputFocusLost();
    else if (screen.cursorCaptured()) screen.mouseDelta(accumulatedDX, accumulatedDY);
    accumulatedDX = accumulatedDY = 0;
    callback.cancel();
  }
}
