package dev.bluevista.craftq3.mc.fabric.client.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sound manager's engine, which owns the OpenAL library, executor and channel pools. */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
  @Accessor("soundEngine")
  SoundEngine craftq3$soundEngine();
}
