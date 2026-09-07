package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.building.BuildingSession;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public abstract class BuildingProjectionMixin {
  @ModifyArg(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
      index = 0)
  private Matrix4f craftq3$projection(Matrix4f matrix) {
    var session = BuildingSession.active();
    if (session != null) session.projection(matrix);
    if (net.minecraft.client.Minecraft.getInstance().gui.screen()
        instanceof dev.bluevista.craftq3.fabric.render.BridgeScreen bridge)
      bridge.projection(matrix);
    return matrix;
  }
}
