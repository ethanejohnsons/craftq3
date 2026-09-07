package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.render.BridgeScreen;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera bob/eye height belongs to cgame; the Minecraft body remains at Quake's actual feet. */
@Mixin(Camera.class)
public abstract class BridgeCameraMixin {
  @Shadow
  protected abstract void setPosition(Vec3 position);

  @Shadow
  protected abstract void setRotation(float yaw, float pitch);

  @Shadow private boolean detached;
  @Shadow @org.spongepowered.asm.mixin.Final private org.joml.Quaternionf rotation;
  @Shadow @org.spongepowered.asm.mixin.Final private org.joml.Vector3f forwards;
  @Shadow @org.spongepowered.asm.mixin.Final private org.joml.Vector3f up;
  @Shadow @org.spongepowered.asm.mixin.Final private org.joml.Vector3f left;

  @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
  private float craftq3$fov(float original) {
    return Minecraft.getInstance().gui.screen() instanceof BridgeScreen bridge
            && bridge.worldView() != null
        ? bridge.worldView().fovY()
        : original;
  }

  @com.llamalad7.mixinextras.injector.ModifyReturnValue(
      method = "getViewRotationMatrix",
      at = @At("RETURN"))
  private org.joml.Matrix4f craftq3$rotation(org.joml.Matrix4f original) {
    if (Minecraft.getInstance().gui.screen() instanceof BridgeScreen bridge
        && bridge.worldView() != null) original.set(bridge.cameraRotation());
    return original;
  }

  @Inject(method = "extractRenderState", at = @At("RETURN"))
  private void craftq3$orientation(
      net.minecraft.client.renderer.state.level.CameraRenderState state,
      float partialTicks,
      CallbackInfo ci) {
    if (Minecraft.getInstance().gui.screen() instanceof BridgeScreen bridge
        && bridge.worldView() != null)
      state.orientation.setFromNormalized(bridge.cameraRotation().transpose());
  }

  @Inject(method = "alignWithEntity", at = @At("RETURN"))
  private void craftq3$camera(float partialTicks, CallbackInfo ci) {
    if (Minecraft.getInstance().gui.screen() instanceof BridgeScreen bridge
        && bridge.cameraPosition() != null
        && bridge.worldView() != null) {
      var ref = bridge.worldView();
      var forward = ref.axisX();
      setPosition(bridge.cameraPosition());
      setRotation(
          (float) Math.toDegrees(Math.atan2(-forward.x(), -forward.y())),
          (float) Math.toDegrees(Math.atan2(-forward.z(), Math.hypot(forward.x(), forward.y()))));
      rotation.setFromNormalized(bridge.cameraRotation().transpose());
      forwards.set((float) forward.x(), (float) forward.z(), (float) -forward.y());
      up.set((float) ref.axisZ().x(), (float) ref.axisZ().z(), (float) -ref.axisZ().y());
      left.set((float) ref.axisY().x(), (float) ref.axisY().z(), (float) -ref.axisY().y());
      // Cgame supplies the third-person Quake body; never add the native avatar over it.
      detached = false;
    }
  }
}
