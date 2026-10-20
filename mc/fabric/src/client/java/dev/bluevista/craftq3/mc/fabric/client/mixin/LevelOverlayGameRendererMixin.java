package dev.bluevista.craftq3.mc.fabric.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import dev.bluevista.craftq3.mc.fabric.client.bridge.BridgeScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quake III inside Minecraft's level ({@code Q3LevelOverlay}: the bridge's cgame frame, building
 * regions' BSPs). Replaces the old bridge half of {@code GameRendererMixin} and {@code
 * BridgeHandMixin}.
 *
 * <ul>
 *   <li>{@code update} HEAD: the overlays' frame runs before {@code Camera.update}, so the camera
 *       Minecraft culls and renders with is the cgame's view of this frame.
 *   <li>{@code renderLevel} at the hand pass's depth clear: overlays draw with this frame's
 *       projection (the {@code projectionMatrix} local, bobbing included) and the level's depth, so
 *       blocks and Quake geometry occlude each other. Fabric's {@code LevelRenderEvents} fire
 *       inside the level's frame graph, where the main target is not ours to draw into.
 *   <li>The hand pass and the hurt/walk view bobbing are skipped while the bridge is open: the
 *       cgame draws its own weapon and view kicks ({@code @WrapWithCondition} instead of cancelling
 *       {@code ItemInHandRenderer}, so other mods' hand hooks see a normal frame otherwise).
 * </ul>
 */
@Mixin(GameRenderer.class)
abstract class LevelOverlayGameRendererMixin {
  @Inject(method = "update", at = @At("HEAD"))
  private void craftq3$overlaysBeforeCamera(DeltaTracker deltaTracker, CallbackInfo callback) {
    CraftQ3ClientMod.beforeCamera(Minecraft.getInstance());
  }

  @Inject(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
  private void craftq3$overlaysInLevel(
      DeltaTracker deltaTracker,
      CallbackInfo callback,
      @Local(name = "projectionMatrix") Matrix4f projectionMatrix,
      @Local(name = "cameraState") CameraRenderState cameraState) {
    CraftQ3ClientMod.renderInLevel(
        Minecraft.getInstance(), projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
  }

  @WrapWithCondition(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"))
  private boolean craftq3$noHandInBridge(
      GameRenderer renderer, CameraRenderState cameraState, float partialTick, Matrix4fc view) {
    return BridgeScreen.current(Minecraft.getInstance()) == null;
  }

  @WrapWithCondition(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
  private boolean craftq3$noHurtBobInBridge(
      GameRenderer renderer, CameraRenderState cameraState, PoseStack poseStack) {
    return BridgeScreen.current(Minecraft.getInstance()) == null;
  }

  @WrapWithCondition(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
  private boolean craftq3$noWalkBobInBridge(
      GameRenderer renderer, CameraRenderState cameraState, PoseStack poseStack) {
    return BridgeScreen.current(Minecraft.getInstance()) == null;
  }
}
