package dev.bluevista.craftq3.fabric.audio;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.ListenerTransform;
import com.mojang.blaze3d.audio.SoundBuffer;
import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.mixin.SoundEngineAccessor;
import dev.bluevista.craftq3.fabric.mixin.SoundManagerAccessor;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.platform.audio.PcmStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess.ChannelHandle;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;

/**
 * Q3 PCM on Minecraft's existing source pool, executor and listener. Construct on the client
 * thread; PCM registration may run on a loading worker. No device, context, or raw OpenAL entry
 * points are created here. One session may own a SoundEngine at a time, and first endFrame acquires
 * its listener.
 */
public final class MinecraftAudioBackend implements AudioBackend {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final int MAX_SOUNDS = 4096, MAX_VOICES = 128;
  private static final long MAX_PCM_BYTES = 64L * 1024 * 1024,
      MAX_NATIVE_BYTES = 128L * 1024 * 1024;
  private static final Map<SoundEngine, MinecraftAudioBackend> OWNERS = new IdentityHashMap<>();
  private static final java.util.concurrent.ScheduledExecutorService STREAM_TICKS =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            var thread = new Thread(r, "CraftQ3-stream-refills");
            thread.setDaemon(true);
            return thread;
          });
  private java.util.concurrent.ScheduledFuture<?> streamTicks;
  private boolean streamTickQueued;
  private final Minecraft client;
  private final SoundEngine engine;
  private final SoundEngineAccessor access;
  private final CoordinateTransform transform;
  private final AudioAssetCache<SoundBuffer> assets =
      new AudioAssetCache<>(MAX_SOUNDS, MAX_PCM_BYTES, MAX_NATIVE_BYTES);
  private final Map<Long, Voice> voices = new LinkedHashMap<>();
  private final Map<Integer, Long> loopVoices = new HashMap<>();
  private final Map<Integer, Loop> submittedLoops = new HashMap<>();
  private final Vec3[] entityPositions = new Vec3[1024];
  private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
  private Listener listener = new Listener(ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1));
  private ListenerTransform previousListener;
  private volatile boolean listenerOwned, closed, registered;
  private boolean suspended, tearingDown, frameOpen;
  private long sequence, generation, started, failures;
  private float gain = 1, hostGain;
  private String lastFailure = "";

  private static final class Voice {
    final long id, generation;
    final boolean loop;
    Playback playback;
    ChannelHandle handle;
    boolean started, allocating, streamFailed;
    PcmStream stream;
    float streamGain;

    Voice(long id, long generation, Playback playback, boolean loop) {
      this.id = id;
      this.generation = generation;
      this.playback = playback;
      this.loop = loop;
    }
  }

  public MinecraftAudioBackend(Minecraft client) {
    this(client, new CoordinateTransform(32, ZERO));
  }

  public MinecraftAudioBackend(Minecraft client, CoordinateTransform transform) {
    this(client, transform, false);
  }

  /** Prepare bounded PCM registrations without acquiring the source session's sound engine. */
  public static MinecraftAudioBackend prepare(Minecraft client, CoordinateTransform transform) {
    return new MinecraftAudioBackend(client, transform, true);
  }

  private MinecraftAudioBackend(Minecraft client, CoordinateTransform transform, boolean deferred) {
    this.client = java.util.Objects.requireNonNull(client);
    this.transform = java.util.Objects.requireNonNull(transform);
    engine = ((SoundManagerAccessor) client.getSoundManager()).craftq3$soundEngine();
    access = (SoundEngineAccessor) engine;
    hostGain = client.options.getFinalSoundSourceVolume(SoundSource.MASTER);
    if (!deferred) acquire();
  }

  private synchronized void acquire() {
    checkOpen();
    synchronized (OWNERS) {
      var previous = OWNERS.putIfAbsent(engine, this);
      if (previous != null && previous != this)
        throw new IllegalStateException("Q3 audio session already owns this sound engine");
      registered = true;
    }
  }

  /** The source must already be closing; acquire only after native buffer/listener cleanup. */
  public CompletableFuture<Void> activateAfterRelease() {
    var previous = owner(engine);
    if (previous != null && previous != this && !previous.closed)
      return CompletableFuture.failedFuture(
          new IllegalStateException("Source audio is still active"));
    var ready =
        previous == null || previous == this
            ? CompletableFuture.<Void>completedFuture(null)
            : previous.closeCompletion();
    return ready.thenRun(this::acquire);
  }

  @Override
  public synchronized int register(String name, PcmSound sound) {
    checkOpen();
    return assets.register(name, sound);
  }

  @Override
  public synchronized long play(Playback playback) {
    checkOpen();
    checkSound(playback.sound());
    if (playback.channel() != CHAN_AUTO && playback.entity() >= 0) {
      for (Voice voice : List.copyOf(voices.values())) {
        if (!voice.loop
            && voice.stream == null
            && voice.playback.entity() == playback.entity()
            && voice.playback.channel() == playback.channel()) stop(voice.id);
      }
    }
    return start(playback, false);
  }

  @Override
  public synchronized long stream(PcmStream source, float streamGain) {
    java.util.Objects.requireNonNull(source);
    try {
      checkOpen();
      if (!Float.isFinite(streamGain) || streamGain < 0 || streamGain > 1)
        throw new IllegalArgumentException("Invalid stream gain");
      if (!available() || voices.size() >= MAX_VOICES) {
        fail("Streaming PCM unavailable or voice budget exhausted");
        source.close();
        return 0;
      }
      var voice = new Voice(++sequence, generation, null, false);
      voice.stream = source;
      voice.streamGain = streamGain;
      voices.put(voice.id, voice);
      startStreamTicks();
      return voice.id;
    } catch (RuntimeException failure) {
      source.close();
      throw failure;
    }
  }

  /** Keep native streaming buffers fed during renderer readback or a slow presentation frame. */
  private void startStreamTicks() {
    if (streamTicks != null) return;
    streamTicks =
        STREAM_TICKS.scheduleAtFixedRate(
            () -> {
              synchronized (this) {
                if (!available()
                    || voices.values().stream().noneMatch(voice -> voice.stream != null)) return;
                long expected = generation;
                // Bound queued work if Minecraft's sound executor itself is stalled.
                if (streamTickQueued) return;
                streamTickQueued = true;
                access
                    .craftq3$executor()
                    .execute(
                        () -> {
                          synchronized (this) {
                            streamTickQueued = false;
                            if (available() && expected == generation)
                              access.craftq3$channels().scheduleTick();
                          }
                        });
              }
            },
            20,
            20,
            java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  private long start(Playback playback, boolean loop) {
    if (!registered) return 0;
    if (!available()) {
      fail("Minecraft sound engine is unavailable");
      return 0;
    }
    if (voices.size() >= MAX_VOICES) {
      fail("Q3 audio voice budget exhausted");
      return 0;
    }
    Voice voice = new Voice(++sequence, generation, playback, loop);
    voices.put(voice.id, voice);
    allocate(voice);
    return voice.id;
  }

  private void allocate(Voice voice) {
    voice.allocating = true;
    access
        .craftq3$channels()
        .createHandle(voice.stream == null ? Library.Pool.STATIC : Library.Pool.STREAMING)
        .whenComplete(
            (handle, error) -> {
              synchronized (this) {
                if (!live(voice)) {
                  if (handle != null) handle.execute(Channel::stop);
                  return;
                }
                if (error != null || handle == null) {
                  fail(
                      error == null
                          ? "Minecraft sound source pool exhausted"
                          : "Minecraft source allocation failed: " + error.getMessage());
                  forget(voice);
                  return;
                }
                voice.handle = handle;
                handle.execute(
                    channel -> {
                      synchronized (this) {
                        if (!live(voice)) {
                          channel.stop();
                          return;
                        }
                        try {
                          if (voice.stream == null)
                            channel.attachStaticBuffer(
                                buffer(
                                    voice.playback.sound(),
                                    voice.playback.spatial() != Spatial.LOCAL));
                          else channel.attachBufferStream(new NativePcmStream(voice));
                          channel.setLooping(voice.loop);
                          update(channel, voice);
                          channel.play();
                          if (!channel.playing())
                            throw new IllegalStateException(
                                "Minecraft channel did not enter playing state");
                          if (voice.stream != null) voice.stream.started(System.nanoTime());
                          voice.started = true;
                          started++;
                        } catch (RuntimeException failure) {
                          channel.stop();
                          fail("Q3 PCM playback failed: " + failure.getMessage());
                          forget(voice);
                        }
                      }
                    });
              }
            });
  }

  private final class NativePcmStream implements net.minecraft.client.sounds.AudioStream {
    private final Voice voice;

    NativePcmStream(Voice voice) {
      this.voice = voice;
    }

    @Override
    public AudioFormat getFormat() {
      var format = voice.stream.format();
      return new AudioFormat(
          format.sampleRate(), format.bits(), format.channels(), format.bits() == 16, false);
    }

    @Override
    public ByteBuffer read(int maximumBytes) throws java.io.IOException {
      // ChannelAccess may have queued a final refill before cancellation. Serialize its short,
      // nonblocking source read with stop/suspend and return no data for retired voices.
      synchronized (MinecraftAudioBackend.this) {
        if (!live(voice)) return null;
        try {
          var data = voice.stream.read(maximumBytes);
          if (data.length > maximumBytes || data.length % voice.stream.format().frameBytes() != 0)
            throw new java.io.IOException("Streaming PCM returned an invalid sample block");
          if (data.length == 0) {
            if (!voice.stream.exhausted())
              throw new java.io.IOException("Streaming PCM ended before EOF");
            return null;
          }
          return ByteBuffer.allocateDirect(data.length).put(data).flip();
        } catch (java.io.IOException | RuntimeException error) {
          voice.streamFailed = true;
          fail("Streaming PCM read failed: " + error.getMessage());
          if (error instanceof java.io.IOException io) throw io;
          throw new java.io.IOException("Streaming PCM source failed", error);
        }
      }
    }

    @Override
    public void close() {
      closeStream(voice);
    }
  }

  private synchronized void closeStream(Voice voice) {
    try {
      voice.stream.close();
    } catch (RuntimeException failure) {
      fail("Streaming PCM cleanup failed: " + failure.getMessage());
    }
  }

  /** Called only on the engine executor, or after that executor has been joined during teardown. */
  private SoundBuffer buffer(int sound, boolean mono) {
    SoundBuffer existing = assets.buffer(sound, mono);
    if (existing != null) return existing;
    PcmSound pcm = assets.sound(sound);
    byte[] data = mono ? pcm.mono16le() : pcm.pcm();
    return assets.buffer(
        sound,
        mono,
        data.length,
        () -> {
          ByteBuffer direct = ByteBuffer.allocateDirect(data.length);
          direct.put(data).flip();
          int bits = mono ? 16 : pcm.bits(), channels = mono ? 1 : pcm.channels();
          return new SoundBuffer(
              direct, new AudioFormat(pcm.sampleRate(), bits, channels, bits == 16, false));
        });
  }

  @Override
  public synchronized void updateEntity(int entity, Vec3 origin) {
    checkOpen();
    if (entity < 0 || entity >= entityPositions.length || origin == null)
      throw new IllegalArgumentException("Invalid sound entity position");
    entityPositions[entity] = origin;
  }

  @Override
  public synchronized void beginFrame() {
    checkOpen();
    submittedLoops.clear();
    frameOpen = true;
  }

  @Override
  public synchronized void submitLoop(Loop loop) {
    checkOpen();
    if (!frameOpen)
      throw new IllegalStateException("Audio beginFrame must precede loop submission");
    checkSound(loop.sound());
    if (!submittedLoops.containsKey(loop.entity()) && submittedLoops.size() >= MAX_VOICES)
      throw new IllegalStateException("Q3 loop submission budget exceeded");
    submittedLoops.put(loop.entity(), loop);
  }

  @Override
  public synchronized void endFrame(Listener listener) {
    checkOpen();
    this.listener = java.util.Objects.requireNonNull(listener);
    if (!registered) {
      frameOpen = false;
      return;
    }
    if (!listenerOwned) {
      previousListener = engine.getListenerTransform();
      listenerOwned = true;
    }
    hostGain = client.options.getFinalSoundSourceVolume(SoundSource.MASTER);
    if (frameOpen) {
      for (int entity : List.copyOf(loopVoices.keySet())) {
        if (!submittedLoops.containsKey(entity)) stop(loopVoices.get(entity));
      }
      for (Loop loop : submittedLoops.values()) {
        Voice current = voices.get(loopVoices.get(loop.entity()));
        Playback playback =
            new Playback(
                loop.sound(),
                loop.entity(),
                CHAN_AUTO,
                Spatial.POSITION,
                loop.origin(),
                loop.gain(),
                loop.pitch());
        if (current != null && current.playback.sound() == loop.sound())
          current.playback = playback;
        else {
          if (current != null) stop(current.id);
          long voice = start(playback, true);
          if (voice != 0) loopVoices.put(loop.entity(), voice);
        }
      }
      frameOpen = false;
    }
    if (!available()) return;
    long expected = generation;
    ListenerTransform next =
        new ListenerTransform(
            hostPosition(listener.origin()),
            hostDirection(listener.forward()),
            hostDirection(listener.up()));
    access
        .craftq3$executor()
        .execute(
            () -> {
              synchronized (this) {
                if (closed || suspended || expected != generation) return;
                access.craftq3$listener().setTransform(next);
                for (Voice voice : List.copyOf(voices.values())) {
                  if (voice.streamFailed) {
                    stop(voice.id);
                    continue;
                  }
                  if (voice.handle == null) {
                    if (voice.stream != null && !voice.allocating) {
                      if (voice.stream.exhausted()) stop(voice.id);
                      else if (voice.stream.ready()) allocate(voice);
                    }
                    continue;
                  }
                  if (voice.handle.isStopped()) {
                    forget(voice);
                    continue;
                  }
                  voice.handle.execute(
                      channel -> {
                        synchronized (this) {
                          if (live(voice)) update(channel, voice);
                        }
                      });
                }
              }
            });
    access.craftq3$channels().scheduleTick();
  }

  private void update(Channel channel, Voice voice) {
    // Q3 simulation can continue while the underlying Minecraft world is paused by its screen.
    channel.unpause();
    if (voice.stream != null) {
      channel.setRelative(true);
      channel.setPitch(1);
      channel.setVolume(Math.clamp(gain * hostGain * voice.streamGain, 0, 1));
      channel.disableAttenuation();
      channel.setSelfPosition(net.minecraft.world.phys.Vec3.ZERO);
      return;
    }
    Playback playback = voice.playback;
    boolean local =
        playback.spatial() == Spatial.LOCAL
            || (playback.spatial() == Spatial.ENTITY && playback.entity() == listener.entity());
    channel.setRelative(local);
    channel.setPitch(playback.pitch());
    channel.setVolume(Math.clamp(gain * hostGain * playback.gain(), 0, 1));
    if (local) {
      channel.disableAttenuation();
      channel.setSelfPosition(net.minecraft.world.phys.Vec3.ZERO);
    } else {
      Vec3 position =
          playback.spatial() == Spatial.POSITION
              ? playback.origin()
              : entityPositions[playback.entity()];
      channel.setSelfPosition(hostPosition(position == null ? ZERO : position));
      channel.linearAttenuation((float) (1250 / transform.quakeUnitsPerBlock()));
    }
  }

  @Override
  public synchronized void clearLoops() {
    checkOpen();
    for (long voice : List.copyOf(loopVoices.values())) stop(voice);
    submittedLoops.clear();
  }

  @Override
  public synchronized void stop(long id) {
    Voice voice = voices.get(id);
    if (voice == null) return;
    forget(voice);
    if (voice.handle != null) voice.handle.execute(Channel::stop);
  }

  private void forget(Voice voice) {
    voices.remove(voice.id);
    if (voice.stream != null) closeStream(voice);
    if (voice.loop) loopVoices.remove(voice.playback.entity(), voice.id);
  }

  @Override
  public synchronized void stopAll() {
    for (long voice : List.copyOf(voices.keySet())) stop(voice);
    submittedLoops.clear();
  }

  @Override
  public synchronized void resetAssets() {
    checkOpen();
    generation++;
    stopAll();
    frameOpen = false;
    java.util.Arrays.fill(entityPositions, null);
    var retired = assets.reset();
    // A suspended engine's sourcesCleared hook owns cleanup after its executor has joined.
    if (suspended || !access.craftq3$loaded()) return;
    // Stop callbacks precede this release tick. Shared ChannelAccess retains source ownership.
    access.craftq3$channels().scheduleTick();
    access
        .craftq3$executor()
        .execute(
            () -> {
              synchronized (this) {
                if (suspended || !access.craftq3$loaded()) return;
                assets.discard(retired, SoundBuffer::discardAlBuffer);
              }
            });
  }

  @Override
  public synchronized void volume(float gain) {
    checkOpen();
    if (!Float.isFinite(gain) || gain < 0 || gain > 1)
      throw new IllegalArgumentException("Invalid Q3 audio volume");
    this.gain = gain;
  }

  @Override
  public synchronized Diagnostics diagnostics() {
    int active = (int) voices.values().stream().filter(voice -> voice.started).count();
    return new Diagnostics(
        available(),
        assets.sounds(),
        voices.size() - active,
        active,
        loopVoices.size(),
        started,
        failures,
        lastFailure);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    stopAll();
    if (streamTicks != null) {
      streamTicks.cancel(false);
      streamTicks = null;
    }
    closed = true;
    listenerOwned = false;
    if (!registered) {
      unregister();
      return;
    }
    if (suspended || !access.craftq3$loaded()) {
      // Retired batches may still own native buffers, even when the current view is empty.
      // sourcesCleared/emergencyShutdown will finish cleanup for a suspended engine.
      if (!assets.ownsBuffers()) unregister();
      return;
    }
    // Stopped handles belong to Minecraft's shared ChannelAccess. Its tick releases and removes
    // them; invoking ChannelHandle.release ourselves would corrupt that shared ownership.
    access.craftq3$channels().scheduleTick();
    access
        .craftq3$executor()
        .execute(
            () -> {
              synchronized (this) {
                discardBuffers();
                if (previousListener != null)
                  access.craftq3$listener().setTransform(previousListener);
                unregister();
              }
            });
  }

  private boolean live(Voice voice) {
    return available() && voice.generation == generation && voices.get(voice.id) == voice;
  }

  private boolean available() {
    return registered && !closed && !suspended && access.craftq3$loaded();
  }

  private void checkOpen() {
    if (closed) throw new IllegalStateException("Q3 audio backend closed");
  }

  private void checkSound(int sound) {
    assets.sound(sound);
  }

  private void fail(String message) {
    failures++;
    lastFailure = message;
    if (failures <= 8 || (failures & (failures - 1)) == 0)
      CraftQ3Client.LOGGER.warn("CraftQ3 audio: {}", message);
  }

  private net.minecraft.world.phys.Vec3 hostPosition(Vec3 position) {
    Vec3 value = transform.toMinecraft(position);
    return new net.minecraft.world.phys.Vec3(value.x(), value.y(), value.z());
  }

  private net.minecraft.world.phys.Vec3 hostDirection(Vec3 direction) {
    return new net.minecraft.world.phys.Vec3(direction.x(), direction.z(), -direction.y());
  }

  private void discardBuffers() {
    assets.discardAll(SoundBuffer::discardAlBuffer);
  }

  private void unregister() {
    synchronized (OWNERS) {
      OWNERS.remove(engine, this);
    }
    closeCompletion.complete(null);
  }

  /** Completes once the existing sound executor has reclaimed this session's native resources. */
  public CompletableFuture<Void> closeCompletion() {
    return closeCompletion;
  }

  public synchronized int bufferedSounds() {
    return assets.buffers();
  }

  private static MinecraftAudioBackend owner(SoundEngine engine) {
    synchronized (OWNERS) {
      return OWNERS.get(engine);
    }
  }

  public static boolean ownsListener(SoundEngine engine) {
    MinecraftAudioBackend owner = owner(engine);
    return owner != null && owner.listenerOwned;
  }

  public static void suspend(SoundEngine engine, boolean teardown) {
    MinecraftAudioBackend owner = owner(engine);
    if (owner == null) return;
    synchronized (owner) {
      owner.suspended = true;
      owner.tearingDown |= teardown;
      owner.generation++;
      for (var voice : owner.voices.values()) if (voice.stream != null) owner.closeStream(voice);
      owner.voices.clear();
      owner.loopVoices.clear();
    }
  }

  /** SoundEngine has joined its executor and released every shared channel before this hook. */
  public static void sourcesCleared(SoundEngine engine) {
    MinecraftAudioBackend owner = owner(engine);
    if (owner == null) return;
    synchronized (owner) {
      owner.discardBuffers();
      if (owner.closed) owner.unregister();
    }
  }

  public static void resume(SoundEngine engine, boolean libraryLoaded) {
    MinecraftAudioBackend owner = owner(engine);
    if (owner == null) return;
    synchronized (owner) {
      if (libraryLoaded) owner.tearingDown = false;
      if (!owner.tearingDown) owner.suspended = false;
    }
  }

  public static void emergencyShutdown(SoundEngine engine) {
    suspend(engine, true);
    MinecraftAudioBackend owner = owner(engine);
    if (owner == null) return;
    synchronized (owner) {
      // Library cleanup destroys the context and its objects. Forget stale IDs without issuing
      // new native calls or joining a potentially failing sound thread during emergency shutdown.
      owner.assets.forgetBuffers();
      if (owner.closed) owner.unregister();
    }
  }
}
