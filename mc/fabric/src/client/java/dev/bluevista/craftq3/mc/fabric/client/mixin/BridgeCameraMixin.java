package dev.bluevista.craftq3.mc.fabric.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.mc.fabric.client.bridge.BridgeCameraPose;
import dev.bluevista.craftq3.mc.fabric.client.bridge.BridgeScreen;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's camera is the cgame's view while the bridge is open: after {@code alignWithEntity} it
 * takes the pose of the frame's world view (position, rotation including roll, the forward, up and
 * left vectors, yaw and pitch, always first person so the Minecraft body never draws over Quake's),
 * and {@code calculateFov} returns the view's vertical field of view. Culling, the level pass and
 * sound all follow. Because the pose is set before {@code Camera.update} derives its matrices, the
 * old {@code getViewRotationMatrix} and {@code extractRenderState} overrides are not needed.
 */
@Mixin(Camera.class)
public abstract class BridgeCameraMixin {
  @Shadow private float xRot;
  @Shadow private float yRot;
  @Shadow private boolean detached;
  @Shadow private int matrixPropertiesDirty;
  @Shadow @Final private Quaternionf rotation;
  @Shadow @Final private Vector3f forwards;
  @Shadow @Final private Vector3f up;
  @Shadow @Final private Vector3f left;

  @Shadow
  protected abstract void setPosition(Vec3 position);

  @Inject(method = "alignWithEntity", at = @At("RETURN"))
  private void craftq3$cgameView(float partialTicks, CallbackInfo callback) {
    BridgeScreen bridge = BridgeScreen.current(Minecraft.getInstance());
    BridgeCameraPose pose = bridge == null ? null : bridge.pose();
    if (pose == null) return;
    setPosition(pose.position());
    xRot = pose.xRot();
    yRot = pose.yRot();
    rotation.set(pose.rotation());
    forwards.set(pose.forwards());
    up.set(pose.up());
    left.set(pose.left());
    detached = false;
    matrixPropertiesDirty |= 3;
  }

  @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
  private float craftq3$cgameFov(float original) {
    BridgeScreen bridge = BridgeScreen.current(Minecraft.getInstance());
    BridgeCameraPose pose = bridge == null ? null : bridge.pose();
    return pose == null ? original : pose.fovY();
  }
}
