package dev.bluevista.craftq3.mc.fabric.client.audio;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.audio.Listener;
import dev.bluevista.craftq3.engine.audio.Pcm;
import dev.bluevista.craftq3.engine.audio.PcmBuffer;
import dev.bluevista.craftq3.engine.audio.VoiceParams;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.mc.fabric.client.mixin.ChannelAccessor;
import dev.bluevista.craftq3.mc.fabric.client.mixin.SoundEngineAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.DoubleSupplier;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * An {@link AudioDevice} on Minecraft's own OpenAL sound engine. It never opens a device or
 * context: sources come from the engine's {@link Library} channel pools, and every OpenAL call runs
 * on the engine's sound executor thread (or, at the one quiescent point inside {@code
 * SoundEngine.stopAll}, on the thread that joined it).
 *
 * <h2>Threading</h2>
 *
 * State is split by owner instead of being guarded by one lock:
 *
 * <ul>
 *   <li><b>Owner side</b> (the sound system's thread): identifier allocation, the listener, the
 *       batch being built. Each SPI call appends a pooled {@link Command} to the current {@link
 *       Batch}; {@link #commit()} hands the batch over through a bounded queue and schedules one
 *       drain task on the sound executor.
 *   <li><b>Executor side</b> (the sound executor): channels, OpenAL buffer names and stream queues.
 *       Only the drain task and the lifecycle hooks (while the executor is joined) touch it.
 *   <li><b>Feedback</b>: voice completion is published through an {@link AtomicIntegerArray} of
 *       generations, stream progress through a volatile counter per stream.
 * </ul>
 *
 * Steady state allocates nothing per update: commands and batches are recycled, and stream data is
 * copied into pooled direct buffers ({@link #CHUNK_FRAMES} frames each) whose OpenAL buffer names
 * are reused once played.
 *
 * <h2>Space</h2>
 *
 * Every Quake III voice is played source-relative with OpenAL distance attenuation disabled. The
 * device converts Quake III world positions into the listener's own frame (OpenAL: +x right, +y up,
 * -z forward) using the {@link Listener} from {@link #setListener}, so Minecraft's listener
 * (camera) is never touched and vanilla sounds keep theirs. {@link VoiceParams#gain()} already
 * contains the Quake III rolloff; it is scaled only by Minecraft's master volume. Velocities are
 * ignored: the original mixer had no Doppler shift.
 */
public final class MinecraftAudioDevice implements AudioDevice {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Voices the device plays at once: the original engine's {@code MAX_CHANNELS}. */
  public static final int MAX_VOICES = 96;

  /** Streams open at once (music and raw samples need two). */
  public static final int MAX_STREAMS = 4;

  /** Frames per pooled stream chunk; one chunk becomes one queued OpenAL buffer. */
  public static final int CHUNK_FRAMES = 4096;

  /** Most pooled chunks ever allocated: 512 x 16 KiB = 8 MiB of queued stereo audio. */
  static final int MAX_CHUNKS = 512;

  private static final int MAX_PENDING_BATCHES = 16;
  private static final int MAX_BATCH_COMMANDS = 1 << 15;
  private static final long CLOSE_TIMEOUT_MILLIS = 2000;

  private static final int OP_CREATE_BUFFER = 1;
  private static final int OP_DESTROY_BUFFER = 2;
  private static final int OP_START = 3;
  private static final int OP_UPDATE = 4;
  private static final int OP_STOP = 5;
  private static final int OP_OPEN_STREAM = 6;
  private static final int OP_QUEUE_STREAM = 7;
  private static final int OP_STREAM_GAIN = 8;
  private static final int OP_CLOSE_STREAM = 9;
  private static final int OP_CLOSE = 10;

  private final SoundEngineAccessor engine;
  private final SoundEngineExecutor executor;
  private final Library library;
  private final DoubleSupplier hostGain;
  private final Runnable onClose;
  private final Runnable drainTask = this::drain;

  // Handover between the two sides.
  private final ArrayBlockingQueue<Batch> submitted = new ArrayBlockingQueue<>(MAX_PENDING_BATCHES);
  private final ArrayBlockingQueue<Batch> freeBatches =
      new ArrayBlockingQueue<>(MAX_PENDING_BATCHES + 2);
  private final ArrayBlockingQueue<PcmChunk> freeChunks = new ArrayBlockingQueue<>(MAX_CHUNKS);
  private final AtomicBoolean drainPending = new AtomicBoolean();
  private final AtomicIntegerArray endedGeneration = new AtomicIntegerArray(MAX_VOICES + 1);
  private final CompletableFuture<Void> released = new CompletableFuture<>();
  private volatile boolean goingDown;
  private volatile int libraryEpoch;
  private volatile int nativeSources;
  private volatile int nativeBuffers;
  private volatile long drains;

  // Owner side.
  private Batch current = new Batch();
  private Listener listener = Listener.DEFAULT;
  private boolean[] bufferLive = new boolean[64];
  private int[] freeBufferIds = new int[64];
  private int freeBufferCount;
  private int nextBufferId = 1;
  private final int[] voiceGeneration = new int[MAX_VOICES + 1];
  private final boolean[] voiceLive = new boolean[MAX_VOICES + 1];
  private final StreamState[] streams = new StreamState[MAX_STREAMS + 1];
  private final long[] streamQueued = new long[MAX_STREAMS + 1];
  private int allocatedChunks;
  private boolean closed;

  // Executor side.
  private final NativeVoice[] nativeVoices = new NativeVoice[MAX_VOICES + 1];
  private final NativeStream[] nativeStreams = new NativeStream[MAX_STREAMS + 1];
  private PcmBuffer[] nativePcm = new PcmBuffer[64];
  private int[] nativeNames = new int[64];
  private int nativeEpoch;
  private boolean quiescing;
  private int sourceCount;
  private int bufferCount;
  private boolean nativeClosed;

  MinecraftAudioDevice(SoundEngine soundEngine, DoubleSupplier hostGain, Runnable onClose) {
    this.engine = (SoundEngineAccessor) Objects.requireNonNull(soundEngine, "soundEngine");
    this.executor = engine.craftq3$executor();
    this.library = engine.craftq3$library();
    this.hostGain = Objects.requireNonNull(hostGain, "hostGain");
    this.onClose = Objects.requireNonNull(onClose, "onClose");
    for (int i = 0; i <= MAX_VOICES; i++) nativeVoices[i] = new NativeVoice();
    for (int i = 0; i <= MAX_STREAMS; i++) nativeStreams[i] = new NativeStream();
  }

  // ------------------------------------------------------------------ owner side: SPI

  @Override
  public int maxVoices() {
    return MAX_VOICES;
  }

  @Override
  public int createBuffer(PcmBuffer pcm) {
    Objects.requireNonNull(pcm, "pcm");
    if (closed) return 0;
    int id;
    if (freeBufferCount > 0) id = freeBufferIds[--freeBufferCount];
    else {
      id = nextBufferId++;
      if (id >= bufferLive.length) bufferLive = Arrays.copyOf(bufferLive, id * 2);
    }
    bufferLive[id] = true;
    Command c = add(OP_CREATE_BUFFER);
    c.buffer = id;
    c.pcm = pcm;
    return id;
  }

  @Override
  public void destroyBuffer(int buffer) {
    if (!liveBuffer(buffer)) return;
    bufferLive[buffer] = false;
    if (freeBufferCount == freeBufferIds.length)
      freeBufferIds = Arrays.copyOf(freeBufferIds, freeBufferCount * 2);
    freeBufferIds[freeBufferCount++] = buffer;
    add(OP_DESTROY_BUFFER).buffer = buffer;
  }

  @Override
  public int startVoice(int buffer, boolean looping, int startFrame, VoiceParams params) {
    if (closed || !usable() || !liveBuffer(buffer)) return 0;
    int slot = 0;
    for (int i = 1; i <= MAX_VOICES; i++)
      if (!voiceLive[i]) {
        slot = i;
        break;
      }
    if (slot == 0) return 0;
    int generation = voiceGeneration[slot] + 1;
    if (generation == 0) generation = 1;
    voiceGeneration[slot] = generation;
    voiceLive[slot] = true;
    Command c = add(OP_START);
    c.slot = slot;
    c.generation = generation;
    c.buffer = buffer;
    c.looping = looping;
    c.startFrame = Math.max(0, startFrame);
    place(c, params);
    return slot;
  }

  @Override
  public void updateVoice(int voice, VoiceParams params) {
    if (!liveVoice(voice)) return;
    if (current.size >= MAX_BATCH_COMMANDS) return; // executor stalled: drop updates, keep state
    Command c = add(OP_UPDATE);
    c.slot = voice;
    c.generation = voiceGeneration[voice];
    place(c, params);
  }

  @Override
  public boolean voicePlaying(int voice) {
    return liveVoice(voice) && endedGeneration.get(voice) != voiceGeneration[voice];
  }

  @Override
  public void stopVoice(int voice) {
    if (!liveVoice(voice)) return;
    voiceLive[voice] = false;
    Command c = add(OP_STOP);
    c.slot = voice;
    c.generation = voiceGeneration[voice];
  }

  @Override
  public int openStream(int sampleRate, int channels) {
    if (closed || !usable()) return 0;
    if (sampleRate < 1 || sampleRate > Pcm.MAX_RATE || channels < 1 || channels > 2) return 0;
    for (int slot = 1; slot <= MAX_STREAMS; slot++) {
      if (streams[slot] != null) continue;
      StreamState state = new StreamState(sampleRate, channels);
      streams[slot] = state;
      streamQueued[slot] = 0;
      Command c = add(OP_OPEN_STREAM);
      c.slot = slot;
      c.stream = state;
      return slot;
    }
    return 0;
  }

  @Override
  public void queueStream(int stream, short[] samples, int offset, int frames) {
    StreamState state = liveStream(stream);
    if (state == null || frames <= 0) return;
    int channels = state.channels;
    Objects.checkFromIndexSize(offset, frames * channels, samples.length);
    int position = offset;
    int remaining = frames;
    while (remaining > 0) {
      PcmChunk chunk = takeChunk();
      if (chunk == null) {
        if (LOGGER.isWarnEnabled())
          LOGGER.warn("CraftQ3 audio: stream chunk pool exhausted; dropping {} frames", remaining);
        return;
      }
      int count = Math.min(CHUNK_FRAMES, remaining);
      chunk.data.clear();
      chunk.data.put(samples, position, count * channels).flip();
      chunk.frames = count;
      Command c = add(OP_QUEUE_STREAM);
      c.slot = stream;
      c.stream = state;
      c.chunk = chunk;
      streamQueued[stream] += count;
      position += count * channels;
      remaining -= count;
    }
  }

  @Override
  public int streamQueuedFrames(int stream) {
    StreamState state = liveStream(stream);
    if (state == null) return 0;
    long queued = streamQueued[stream] - state.played;
    return Math.clamp(queued, 0, Integer.MAX_VALUE);
  }

  @Override
  public void setStreamGain(int stream, float gain) {
    StreamState state = liveStream(stream);
    if (state == null) return;
    Command c = add(OP_STREAM_GAIN);
    c.slot = stream;
    c.stream = state;
    c.gain = clampGain(gain * (float) hostGain.getAsDouble());
  }

  @Override
  public void closeStream(int stream) {
    StreamState state = liveStream(stream);
    if (state == null) return;
    streams[stream] = null;
    Command c = add(OP_CLOSE_STREAM);
    c.slot = stream;
    c.stream = state;
  }

  @Override
  public void setListener(Listener listener) {
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  @Override
  public void commit() {
    if (closed) return;
    if (current.size > 0 && submitted.offer(current)) {
      Batch next = freeBatches.poll();
      current = next != null ? next : new Batch();
    }
    scheduleDrain();
  }

  /**
   * Stops everything, frees every source and buffer, and waits (up to two seconds) until the sound
   * executor has done so. Idempotent. Called by the device's host, never by the sound system.
   */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    Arrays.fill(voiceLive, false);
    Arrays.fill(bufferLive, false);
    Arrays.fill(streams, null);
    add(OP_CLOSE);
    Batch last = current;
    current = new Batch(); // never touched again: every SPI call is a no-op once closed
    boolean handedOver = handOver(last);
    scheduleDrain();
    if (!handedOver) {
      LOGGER.warn("CraftQ3 audio: the sound executor is stalled; Quake III audio was not released");
    } else if (!executor.isSameThread()) {
      try {
        released.get(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        LOGGER.warn("CraftQ3 audio: the sound executor did not release Quake III audio in time");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        LOGGER.warn("CraftQ3 audio: releasing Quake III audio failed", e.getCause());
      }
    }
    onClose.run();
  }

  /** Completes once the sound executor has released this device's sources and buffers. */
  public CompletableFuture<Void> released() {
    return released;
  }

  /** A snapshot of the device's native footprint, for diagnostics and tests. */
  public record Diagnostics(
      int sources, int buffers, int allocatedChunks, int pooledChunks, long drains) {}

  public Diagnostics diagnostics() {
    return new Diagnostics(
        nativeSources, nativeBuffers, allocatedChunks, freeChunks.size(), drains);
  }

  private boolean usable() {
    return !goingDown && engine.craftq3$loaded();
  }

  private boolean liveBuffer(int buffer) {
    return buffer > 0 && buffer < bufferLive.length && bufferLive[buffer];
  }

  private boolean liveVoice(int voice) {
    return voice > 0 && voice <= MAX_VOICES && voiceLive[voice];
  }

  private StreamState liveStream(int stream) {
    return stream > 0 && stream <= MAX_STREAMS ? streams[stream] : null;
  }

  private Command add(int op) {
    Command c = current.add();
    c.op = op;
    return c;
  }

  private PcmChunk takeChunk() {
    PcmChunk chunk = freeChunks.poll();
    if (chunk == null && allocatedChunks < MAX_CHUNKS) {
      allocatedChunks++;
      chunk = new PcmChunk();
    }
    return chunk;
  }

  /** Converts {@code params} into the listener's frame and stores them in {@code c}. */
  private void place(Command c, VoiceParams params) {
    c.gain = clampGain(params.gain() * (float) hostGain.getAsDouble());
    c.pitch = params.pitch() > 0 ? params.pitch() : 1f;
    if (params.relative()) {
      c.x = c.y = c.z = 0f;
      return;
    }
    Vec3f origin = listener.origin();
    float dx = params.x() - origin.x();
    float dy = params.y() - origin.y();
    float dz = params.z() - origin.z();
    Vec3f forward = listener.forward();
    Vec3f left = listener.left();
    Vec3f up = listener.up();
    c.x = -(dx * left.x() + dy * left.y() + dz * left.z());
    c.y = dx * up.x() + dy * up.y() + dz * up.z();
    c.z = -(dx * forward.x() + dy * forward.y() + dz * forward.z());
  }

  private static float clampGain(float gain) {
    return Float.isFinite(gain) ? Math.clamp(gain, 0f, 1f) : 0f;
  }

  private void scheduleDrain() {
    if (drainPending.compareAndSet(false, true)) executor.execute(drainTask);
  }

  /**
   * Queues {@code batch} even when the queue is full, letting the executor catch up for at most
   * {@link #CLOSE_TIMEOUT_MILLIS}.
   */
  private boolean handOver(Batch batch) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_TIMEOUT_MILLIS);
    while (!submitted.offer(batch)) {
      if (executor.isSameThread()) drain();
      else {
        scheduleDrain();
        if (System.nanoTime() > deadline) return false;
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return true;
  }

  // ------------------------------------------------------------------ lifecycle (render thread)

  void beforeDestroy() {
    goingDown = true;
  }

  /**
   * The sound executor is joined: apply what is pending without OpenAL, then release every source
   * (and, when the context is going away, every buffer) directly. OpenAL is still current.
   */
  void executorStopped() {
    drainPending.set(false);
    boolean alive = engine.craftq3$loaded();
    quiescing = true;
    try {
      applyPending(alive);
    } finally {
      quiescing = false;
    }
    releaseNatives(alive);
    if (goingDown) forgetBufferNames(alive);
  }

  void libraryLoaded() {
    goingDown = false;
    libraryEpoch++;
  }

  void emergencyShutdown() {
    goingDown = true;
    libraryEpoch++;
  }

  // ------------------------------------------------------------------ executor side

  private void drain() {
    drainPending.set(false);
    if (nativeEpoch != libraryEpoch) {
      // The library was rebuilt; anything still referenced belongs to the old context.
      nativeEpoch = libraryEpoch;
      releaseNatives(false);
      forgetBufferNames(false);
    }
    boolean usable = usable();
    applyPending(usable);
    if (usable && !nativeClosed) service();
    drains++;
  }

  private void applyPending(boolean usable) {
    Batch batch;
    while ((batch = submitted.poll()) != null) {
      for (int i = 0; i < batch.size; i++) {
        Command c = batch.commands[i];
        try {
          apply(c, usable && !nativeClosed);
        } catch (RuntimeException e) {
          LOGGER.warn("CraftQ3 audio: command {} failed", c.op, e);
        } finally {
          c.clear();
        }
      }
      batch.size = 0;
      freeBatches.offer(batch);
    }
    publishCounts();
  }

  private void apply(Command c, boolean usable) {
    switch (c.op) {
      case OP_CREATE_BUFFER -> {
        if (c.buffer >= nativePcm.length) {
          int size = Math.max(c.buffer + 1, nativePcm.length * 2);
          nativePcm = Arrays.copyOf(nativePcm, size);
          nativeNames = Arrays.copyOf(nativeNames, size);
        }
        nativePcm[c.buffer] = c.pcm;
        nativeNames[c.buffer] = 0;
        if (usable) upload(c.buffer);
      }
      case OP_DESTROY_BUFFER -> {
        if (c.buffer < nativePcm.length) {
          nativePcm[c.buffer] = null;
          deleteName(c.buffer, usable);
        }
      }
      case OP_START -> start(c, usable);
      case OP_UPDATE -> {
        NativeVoice v = nativeVoices[c.slot];
        if (usable && v.generation == c.generation && v.channel != null) applyParams(v, c);
      }
      case OP_STOP -> {
        NativeVoice v = nativeVoices[c.slot];
        if (v.generation == c.generation) releaseVoice(c.slot, v, usable);
      }
      case OP_OPEN_STREAM -> {
        NativeStream s = nativeStreams[c.slot];
        releaseStream(s, usable);
        s.open(c.stream);
      }
      case OP_QUEUE_STREAM -> {
        NativeStream s = nativeStreams[c.slot];
        PcmChunk chunk = c.chunk;
        if (s.state == c.stream) queue(s, chunk, usable);
        freeChunks.offer(chunk);
      }
      case OP_STREAM_GAIN -> {
        NativeStream s = nativeStreams[c.slot];
        if (s.state == c.stream) {
          s.gain = c.gain;
          if (usable && s.channel != null) s.channel.setVolume(s.gain);
        }
      }
      case OP_CLOSE_STREAM -> {
        NativeStream s = nativeStreams[c.slot];
        if (s.state == c.stream) {
          releaseStream(s, usable);
          s.state = null;
        }
      }
      case OP_CLOSE -> {
        for (int slot = 1; slot <= MAX_VOICES; slot++)
          releaseVoice(slot, nativeVoices[slot], usable);
        for (int slot = 1; slot <= MAX_STREAMS; slot++) {
          releaseStream(nativeStreams[slot], usable);
          nativeStreams[slot].state = null;
        }
        for (int id = 1; id < nativePcm.length; id++) {
          nativePcm[id] = null;
          deleteName(id, usable);
        }
        nativeClosed = true;
        publishCounts();
        released.complete(null);
      }
      default -> throw new IllegalStateException("Unknown audio command " + c.op);
    }
  }

  private void start(Command c, boolean usable) {
    NativeVoice v = nativeVoices[c.slot];
    releaseVoice(c.slot, v, usable);
    v.generation = c.generation;
    // Inside stopAll every voice ends anyway, so none is started there.
    boolean play = usable && !quiescing && c.buffer < nativePcm.length;
    int name = play ? ensureUploaded(c.buffer) : 0;
    Channel channel = name != 0 ? library.acquireChannel(Library.Pool.STATIC) : null;
    if (channel == null) {
      endedGeneration.set(c.slot, c.generation);
      return;
    }
    int source = ((ChannelAccessor) channel).craftq3$source();
    v.channel = channel;
    v.source = source;
    sourceCount++;
    AL10.alSourcei(source, AL10.AL_BUFFER, name);
    channel.setLooping(c.looping);
    channel.setRelative(true);
    channel.disableAttenuation();
    AL10.alSource3f(source, AL10.AL_VELOCITY, 0f, 0f, 0f);
    int frames = nativePcm[c.buffer].frames();
    if (c.startFrame > 0 && frames > 0)
      AL10.alSourcei(source, AL11.AL_SAMPLE_OFFSET, c.startFrame % frames);
    applyParams(v, c);
    channel.play();
  }

  private static void applyParams(NativeVoice v, Command c) {
    v.channel.setVolume(c.gain);
    v.channel.setPitch(c.pitch);
    AL10.alSource3f(v.source, AL10.AL_POSITION, c.x, c.y, c.z);
  }

  /** Per drain: finished voices end, played stream buffers are recycled and counted. */
  private void service() {
    for (int slot = 1; slot <= MAX_VOICES; slot++) {
      NativeVoice v = nativeVoices[slot];
      if (v.channel == null) continue;
      int state = AL10.alGetSourcei(v.source, AL10.AL_SOURCE_STATE);
      if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) releaseVoice(slot, v, true);
    }
    for (int slot = 1; slot <= MAX_STREAMS; slot++) {
      NativeStream s = nativeStreams[slot];
      if (s.state == null || s.channel == null) continue;
      reclaimProcessed(s);
      boolean playing = AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
      long offset = playing ? AL10.alGetSourcei(s.source, AL11.AL_SAMPLE_OFFSET) : 0;
      s.state.played = s.playedFrames + Math.min(offset, s.queuedFrames());
    }
    publishCounts();
  }

  private void releaseVoice(int slot, NativeVoice v, boolean usable) {
    if (v.channel != null) {
      if (usable) {
        v.channel.stop();
        AL10.alSourcei(v.source, AL10.AL_BUFFER, 0);
        releaseChannel(v.channel);
      }
      v.channel = null;
      v.source = 0;
      sourceCount--;
    }
    if (v.generation != 0) endedGeneration.set(slot, v.generation);
  }

  private void queue(NativeStream s, PcmChunk chunk, boolean usable) {
    if (!usable || quiescing || !ensureStreamChannel(s)) {
      discard(s, chunk.frames);
      return;
    }
    reclaimProcessed(s);
    int name;
    if (s.freeCount > 0) name = s.freeNames[--s.freeCount];
    else {
      name = genName();
      if (name == 0) {
        discard(s, chunk.frames);
        return;
      }
      s.adopt(name);
    }
    int format = s.state.channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
    AL10.alBufferData(name, format, chunk.data, s.state.rate);
    AL10.alSourceQueueBuffers(s.source, name);
    if (AL10.alGetError() != AL10.AL_NO_ERROR) {
      LOGGER.warn("CraftQ3 audio: queueing stream data failed");
      s.freeNames[s.freeCount++] = name;
      discard(s, chunk.frames);
      return;
    }
    s.pushQueued(chunk.frames);
    // Starts the stream, or restarts it after it ran dry.
    if (AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) s.channel.play();
  }

  /** Without sound the data is consumed at once, as Quake III does without a device. */
  private static void discard(NativeStream s, int frames) {
    s.playedFrames += frames;
    s.state.played = s.playedFrames;
  }

  private boolean ensureStreamChannel(NativeStream s) {
    if (s.channel != null) return true;
    Channel channel = library.acquireChannel(Library.Pool.STREAMING);
    if (channel == null) channel = library.acquireChannel(Library.Pool.STATIC);
    if (channel == null) return false;
    s.channel = channel;
    s.source = ((ChannelAccessor) channel).craftq3$source();
    sourceCount++;
    channel.setRelative(true);
    channel.disableAttenuation();
    channel.setLooping(false);
    channel.setPitch(1f);
    channel.setVolume(s.gain);
    AL10.alSource3f(s.source, AL10.AL_POSITION, 0f, 0f, 0f);
    return true;
  }

  private static void reclaimProcessed(NativeStream s) {
    int processed = AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_PROCESSED);
    for (int i = 0; i < processed && s.queuedCount > 0; i++) {
      int name = AL10.alSourceUnqueueBuffers(s.source);
      s.freeNames[s.freeCount++] = name;
      s.playedFrames += s.popQueued();
    }
  }

  /** Stops a stream and frees its source and buffer names; queued data counts as played. */
  private void releaseStream(NativeStream s, boolean usable) {
    if (s.channel != null) {
      if (usable) {
        s.channel.stop();
        AL10.alSourcei(s.source, AL10.AL_BUFFER, 0);
        releaseChannel(s.channel);
      }
      s.channel = null;
      s.source = 0;
      sourceCount--;
    }
    while (s.queuedCount > 0) s.playedFrames += s.popQueued();
    if (usable) for (int i = 0; i < s.ownedCount; i++) AL10.alDeleteBuffers(s.owned[i]);
    bufferCount -= s.ownedCount;
    s.ownedCount = 0;
    s.freeCount = 0;
    if (s.state != null) s.state.played = s.playedFrames;
  }

  private void releaseChannel(Channel channel) {
    try {
      library.releaseChannel(channel);
    } catch (IllegalStateException e) {
      // The pool was rebuilt underneath us; the old context already destroyed the source.
      LOGGER.debug("CraftQ3 audio: channel already released by the library", e);
    }
  }

  /** Releases every source; with {@code openAl} false only forgets them (the context is gone). */
  private void releaseNatives(boolean openAl) {
    for (int slot = 1; slot <= MAX_VOICES; slot++) releaseVoice(slot, nativeVoices[slot], openAl);
    for (int slot = 1; slot <= MAX_STREAMS; slot++) releaseStream(nativeStreams[slot], openAl);
    publishCounts();
  }

  /** Drops every static buffer name; the PCM is kept and uploaded again on next use. */
  private void forgetBufferNames(boolean openAl) {
    for (int id = 1; id < nativeNames.length; id++) deleteName(id, openAl);
    publishCounts();
  }

  private int ensureUploaded(int buffer) {
    if (nativeNames[buffer] == 0 && nativePcm[buffer] != null) upload(buffer);
    return nativeNames[buffer];
  }

  private void upload(int buffer) {
    PcmBuffer pcm = nativePcm[buffer];
    short[] samples = pcm.samples();
    if (samples.length == 0) return;
    int name = genName();
    if (name == 0) return;
    ShortBuffer staging = MemoryUtil.memAllocShort(samples.length);
    try {
      staging.put(samples).flip();
      int format = pcm.channels() == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
      AL10.alBufferData(name, format, staging, pcm.sampleRate());
    } finally {
      MemoryUtil.memFree(staging);
    }
    if (AL10.alGetError() != AL10.AL_NO_ERROR) {
      AL10.alDeleteBuffers(name);
      bufferCount--;
      LOGGER.warn("CraftQ3 audio: uploading a {}-frame buffer failed", pcm.frames());
      return;
    }
    nativeNames[buffer] = name;
  }

  private void deleteName(int buffer, boolean openAl) {
    int name = nativeNames[buffer];
    if (name == 0) return;
    if (openAl) AL10.alDeleteBuffers(name);
    nativeNames[buffer] = 0;
    bufferCount--;
  }

  private int genName() {
    AL10.alGetError();
    int name = AL10.alGenBuffers();
    if (AL10.alGetError() != AL10.AL_NO_ERROR || name == 0) {
      LOGGER.warn("CraftQ3 audio: OpenAL could not allocate a buffer");
      return 0;
    }
    bufferCount++;
    return name;
  }

  private void publishCounts() {
    nativeSources = sourceCount;
    nativeBuffers = bufferCount;
  }

  // ------------------------------------------------------------------ records

  /** One SPI call, recycled through its {@link Batch}. */
  private static final class Command {
    int op;
    int slot;
    int generation;
    int buffer;
    int startFrame;
    boolean looping;
    float gain;
    float pitch;
    float x;
    float y;
    float z;
    PcmBuffer pcm;
    StreamState stream;
    PcmChunk chunk;

    void clear() {
      pcm = null;
      stream = null;
      chunk = null;
    }
  }

  /** The commands between two commits; reused once the executor has applied them. */
  private static final class Batch {
    Command[] commands = new Command[64];
    int size;

    Command add() {
      if (size == commands.length) commands = Arrays.copyOf(commands, size * 2);
      Command c = commands[size];
      if (c == null) commands[size] = c = new Command();
      size++;
      return c;
    }
  }

  /** A pooled direct buffer holding up to {@link #CHUNK_FRAMES} stereo frames. */
  private static final class PcmChunk {
    final ShortBuffer data =
        ByteBuffer.allocateDirect(CHUNK_FRAMES * 2 * Short.BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer();
    int frames;
  }

  /** What both sides know of an open stream; a new instance per {@link #openStream}. */
  private static final class StreamState {
    final int rate;
    final int channels;

    /** Frames the executor has played or discarded; only the executor writes it. */
    volatile long played;

    StreamState(int rate, int channels) {
      this.rate = rate;
      this.channels = channels;
    }
  }

  private static final class NativeVoice {
    int generation;
    Channel channel;
    int source;
  }

  private static final class NativeStream {
    StreamState state;
    Channel channel;
    int source;
    float gain = 1f;
    long playedFrames;
    int[] owned = new int[8];
    int ownedCount;
    int[] freeNames = new int[8];
    int freeCount;
    int[] queued = new int[8];
    int queuedHead;
    int queuedCount;

    void open(StreamState newState) {
      state = newState;
      gain = 1f;
      playedFrames = 0;
      queuedHead = 0;
      queuedCount = 0;
    }

    void adopt(int name) {
      if (ownedCount == owned.length) {
        owned = Arrays.copyOf(owned, ownedCount * 2);
        freeNames = Arrays.copyOf(freeNames, owned.length);
      }
      owned[ownedCount++] = name;
    }

    void pushQueued(int frames) {
      if (queuedCount == queued.length) {
        int[] grown = new int[queued.length * 2];
        for (int i = 0; i < queuedCount; i++) grown[i] = queued[(queuedHead + i) % queued.length];
        queued = grown;
        queuedHead = 0;
      }
      queued[(queuedHead + queuedCount++) % queued.length] = frames;
    }

    int popQueued() {
      int frames = queued[queuedHead];
      queuedHead = (queuedHead + 1) % queued.length;
      queuedCount--;
      return frames;
    }

    long queuedFrames() {
      long total = 0;
      for (int i = 0; i < queuedCount; i++) total += queued[(queuedHead + i) % queued.length];
      return total;
    }
  }
}
