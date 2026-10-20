package dev.bluevista.craftq3.mc.fabric.client.mixin;

import dev.bluevista.craftq3.mc.fabric.client.render.Q3FullscreenView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render takeover for {@link Q3FullscreenView} screens.
 *
 * <p>{@code GameRenderer.extract} and {@code render} both gate all level work on their {@code
 * advanceGameTime} parameter ({@code readyForLevelRendering}/{@code shouldRenderLevel}). Clearing
 * it skips camera/level extraction, the HUD, the lightmap, {@code renderLevel}, the entity outline
 * and post chains in one place, while window/options extraction, the GUI and frame cleanup still
 * run. The parameter is selected by name, never by ordinal.
 *
 * <p>The Q3 pass is injected immediately before {@code GuiRenderer.render()}, after the main
 * target's depth was cleared for the GUI, so it covers the whole frame and the screen's own GUI
 * elements draw over it. Fabric API has no event between level and GUI rendering.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
  @ModifyVariable(
      method = {"extract", "render"},
      at = @At("HEAD"),
      argsOnly = true,
      name = "advanceGameTime")
  private boolean craftq3$suppressLevel(boolean advanceGameTime) {
    return advanceGameTime && !Q3FullscreenView.replacesLevel(Minecraft.getInstance());
  }

  @Inject(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
  private void craftq3$fullscreenPass(
      DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo callback) {
    Q3FullscreenView.renderBeforeGui(Minecraft.getInstance());
  }
}
