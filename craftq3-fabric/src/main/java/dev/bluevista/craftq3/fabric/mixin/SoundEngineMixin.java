package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend;
import dev.bluevista.craftq3.fabric.audio.QuakeHostAudio;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ownership/lifetime hooks; allocation and playback remain in Minecraft's sound engine. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
  @Inject(method = "play", at = @At("HEAD"), cancellable = true)
  private void craftq3$hostPlayback(CallbackInfoReturnable<SoundEngine.PlayResult> callback) {
    if (QuakeHostAudio.active()) callback.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
  }

  @Inject(
      method = {"playDelayed", "queueTickingSound"},
      at = @At("HEAD"),
      cancellable = true)
  private void craftq3$hostQueue(CallbackInfo callback) {
    if (QuakeHostAudio.active()) callback.cancel();
  }

  @Inject(method = "stopAll", at = @At("HEAD"))
  private void craftq3$suspend(CallbackInfo callback) {
    MinecraftAudioBackend.suspend((SoundEngine) (Object) this, false);
  }

  @Inject(method = "destroy", at = @At("HEAD"))
  private void craftq3$destroy(CallbackInfo callback) {
    MinecraftAudioBackend.suspend((SoundEngine) (Object) this, true);
  }

  @Inject(
      method = "stopAll",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/sounds/ChannelAccess;clear()V",
              shift = At.Shift.AFTER))
  private void craftq3$discard(CallbackInfo callback) {
    MinecraftAudioBackend.sourcesCleared((SoundEngine) (Object) this);
  }

  @Inject(method = "stopAll", at = @At("RETURN"))
  private void craftq3$resume(CallbackInfo callback) {
    MinecraftAudioBackend.resume((SoundEngine) (Object) this, false);
  }

  @Inject(method = "loadLibrary", at = @At("RETURN"))
  private void craftq3$loaded(CallbackInfo callback) {
    MinecraftAudioBackend.resume((SoundEngine) (Object) this, true);
  }

  @Inject(method = "emergencyShutdown", at = @At("HEAD"))
  private void craftq3$emergency(CallbackInfo callback) {
    MinecraftAudioBackend.emergencyShutdown((SoundEngine) (Object) this);
  }

  @Inject(
      method = {"updateSource", "lambda$updateSource$0"},
      at = @At("HEAD"),
      cancellable = true)
  private void craftq3$listener(CallbackInfo callback) {
    if (MinecraftAudioBackend.ownsListener((SoundEngine) (Object) this)) callback.cancel();
  }
}
