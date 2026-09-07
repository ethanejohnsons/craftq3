package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class BridgeServerPlayerMixin
    implements dev.bluevista.craftq3.fabric.bridge.BridgeReturnState.Marker {
  @org.spongepowered.asm.mixin.Unique private String craftq3$marker = "";

  public String craftq3$bridgeMarker() {
    return craftq3$marker;
  }

  public void craftq3$bridgeMarker(String value) {
    craftq3$marker = value;
  }

  @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
  private void craftq3$loadMarker(
      net.minecraft.world.level.storage.ValueInput input,
      org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
    craftq3$marker = input.getStringOr("craftq3_bridge_return", "");
  }

  @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
  private void craftq3$saveMarker(
      net.minecraft.world.level.storage.ValueOutput output,
      org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
    if (!craftq3$marker.isEmpty()) output.putString("craftq3_bridge_return", craftq3$marker);
  }

  @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
  private void craftq3$damage(
      ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
    var bridge = BridgePlayerController.get((ServerPlayer) (Object) this);
    if (bridge != null && !bridge.acceptsDamage(source)) ci.setReturnValue(false);
  }
}
