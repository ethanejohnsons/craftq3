package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.render.QuakeView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World extraction/drawing is disabled while Q3 owns the view; host GUI/frame cleanup still run.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
  @ModifyVariable(
      method = {"extract", "render"},
      at = @At("HEAD"),
      argsOnly = true,
      ordinal = 0)
  private boolean craftq3$worldEnabled(boolean enabled) {
    return enabled
        && !(Minecraft.getInstance().gui.screen() instanceof QuakeView view
            && view.replacesMinecraftWorld());
  }

  @Inject(method = "extract", at = @At("HEAD"))
  private void craftq3$prepare(CallbackInfo callback) {
    if (Minecraft.getInstance().gui.screen() instanceof QuakeView view
        && !(view instanceof dev.bluevista.craftq3.fabric.render.BridgeScreen)) view.prepareFrame();
  }

  @Inject(method = "update", at = @At("HEAD"))
  private void craftq3$bridgeBeforeCamera(CallbackInfo callback) {
    // Camera.update precedes extraction in 26.2; cgame's current view must exist already.
    if (Minecraft.getInstance().gui.screen()
        instanceof dev.bluevista.craftq3.fabric.render.BridgeScreen bridge) bridge.prepareFrame();
  }

  @Inject(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
  private void craftq3$draw(CallbackInfo callback) {
    var client = Minecraft.getInstance();
    if (client.gui.screen() instanceof QuakeView screen) {
      try {
        screen.drawFrame();
      } catch (RuntimeException e) {
        CraftQ3Client.LOGGER.error("CraftQ3 rendering failed; returning to Minecraft", e);
        client.gui.setScreen(null);
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
            && (Boolean.getBoolean("craftq3.capture")
                || Boolean.getBoolean("craftq3.playCapture")
                || Boolean.getBoolean("craftq3.uiCapture")
                || Boolean.getBoolean("craftq3.lifecycleCapture"))) client.stop();
      }
    }
  }

  @Inject(method = "render", at = @At("RETURN"))
  private void craftq3$capture(CallbackInfo callback) {
    if (Minecraft.getInstance().gui.screen() instanceof QuakeView screen) screen.endFrame();
  }

  @Inject(
      method = {"bobHurt", "bobView"},
      at = @At("HEAD"),
      cancellable = true)
  private void craftq3$cameraEffects(CallbackInfo ci) {
    if (Minecraft.getInstance().gui.screen()
        instanceof dev.bluevista.craftq3.fabric.render.BridgeScreen) ci.cancel();
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void craftq3$close(CallbackInfo callback) {
    var screen = Minecraft.getInstance().gui.screen();
    if (screen instanceof QuakeView) screen.removed();
  }
}
