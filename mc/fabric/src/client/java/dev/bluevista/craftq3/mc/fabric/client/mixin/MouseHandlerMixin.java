package dev.bluevista.craftq3.mc.fabric.client.mixin;

import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputCapture;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Relative mouse motion for {@link Q3InputView} screens.
 *
 * <p>{@code onMove} accumulates cursor motion into {@code accumulatedDX/DY} and {@code
 * handleAccumulatedMovement} spends it once per frame: on the screen's {@code mouseMoved}/{@code
 * mouseDragged} and, while Minecraft holds the mouse, on {@code turnPlayer}. At its head, while a
 * Q3 input view is open, the deltas are handed to the view's capture and zeroed, so nothing later
 * in the frame (the screen callbacks or the camera) sees them. The method then runs normally and
 * keeps its own frame-time bookkeeping; nothing is cancelled. Fabric API has no mouse-motion event
 * ({@code ScreenMouseEvents} only covers buttons and scrolling), and the fields are spent in two
 * methods, so one HEAD hook replaces per-read {@code @ModifyExpressionValue}s.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
  @Shadow @Final private Minecraft minecraft;
  @Shadow private double accumulatedDX;
  @Shadow private double accumulatedDY;

  @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
  private void craftq3$captureMotion(CallbackInfo callback) {
    Q3InputCapture capture = Q3InputView.current(minecraft);
    if (capture == null) return;
    double dx = accumulatedDX;
    double dy = accumulatedDY;
    accumulatedDX = 0;
    accumulatedDY = 0;
    Q3InputView.deliver(minecraft, capture, () -> capture.mouseMoved(dx, dy));
  }
}
