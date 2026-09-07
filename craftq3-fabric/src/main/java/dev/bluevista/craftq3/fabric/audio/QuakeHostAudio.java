package dev.bluevista.craftq3.fabric.audio;

import dev.bluevista.craftq3.fabric.render.QuakeView;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** Scope host SoundInstances to the host view; Q3 PCM uses the shared source pool directly. */
public final class QuakeHostAudio {
  private QuakeHostAudio() {}

  public static boolean active() {
    Minecraft client = Minecraft.getInstance();
    return client != null && client.gui != null && client.gui.screen() instanceof QuakeView;
  }

  public static void enter(Minecraft client) {
    client.getMusicManager().stopPlaying();
    // A null category calls SoundEngine.stopAll, which also destroys Q3's shared sources.
    // Category-scoped stops visit only Minecraft's own SoundInstance registrations.
    for (SoundSource source : SoundSource.values()) client.getSoundManager().stop(null, source);
  }
}
