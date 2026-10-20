package dev.bluevista.craftq3.mc.fabric.client.mixin;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What {@code MinecraftAudioDevice} needs from the engine: whether OpenAL is up, the sound executor
 * (the only thread that issues OpenAL calls) and the library whose channel pools it shares.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
  @Accessor("loaded")
  boolean craftq3$loaded();

  @Accessor("executor")
  SoundEngineExecutor craftq3$executor();

  @Accessor("library")
  Library craftq3$library();
}
