package dev.bluevista.craftq3.mc.fabric.client.audio;

import dev.bluevista.craftq3.mc.fabric.client.mixin.SoundManagerAccessor;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;

/**
 * The client's Quake III audio host, owned by {@code CraftQ3ClientMod} and closed when the client
 * stops. It opens {@link MinecraftAudioDevice}s on Minecraft's sound engine, forwards the engine's
 * lifecycle to them, and lets a Quake view take audio over from Minecraft ({@link
 * #claimHostAudio()}).
 *
 * <p>Call everything on the render thread, which is the thread that drives {@link SoundManager}.
 */
public final class MinecraftAudio implements AutoCloseable {
  private final Minecraft client;
  private final List<MinecraftAudioDevice> devices = new CopyOnWriteArrayList<>();
  private final SoundEngineLifecycle lifecycle =
      new SoundEngineLifecycle() {
        @Override
        public void beforeDestroy() {
          for (MinecraftAudioDevice device : devices) device.beforeDestroy();
        }

        @Override
        public void executorStopped() {
          for (MinecraftAudioDevice device : devices) device.executorStopped();
        }

        @Override
        public void libraryLoaded(boolean loaded) {
          for (MinecraftAudioDevice device : devices) device.libraryLoaded();
        }

        @Override
        public void emergencyShutdown() {
          for (MinecraftAudioDevice device : devices) device.emergencyShutdown();
        }
      };
  private SoundEngine engine;
  private int claims;
  private boolean closed;

  public MinecraftAudio(Minecraft client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  /**
   * Opens a device on Minecraft's sound engine. The caller closes it; any still open when the
   * client stops are closed then.
   */
  public MinecraftAudioDevice openDevice() {
    if (closed) throw new IllegalStateException("The client's audio host is closed");
    SoundEngine soundEngine = engine();
    MinecraftAudioDevice[] holder = new MinecraftAudioDevice[1];
    MinecraftAudioDevice device =
        new MinecraftAudioDevice(
            soundEngine,
            () -> client.options.getFinalSoundSourceVolume(SoundSource.MASTER),
            () -> devices.remove(holder[0]));
    holder[0] = device;
    devices.add(device);
    return device;
  }

  /** Devices currently open. */
  public int openDevices() {
    return devices.size();
  }

  /**
   * Takes audio over from Minecraft until the returned claim is closed: every vanilla sound playing
   * now is stopped and none starts while any claim is held. Claims nest.
   */
  public HostAudioClaim claimHostAudio() {
    if (closed) throw new IllegalStateException("The client's audio host is closed");
    SoundEngineHooks hooks = (SoundEngineHooks) engine();
    if (claims++ == 0) {
      hooks.craftq3$setVanillaMuted(true);
      SoundManager sounds = client.getSoundManager();
      // Per category, so the engine's executor keeps running (stopAll would restart it).
      for (SoundSource source : SoundSource.values()) sounds.stop(null, source);
    }
    return new HostAudioClaim(this);
  }

  /** Whether Minecraft's own sounds are suppressed right now. */
  public boolean vanillaMuted() {
    return engine != null && ((SoundEngineHooks) engine).craftq3$vanillaMuted();
  }

  void release(HostAudioClaim claim) {
    if (claims > 0 && --claims == 0 && engine != null)
      ((SoundEngineHooks) engine).craftq3$setVanillaMuted(false);
  }

  /** Closes every open device, releases the engine hooks and gives audio back to Minecraft. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    for (MinecraftAudioDevice device : devices) device.close();
    devices.clear();
    if (engine != null) {
      SoundEngineHooks hooks = (SoundEngineHooks) engine;
      hooks.craftq3$setVanillaMuted(false);
      hooks.craftq3$setLifecycle(null);
    }
    claims = 0;
  }

  private SoundEngine engine() {
    if (engine == null) {
      engine = ((SoundManagerAccessor) client.getSoundManager()).craftq3$soundEngine();
      ((SoundEngineHooks) engine).craftq3$setLifecycle(lifecycle);
    }
    return engine;
  }

  /** A Quake view's hold on audio; closing it more than once has no further effect. */
  public static final class HostAudioClaim implements AutoCloseable {
    private final MinecraftAudio owner;
    private boolean released;

    private HostAudioClaim(MinecraftAudio owner) {
      this.owner = owner;
    }

    @Override
    public void close() {
      if (released) return;
      released = true;
      owner.release(this);
    }
  }
}
