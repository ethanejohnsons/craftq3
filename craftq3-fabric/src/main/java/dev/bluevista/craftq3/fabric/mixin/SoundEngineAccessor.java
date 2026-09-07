package dev.bluevista.craftq3.fabric.mixin;

import com.mojang.blaze3d.audio.Listener;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
  @Accessor("loaded")
  boolean craftq3$loaded();

  @Accessor("channelAccess")
  ChannelAccess craftq3$channels();

  @Accessor("executor")
  SoundEngineExecutor craftq3$executor();

  @Accessor("listener")
  Listener craftq3$listener();
}
