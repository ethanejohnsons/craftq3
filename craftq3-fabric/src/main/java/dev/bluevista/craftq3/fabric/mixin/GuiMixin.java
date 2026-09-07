package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.render.QuakeView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An independent Q3 view has only its own diagnostics, including when entered from a MC world. */
@Mixin(Gui.class)
public abstract class GuiMixin {
  @Shadow @Final private GuiRenderState guiRenderState;

  @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
  private void craftq3$extract(
      DeltaTracker delta, boolean world, boolean gui, CallbackInfo callback) {
    var client = Minecraft.getInstance();
    var screen = client.gui.screen();
    if (client.gui.overlay() != null || !(screen instanceof QuakeView)) return;
    guiRenderState.reset();
    int x = (int) client.mouseHandler.getScaledXPos(client.getWindow());
    int y = (int) client.mouseHandler.getScaledYPos(client.getWindow());
    var graphics = new GuiGraphicsExtractor(client, guiRenderState, x, y);
    screen.extractRenderState(graphics, x, y, delta.getGameTimeDeltaTicks());
    graphics.applyCursor(client.getWindow());
    callback.cancel();
  }
}
