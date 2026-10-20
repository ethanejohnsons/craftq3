package dev.bluevista.craftq3.engine.audio;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.QPath;
import dev.bluevista.craftq3.engine.formats.wav.PcmSound;
import dev.bluevista.craftq3.engine.formats.wav.WavFormatException;
import dev.bluevista.craftq3.engine.formats.wav.WavReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Quake III's sound semantics, independent of the host: what the cgame and ui modules' sound
 * imports do, played through an {@link AudioDevice}.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Registration</b> returns a handle per normalised name (case-insensitive, either
 *       separator). Files load on first use. A missing or unreadable file gets a handle to silence
 *       and one warning, so the game keeps running; handle 0 means "no sound".
 *   <li><b>Channels.</b> Starting a sound on an entity channel other than {@link SoundChannel#AUTO}
 *       stops the sound already playing on that entity and channel; {@code AUTO} sounds always take
 *       a free voice. One entity plays the same sound at most {@value #MAX_SAME_SOUND} times at
 *       once ({@value #MAX_SAME_SOUND_LISTENER} for the listener), and a repeat within {@value
 *       #REPEAT_WINDOW_MILLIS} ms of the last start is ignored.
 *   <li><b>Voices.</b> When all voices are busy, the oldest voice of the same entity is reused,
 *       else the oldest voice not heard from the listener's own entity; announcer voices are never
 *       taken. Otherwise the new sound is dropped.
 *   <li><b>Spatialisation</b> follows {@link Attenuation}. Sounds on the listener's entity and
 *       local sounds are unattenuated. A sound started without an origin follows its entity's
 *       position from {@link #updateEntityPosition}.
 *   <li><b>Loops</b> are submitted per frame. {@link #clearLoopingSounds}{@code (false)} drops the
 *       loops added with {@link #addLoopingSound}; only those re-added before the next {@link
 *       #update} keep playing. Loops added with {@link #addRealLoopingSound} persist until {@link
 *       #stopLoopingSound}, {@code clearLoopingSounds(true)} or {@link #stopAllSounds}. Every copy
 *       of a looping sound plays in phase with the sound system's clock. Loops out of earshot hold
 *       no voice; above {@link Limits#loops()} audible loops, the loudest play.
 *   <li><b>Music</b> streams an intro once and then its loop, at the music volume only.
 *   <li><b>Raw samples</b> (cinematic audio) queue on their own stream at the volume given.
 * </ul>
 *
 * <p>Single-threaded: every method must be called from the thread that called {@link #claimThread},
 * which is checked when assertions are enabled. Failures degrade with a logged warning instead of
 * throwing into the game module.
 */
public final class SoundSystem implements AutoCloseable {
  /** Entity numbers are 0 to {@code MAX_GENTITIES - 1}. */
  public static final int MAX_ENTITIES = 1024;

  /** Registered sounds, including the reserved handle 0 ({@code MAX_SFX}). */
  public static final int MAX_SOUNDS = 4096;

  /** A start of the same sound by the same entity within this window is ignored. */
  public static final int REPEAT_WINDOW_MILLIS = 50;

  /** Copies of one sound one entity may play at once. */
  public static final int MAX_SAME_SOUND = 4;

  /** Copies of one sound the listener's entity may play at once. */
  public static final int MAX_SAME_SOUND_LISTENER = 8;

  /** Raw samples further ahead of playback than this are dropped. */
  public static final int MAX_RAW_BACKLOG_MILLIS = 4000;

  /** The original engine's default {@code s_volume}. */
  public static final float DEFAULT_VOLUME = 0.8f;

  /** The original engine's default {@code s_musicvolume}. */
  public static final float DEFAULT_MUSIC_VOLUME = 0.25f;

  private static final Log LOG = Log.get(SoundSystem.class);

  /** Voice budgets: one-shot sounds ({@code MAX_CHANNELS}) and audible loops. */
  public record Limits(int voices, int loops) {
    public static final Limits DEFAULT = new Limits(96, 64);

    public Limits {
      if (voices < 1 || loops < 0) throw new IllegalArgumentException("Invalid voice limits");
    }
  }

  /** What is known about a registered sound. */
  public record SoundInfo(
      String name, boolean loaded, boolean placeholder, int sampleRate, int frames) {}

  private static final class Sfx {
    final String name;
    final boolean compressed;
    boolean loaded;
    boolean placeholder;
    int buffer;
    int sampleRate;
    int frames;

    Sfx(String name, boolean compressed) {
      this.name = name;
      this.compressed = compressed;
    }
  }

  private static final class Voice {
    boolean active;
    int device;
    int entity;
    int channel;
    int sound;
    boolean fixedOrigin;
    boolean local;
    float x;
    float y;
    float z;
    long startMillis;
    long sequence;
  }

  private final ThreadOwner owner = new ThreadOwner("Sound system");
  private final AudioDevice device;
  private final Limits limits;
  private final Voice[] voices;
  private final VoiceParams params = new VoiceParams();
  private final BackgroundTrack music;
  private final List<Sfx> sounds = new ArrayList<>();
  private final Map<String, Integer> handles = new HashMap<>();
  private final float[] entityOrigins = new float[MAX_ENTITIES * 3];

  private final boolean[] loopActive = new boolean[MAX_ENTITIES];
  private final boolean[] loopKill = new boolean[MAX_ENTITIES];
  private final boolean[] loopSelected = new boolean[MAX_ENTITIES];
  private final int[] loopSound = new int[MAX_ENTITIES];
  private final float[] loopOrigins = new float[MAX_ENTITIES * 3];
  private final float[] loopVelocities = new float[MAX_ENTITIES * 3];
  private final int[] loopVoice = new int[MAX_ENTITIES];
  private final int[] loopVoiceSound = new int[MAX_ENTITIES];
  private final int[] loopCandidates = new int[MAX_ENTITIES];
  private final float[] loopGains = new float[MAX_ENTITIES];
  private final float[] loopSortedGains = new float[MAX_ENTITIES];

  private AssetLookup assets;
  private Listener listener = Listener.DEFAULT;
  private float volume = DEFAULT_VOLUME;
  private float musicVolume = DEFAULT_MUSIC_VOLUME;
  private long timeMillis;
  private long sequence;
  private int rawStream;
  private int rawRate;
  private int rawChannels;
  private short[] rawScratch = new short[0];
  private boolean closed;

  public SoundSystem(AssetLookup assets, AudioDevice device, Limits limits) {
    this.assets = Objects.requireNonNull(assets, "assets");
    this.device = Objects.requireNonNull(device, "device");
    this.limits = Objects.requireNonNull(limits, "limits");
    int count = Math.max(1, Math.min(limits.voices(), device.maxVoices()));
    voices = new Voice[count];
    for (int i = 0; i < count; i++) voices[i] = new Voice();
    music = new BackgroundTrack(device);
    sounds.add(null); // handle 0 is "no sound"
  }

  /** Binds the sound system to the calling thread; later calls from other threads fail. */
  public void claimThread() {
    owner.claim();
  }

  /** Unbinds the sound system so another thread can claim it. */
  public void releaseThread() {
    owner.release();
  }

  // ---------------------------------------------------------------- registration

  /**
   * {@code S_RegisterSound}: the handle for {@code name}, registering it on first use. {@code
   * compressed} is accepted and ignored (the original only used it to save memory). Returns 0 for
   * an empty or over-long name or when the sound table is full.
   */
  public int registerSound(String name, boolean compressed) {
    check();
    if (name == null || name.isBlank()) {
      LOG.warnOnce("empty-name", "Sound registration with an empty name ignored");
      return 0;
    }
    if (name.length() >= QPath.MAX_QPATH) {
      LOG.warnOnce("long:" + name, "Sound name exceeds MAX_QPATH: " + name);
      return 0;
    }
    String key = QPath.normalize(name);
    Integer existing = handles.get(key);
    if (existing != null) return existing;
    if (sounds.size() >= MAX_SOUNDS) {
      LOG.warnOnce("table-full", "Sound table full; cannot register " + name);
      return 0;
    }
    Sfx sfx = new Sfx(key, compressed);
    if (!assets.exists(key)) {
      sfx.loaded = true;
      sfx.placeholder = true;
      LOG.warnOnce("missing:" + key, "Sound " + name + " not found; it will play as silence");
    }
    sounds.add(sfx);
    int handle = sounds.size() - 1;
    handles.put(key, handle);
    return handle;
  }

  /** Number of registered sounds. */
  public int registeredSounds() {
    return sounds.size() - 1;
  }

  /** What is known about {@code handle}, if it is registered. */
  public Optional<SoundInfo> soundInfo(int handle) {
    check();
    if (handle < 1 || handle >= sounds.size()) return Optional.empty();
    Sfx sfx = sounds.get(handle);
    return Optional.of(
        new SoundInfo(sfx.name, sfx.loaded, sfx.placeholder, sfx.sampleRate, sfx.frames));
  }

  /** Loads {@code handle} now instead of on first use; returns false for silence. */
  public boolean precache(int handle) {
    check();
    Sfx sfx = sound(handle, "precache");
    return sfx != null && load(sfx);
  }

  /**
   * Forgets every registered sound after a filesystem change (handles become invalid) and stops all
   * playback; later loads read from {@code newAssets}.
   */
  public void reset(AssetLookup newAssets) {
    check();
    Objects.requireNonNull(newAssets, "newAssets");
    stopAllSounds();
    for (int i = 1; i < sounds.size(); i++) {
      Sfx sfx = sounds.get(i);
      if (sfx.buffer != 0) device.destroyBuffer(sfx.buffer);
    }
    sounds.subList(1, sounds.size()).clear();
    handles.clear();
    Arrays.fill(entityOrigins, 0f);
    assets = newAssets;
  }

  /** Decodes and uploads a sound on first use; returns false if it is silence. */
  private boolean load(Sfx sfx) {
    if (sfx.loaded) return !sfx.placeholder;
    sfx.loaded = true;
    Optional<byte[]> bytes = assets.read(sfx.name);
    if (bytes.isEmpty()) {
      sfx.placeholder = true;
      LOG.warnOnce("missing:" + sfx.name, "Sound " + sfx.name + " not found; playing silence");
      return false;
    }
    PcmSound pcm;
    try {
      pcm = WavReader.read(bytes.get());
    } catch (WavFormatException | IllegalArgumentException bad) {
      sfx.placeholder = true;
      LOG.warnOnce("bad:" + sfx.name, "Cannot load sound " + sfx.name + ": " + bad.getMessage());
      return false;
    }
    sfx.sampleRate = pcm.sampleRate();
    sfx.frames = pcm.frames();
    if (pcm.frames() == 0) {
      sfx.placeholder = true;
      LOG.warnOnce("empty:" + sfx.name, "Sound " + sfx.name + " has no samples");
      return false;
    }
    int buffer = device.createBuffer(new PcmBuffer(pcm.sampleRate(), 1, Pcm.mono16(pcm)));
    if (buffer == 0) {
      sfx.placeholder = true;
      LOG.warnOnce("upload:" + sfx.name, "The audio device refused sound " + sfx.name);
      return false;
    }
    sfx.buffer = buffer;
    return true;
  }

  private Sfx sound(int handle, String what) {
    if (handle < 1 || handle >= sounds.size()) {
      if (handle != 0) LOG.warnOnce("handle:" + what, what + ": sound handle out of range");
      return null;
    }
    return sounds.get(handle);
  }

  // ---------------------------------------------------------------- one-shot sounds

  /**
   * {@code S_StartSound}: plays {@code sound} on {@code entity}'s {@code channel}, at {@code
   * origin} if given, else following the entity.
   */
  public void startSound(Vec3f origin, int entity, int channel, int sound) {
    check();
    if (entity < 0 || entity >= MAX_ENTITIES) {
      LOG.warnOnce("entity-range", "startSound: entity number out of range: " + entity);
      return;
    }
    Sfx sfx = sound(sound, "startSound");
    if (sfx == null) return;
    start(origin, entity, channel, sound, sfx, false);
  }

  /** {@code S_StartLocalSound}: plays {@code sound} unattenuated on the listener's entity. */
  public void startLocalSound(int sound, int channel) {
    check();
    Sfx sfx = sound(sound, "startLocalSound");
    if (sfx == null) return;
    start(null, listener.entity(), channel, sound, sfx, true);
  }

  private void start(Vec3f origin, int entity, int channel, int handle, Sfx sfx, boolean local) {
    boolean listenerEntity = local || entity == listener.entity();
    int playing = 0;
    for (Voice voice : voices) {
      if (!voice.active || voice.entity != entity || voice.sound != handle) continue;
      if (timeMillis - voice.startMillis < REPEAT_WINDOW_MILLIS) return;
      playing++;
    }
    if (playing >= (listenerEntity ? MAX_SAME_SOUND_LISTENER : MAX_SAME_SOUND)) return;
    if (channel != SoundChannel.AUTO) {
      for (Voice voice : voices)
        if (voice.active && voice.entity == entity && voice.channel == channel) release(voice);
    }
    if (!load(sfx)) return;
    Voice voice = allocate(entity);
    if (voice == null) {
      LOG.warnOnce("voices", "All sound voices are busy; dropping sounds");
      return;
    }
    voice.entity = entity;
    voice.channel = channel;
    voice.sound = handle;
    voice.local = local;
    voice.fixedOrigin = origin != null;
    if (origin != null) {
      voice.x = origin.x();
      voice.y = origin.y();
      voice.z = origin.z();
    }
    voice.startMillis = timeMillis;
    voice.sequence = ++sequence;
    spatialise(voice);
    int id = device.startVoice(sfx.buffer, false, 0, params);
    if (id == 0) {
      LOG.warnOnce("device-voices", "The audio device has no voice free; dropping sounds");
      return;
    }
    voice.device = id;
    voice.active = true;
  }

  /** A free voice, or one taken from an older sound, or null. */
  private Voice allocate(int entity) {
    Voice sameEntity = null, other = null;
    for (Voice voice : voices) {
      if (!voice.active) return voice;
      if (voice.channel == SoundChannel.ANNOUNCER) continue;
      if (voice.entity == entity) {
        if (sameEntity == null || voice.sequence < sameEntity.sequence) sameEntity = voice;
      } else if (!heardAsListener(voice)) {
        if (other == null || voice.sequence < other.sequence) other = voice;
      }
    }
    Voice victim = sameEntity != null ? sameEntity : other;
    if (victim != null) release(victim);
    return victim;
  }

  private boolean heardAsListener(Voice voice) {
    return voice.local || voice.entity == listener.entity();
  }

  private void release(Voice voice) {
    if (voice.active && voice.device != 0) device.stopVoice(voice.device);
    voice.active = false;
    voice.device = 0;
  }

  private void spatialise(Voice voice) {
    if (heardAsListener(voice)) {
      Vec3f at = listener.origin();
      params.setUnspatialised(volume, at.x(), at.y(), at.z());
    } else if (voice.fixedOrigin) {
      Attenuation.spatialise(listener, voice.x, voice.y, voice.z, volume, params);
    } else {
      int base = voice.entity * 3;
      Attenuation.spatialise(
          listener,
          entityOrigins[base],
          entityOrigins[base + 1],
          entityOrigins[base + 2],
          volume,
          params);
    }
  }

  /** {@code S_UpdateEntityPosition}: where sounds following {@code entity} are heard from. */
  public void updateEntityPosition(int entity, Vec3f origin) {
    check();
    if (entity < 0 || entity >= MAX_ENTITIES) {
      LOG.warnOnce("entity-position", "updateEntityPosition: entity out of range: " + entity);
      return;
    }
    int base = entity * 3;
    entityOrigins[base] = origin.x();
    entityOrigins[base + 1] = origin.y();
    entityOrigins[base + 2] = origin.z();
  }

  /**
   * {@code S_Respatialize}: the listener for the coming update. {@code axis} is the view axis
   * (forward, left, up). {@code inWater} is passed to the device.
   */
  public void respatialize(
      int entity, Vec3f origin, Vec3f forward, Vec3f left, Vec3f up, boolean inWater) {
    check();
    listener = new Listener(entity, origin, forward, left, up, inWater);
  }

  public Listener listener() {
    return listener;
  }

  // ---------------------------------------------------------------- loops

  /**
   * {@code S_ClearLoopingSounds}: drops the frame's {@link #addLoopingSound} loops ({@code killAll}
   * also drops real loops). Loops re-added before the next update keep their voice.
   */
  public void clearLoopingSounds(boolean killAll) {
    check();
    for (int entity = 0; entity < MAX_ENTITIES; entity++) {
      if (killAll || loopKill[entity]) {
        loopActive[entity] = false;
        loopKill[entity] = false;
      }
    }
    if (killAll) stopLoopVoices();
  }

  /** {@code S_AddLoopingSound}: a loop on {@code entity} for this frame. */
  public void addLoopingSound(int entity, Vec3f origin, Vec3f velocity, int sound) {
    check();
    addLoop(entity, origin, velocity, sound, true, "addLoopingSound");
  }

  /** {@code S_AddRealLoopingSound}: a loop on {@code entity} kept until stopped. */
  public void addRealLoopingSound(int entity, Vec3f origin, Vec3f velocity, int sound) {
    check();
    addLoop(entity, origin, velocity, sound, false, "addRealLoopingSound");
  }

  private void addLoop(
      int entity, Vec3f origin, Vec3f velocity, int sound, boolean kill, String what) {
    if (entity < 0 || entity >= MAX_ENTITIES) {
      LOG.warnOnce("loop-entity:" + what, what + ": entity out of range: " + entity);
      return;
    }
    Sfx sfx = sound(sound, what);
    if (sfx == null) return;
    if (!load(sfx)) {
      LOG.warnOnce("loop-silent:" + sfx.name, what + ": " + sfx.name + " has no samples");
      return;
    }
    loopActive[entity] = true;
    loopKill[entity] = kill;
    loopSound[entity] = sound;
    int base = entity * 3;
    loopOrigins[base] = origin.x();
    loopOrigins[base + 1] = origin.y();
    loopOrigins[base + 2] = origin.z();
    loopVelocities[base] = velocity == null ? 0 : velocity.x();
    loopVelocities[base + 1] = velocity == null ? 0 : velocity.y();
    loopVelocities[base + 2] = velocity == null ? 0 : velocity.z();
  }

  /** {@code S_StopLoopingSound}: stops {@code entity}'s loop at once. */
  public void stopLoopingSound(int entity) {
    check();
    if (entity < 0 || entity >= MAX_ENTITIES) return;
    loopActive[entity] = false;
    loopKill[entity] = false;
    if (loopVoice[entity] != 0) device.stopVoice(loopVoice[entity]);
    loopVoice[entity] = 0;
  }

  private void stopLoopVoices() {
    for (int entity = 0; entity < MAX_ENTITIES; entity++) {
      if (loopVoice[entity] != 0) device.stopVoice(loopVoice[entity]);
      loopVoice[entity] = 0;
    }
  }

  private void spatialiseLoop(int entity) {
    int base = entity * 3;
    Attenuation.spatialise(
        listener, loopOrigins[base], loopOrigins[base + 1], loopOrigins[base + 2], volume, params);
    params.setVelocity(loopVelocities[base], loopVelocities[base + 1], loopVelocities[base + 2]);
  }

  private void updateLoops() {
    int candidates = 0;
    Arrays.fill(loopSelected, false);
    for (int entity = 0; entity < MAX_ENTITIES; entity++) {
      if (!loopActive[entity]) continue;
      spatialiseLoop(entity);
      if (params.gain() <= 0f) continue; // out of earshot: no voice
      loopGains[candidates] = params.gain();
      loopCandidates[candidates++] = entity;
    }
    int budget = limits.loops();
    if (candidates <= budget) {
      for (int i = 0; i < candidates; i++) loopSelected[loopCandidates[i]] = true;
    } else if (budget > 0) {
      System.arraycopy(loopGains, 0, loopSortedGains, 0, candidates);
      Arrays.sort(loopSortedGains, 0, candidates);
      float threshold = loopSortedGains[candidates - budget];
      int kept = 0;
      for (int i = 0; i < candidates && kept < budget; i++)
        if (loopGains[i] > threshold) {
          loopSelected[loopCandidates[i]] = true;
          kept++;
        }
      for (int i = 0; i < candidates && kept < budget; i++)
        if (loopGains[i] == threshold) {
          loopSelected[loopCandidates[i]] = true;
          kept++;
        }
    }
    for (int entity = 0; entity < MAX_ENTITIES; entity++) {
      int id = loopVoice[entity];
      if (id != 0
          && (!loopSelected[entity]
              || loopVoiceSound[entity] != loopSound[entity]
              || !device.voicePlaying(id))) {
        device.stopVoice(id);
        loopVoice[entity] = 0;
      }
      if (!loopSelected[entity]) continue;
      spatialiseLoop(entity);
      if (loopVoice[entity] != 0) {
        device.updateVoice(loopVoice[entity], params);
        continue;
      }
      Sfx sfx = sounds.get(loopSound[entity]);
      // Every copy of a loop plays in phase with the sound clock, as the original mixer does.
      int phase = (int) (timeMillis * sfx.sampleRate / 1000 % sfx.frames);
      int voice = device.startVoice(sfx.buffer, true, phase, params);
      if (voice == 0) {
        LOG.warnOnce("loop-voices", "The audio device has no voice free for a looping sound");
        continue;
      }
      loopVoice[entity] = voice;
      loopVoiceSound[entity] = loopSound[entity];
    }
  }

  // ---------------------------------------------------------------- music and raw samples

  /**
   * {@code S_StartBackgroundTrack}: streams {@code intro} once, then {@code loop} (the intro again
   * if empty) until stopped. An empty intro just stops the music.
   */
  public void startBackgroundTrack(String intro, String loop) {
    check();
    if (music.start(assets, intro, loop)) music.refill(musicVolume);
  }

  /** {@code S_StopBackgroundTrack}. */
  public void stopBackgroundTrack() {
    check();
    music.stop();
  }

  /** True while background music is queued or still playing out. */
  public boolean backgroundTrackPlaying() {
    return music.playing();
  }

  /** True once the background track's intro has ended and its loop is streaming. */
  public boolean backgroundTrackLooping() {
    return music.inLoop();
  }

  /**
   * {@code S_RawSamples}: queues {@code samples} frames of cinematic audio. {@code width} is the
   * bytes per sample (1: signed 8-bit, 2: signed 16-bit little-endian), {@code channels} 1 or 2,
   * and {@code volume} the stream gain (the caller applies {@code s_volume}). A format change
   * restarts the raw stream.
   */
  public void rawSamples(
      int samples, int rate, int width, int channels, byte[] data, float volume) {
    check();
    if (samples <= 0) return;
    if (rate < 1
        || rate > Pcm.MAX_RATE
        || (width != 1 && width != 2)
        || (channels != 1 && channels != 2)
        || data == null
        || data.length < (long) samples * width * channels) {
      LOG.warnOnce("raw-format", "rawSamples: invalid sample block ignored");
      return;
    }
    int count = samples * channels;
    if (rawScratch.length < count) rawScratch = new short[count];
    Pcm.raw16(data, samples, width, channels, rawScratch);
    queueRaw(rawScratch, samples, rate, channels, volume);
  }

  /** Queues a decoded block of cinematic audio, such as a RoQ audio chunk. */
  public void rawSamples(PcmSound sound, float volume) {
    check();
    if (sound.frames() == 0) return;
    queueRaw(
        Pcm.interleaved16(sound), sound.frames(), sound.sampleRate(), sound.channels(), volume);
  }

  private void queueRaw(short[] samples, int frames, int rate, int channels, float gain) {
    if (rawStream != 0 && (rawRate != rate || rawChannels != channels)) stopRawSamples();
    if (rawStream == 0) {
      rawStream = device.openStream(rate, channels);
      if (rawStream == 0) {
        LOG.warnOnce("raw-stream", "The audio device has no stream free for raw samples");
        return;
      }
      rawRate = rate;
      rawChannels = channels;
    }
    if (device.streamQueuedFrames(rawStream) + (long) frames
        > (long) rate * MAX_RAW_BACKLOG_MILLIS / 1000) {
      LOG.warnOnce("raw-overflow", "Raw samples far ahead of playback; dropping a block");
      return;
    }
    device.setStreamGain(rawStream, clampGain(gain));
    device.queueStream(rawStream, samples, 0, frames);
  }

  /** Raw sample frames queued and not yet played. */
  public int rawSamplesQueued() {
    check();
    return rawStream == 0 ? 0 : device.streamQueuedFrames(rawStream);
  }

  /** Discards queued raw samples and closes their stream. */
  public void stopRawSamples() {
    check();
    if (rawStream != 0) device.closeStream(rawStream);
    rawStream = 0;
  }

  // ---------------------------------------------------------------- volume and frame

  /** {@code s_volume}: the gain of every sound effect and loop, clamped to [0, 1]. */
  public void setVolume(float value) {
    check();
    volume = clampGain(value);
  }

  /** {@code s_musicvolume}: the gain of the background track, clamped to [0, 1]. */
  public void setMusicVolume(float value) {
    check();
    musicVolume = clampGain(value);
  }

  public float volume() {
    return volume;
  }

  public float musicVolume() {
    return musicVolume;
  }

  private static float clampGain(float value) {
    return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
  }

  /** {@code S_StopAllSounds}: every voice, loop, the music and raw samples. */
  public void stopAllSounds() {
    check();
    for (Voice voice : voices) release(voice);
    Arrays.fill(loopActive, false);
    Arrays.fill(loopKill, false);
    stopLoopVoices();
    music.stop();
    stopRawSamples();
  }

  /**
   * {@code S_Update}: advances the sound clock by {@code frameMillis}, releases finished voices,
   * respatialises the rest, reconciles loops, refills the music stream and commits to the device.
   */
  public void update(int frameMillis) {
    check();
    timeMillis += Math.max(0, frameMillis);
    device.setListener(listener);
    for (Voice voice : voices) {
      if (!voice.active) continue;
      if (!device.voicePlaying(voice.device)) {
        release(voice);
        continue;
      }
      spatialise(voice);
      device.updateVoice(voice.device, params);
    }
    updateLoops();
    music.refill(musicVolume);
    device.commit();
  }

  /** Milliseconds the sound clock has advanced. */
  public long timeMillis() {
    return timeMillis;
  }

  /** One-shot voices playing. */
  public int activeVoices() {
    int count = 0;
    for (Voice voice : voices) if (voice.active) count++;
    return count;
  }

  /** Loops holding a device voice. */
  public int activeLoopVoices() {
    int count = 0;
    for (int voice : loopVoice) if (voice != 0) count++;
    return count;
  }

  /** The one-shot voice budget, after the device's own limit. */
  public int voiceLimit() {
    return voices.length;
  }

  /** Stops playback and frees every device buffer; the device itself stays open. */
  @Override
  public void close() {
    if (closed) return;
    check();
    reset(assets);
    closed = true;
  }

  private void check() {
    owner.check();
    if (closed) throw new IllegalStateException("Sound system is closed");
  }
}
