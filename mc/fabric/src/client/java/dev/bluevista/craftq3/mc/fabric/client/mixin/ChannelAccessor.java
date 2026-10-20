package dev.bluevista.craftq3.mc.fabric.client.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The OpenAL source name behind a pooled {@link Channel}. {@code Channel} only attaches Minecraft's
 * own buffer and stream types; Quake III voices need a start offset and streams need explicit
 * buffer queueing, so the device issues those calls on the source directly.
 */
@Mixin(Channel.class)
public interface ChannelAccessor {
  @Accessor("source")
  int craftq3$source();
}
