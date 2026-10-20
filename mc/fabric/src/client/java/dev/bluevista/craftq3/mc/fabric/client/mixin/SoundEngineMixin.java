package dev.bluevista.craftq3.mc.fabric.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.bluevista.craftq3.mc.fabric.client.audio.SoundEngineHooks;
import dev.bluevista.craftq3.mc.fabric.client.audio.SoundEngineLifecycle;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla suppression and OpenAL lifecycle fencing for Quake III audio. See docs/MIXINS.md.
 *
 * <ul>
 *   <li>{@code play(SoundInstance)} is the single funnel every vanilla sound passes through: {@code
 *       SoundManager.play}, delayed sounds ({@code playDelayed}) and queued ticking sounds ({@code
 *       queueTickingSound}) all start from it on a later tick. Wrapping it keeps Minecraft silent
 *       while a Quake view owns audio.
 *   <li>{@code stopAll} joins the sound executor and then starts a new one; the hook between the
 *       two is the only point where OpenAL is quiescent, so Quake III sources are released there.
 *   <li>{@code destroy}, {@code loadLibrary} and {@code emergencyShutdown} bracket the OpenAL
 *       context.
 * </ul>
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin implements SoundEngineHooks {
  @Shadow private boolean loaded;

  @Unique private volatile boolean craftq3$muted;
  @Unique private volatile SoundEngineLifecycle craftq3$lifecycle;

  @Override
  public void craftq3$setVanillaMuted(boolean muted) {
    craftq3$muted = muted;
  }

  @Override
  public boolean craftq3$vanillaMuted() {
    return craftq3$muted;
  }

  @Override
  public void craftq3$setLifecycle(SoundEngineLifecycle lifecycle) {
    craftq3$lifecycle = lifecycle;
  }

  @WrapMethod(method = "play")
  private SoundEngine.PlayResult craftq3$suppressVanilla(
      SoundInstance instance, Operation<SoundEngine.PlayResult> original) {
    if (craftq3$muted) return SoundEngine.PlayResult.NOT_STARTED;
    return original.call(instance);
  }

  @Inject(method = "destroy", at = @At("HEAD"))
  private void craftq3$beforeDestroy(CallbackInfo callback) {
    SoundEngineLifecycle lifecycle = craftq3$lifecycle;
    if (lifecycle != null) lifecycle.beforeDestroy();
  }

  @Inject(
      method = "stopAll",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/sounds/SoundEngineExecutor;startUp()V"))
  private void craftq3$executorStopped(CallbackInfo callback) {
    SoundEngineLifecycle lifecycle = craftq3$lifecycle;
    if (lifecycle != null) lifecycle.executorStopped();
  }

  @Inject(method = "loadLibrary", at = @At("RETURN"))
  private void craftq3$libraryLoaded(CallbackInfo callback) {
    SoundEngineLifecycle lifecycle = craftq3$lifecycle;
    if (lifecycle != null) lifecycle.libraryLoaded(loaded);
  }

  @Inject(method = "emergencyShutdown", at = @At("HEAD"))
  private void craftq3$emergencyShutdown(CallbackInfo callback) {
    SoundEngineLifecycle lifecycle = craftq3$lifecycle;
    if (lifecycle != null) lifecycle.emergencyShutdown();
  }
}
