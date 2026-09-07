package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class BridgePlayerMixin {
  @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
  private void craftq3$damage(
      ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
    if ((Object) this instanceof ServerPlayer player) {
      var bridge = BridgePlayerController.get(player);
      if (bridge != null) {
        bridge.damage(amount);
        dev.bluevista.craftq3.fabric.bridge.BridgeBlazeSmoke.damage(player, source);
        dev.bluevista.craftq3.fabric.bridge.BridgeFireSmoke.damage(player, source, amount);
        dev.bluevista.craftq3.fabric.bridge.BridgeFireballSmoke.damage(player, source, amount);
        ci.cancel();
      }
    }
  }

  @Inject(method = "tick", at = @At("TAIL"))
  private void craftq3$hold(CallbackInfo ci) {
    if ((Object) this instanceof ServerPlayer player) {
      var bridge = BridgePlayerController.get(player);
      if (bridge != null) bridge.hold();
    }
  }
}
