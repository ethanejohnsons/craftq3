package dev.bluevista.craftq3.fabric.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.bluevista.craftq3.fabric.building.BuildingSession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class BuildingLevelRendererMixin {
  @org.spongepowered.asm.mixin.injection.ModifyVariable(
      method = "render",
      at = @At("HEAD"),
      argsOnly = true)
  private Vector4f craftq3$background(Vector4f color) {
    var client = net.minecraft.client.Minecraft.getInstance();
    var session = BuildingSession.active();
    return session != null && client.player != null && session.matches(client.player)
        ? new Vector4f(0, 0, 0, 1)
        : color;
  }

  @Inject(
      method = {"addSkyPass", "addCloudsPass", "addWeatherPass"},
      at = @At("HEAD"),
      cancellable = true)
  private void craftq3$sky(CallbackInfo ci) {
    var client = net.minecraft.client.Minecraft.getInstance();
    var session = BuildingSession.active();
    if (session != null && client.player != null && session.matches(client.player)) ci.cancel();
  }

  @Inject(method = "render", at = @At("RETURN"))
  private void craftq3$bsp(
      GraphicsResourceAllocator allocator,
      DeltaTracker delta,
      boolean outline,
      CameraRenderState camera,
      Matrix4fc view,
      GpuBufferSlice fog,
      Vector4f color,
      boolean sky,
      CallbackInfo ci) {
    var session = BuildingSession.active();
    if (session != null) session.render(camera);
    if (net.minecraft.client.Minecraft.getInstance().gui.screen()
        instanceof dev.bluevista.craftq3.fabric.render.BridgeScreen bridge)
      bridge.drawWorld(camera);
  }
}
