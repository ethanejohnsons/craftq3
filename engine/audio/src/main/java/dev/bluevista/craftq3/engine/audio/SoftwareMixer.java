package dev.bluevista.craftq3.engine.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A pure-Java {@link AudioDevice} that mixes every voice and stream into signed 16-bit interleaved
 * stereo at a fixed output rate, for tests and headless capture. Voices use the 2D view of {@link
 * VoiceParams} (per-ear gains), which reproduces the original mixer's volumes. Sources at other
 * rates are resampled with linear interpolation while mixing.
 *
 * <p>Time advances only through {@link #mix}. Stream queues are stored in fixed-size chunks that
 * are recycled through a pool, so steady-state streaming allocates nothing.
 *
 * <p>Single-threaded, like the sound system that drives it: call {@link #mix} from the same thread.
 */
public final class SoftwareMixer implements AudioDevice {
  /** The original engine's default output rate. */
  public static final int DEFAULT_OUTPUT_RATE = 22050;

  /** The most voices a mixer can be built with. */
  public static final int MAX_VOICES = 2046;

  /** Streams open at once (music and cinematic audio need two). */
  public static final int MAX_STREAMS = 8;

  /** Frames per pooled stream chunk. */
  public static final int CHUNK_FRAMES = 2048;

  private static final int SLOT_BITS = 11;
  private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
  private static final int GENERATION_MASK = (1 << (31 - SLOT_BITS)) - 1;

  private final int outputRate;
  private final Voice[] voices;
  private final Stream[] streams = new Stream[MAX_STREAMS];
  private final List<PcmBuffer> buffers = new ArrayList<>();
  private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();
  private final ArrayDeque<Chunk> chunkPool = new ArrayDeque<>();
  private float[] accumulateLeft = new float[0];
  private float[] accumulateRight = new float[0];
  private Listener listener = Listener.DEFAULT;
  private int chunksAllocated;
  private long framesMixed;
  private boolean closed;

  private static final class Voice {
    boolean used;
    boolean playing;
    boolean looping;
    int generation;
    PcmBuffer buffer;
    double position;
    float left;
    float right;
    float pitch;
  }

  private static final class Chunk {
    final short[] data = new short[CHUNK_FRAMES * 2];
    int frames;
  }

  private static final class Stream {
    boolean used;
    int generation;
    int rate;
    int channels;
    float gain = 1;
    Chunk[] ring = new Chunk[8];
    int head;
    int count;
    long queued;
    long played;
    double position;

    Chunk at(int index) {
      return ring[(head + index) % ring.length];
    }

    void push(Chunk chunk) {
      if (count == ring.length) {
        Chunk[] grown = new Chunk[ring.length * 2];
        for (int i = 0; i < count; i++) grown[i] = at(i);
        ring = grown;
        head = 0;
      }
      ring[(head + count) % ring.length] = chunk;
      count++;
    }

    Chunk pop() {
      Chunk chunk = ring[head];
      ring[head] = null;
      head = (head + 1) % ring.length;
      count--;
      return chunk;
    }
  }

  public SoftwareMixer(int outputRate, int maxVoices) {
    if (outputRate < 1000 || outputRate > Pcm.MAX_RATE)
      throw new IllegalArgumentException("Unsupported output rate " + outputRate);
    if (maxVoices < 1 || maxVoices > MAX_VOICES)
      throw new IllegalArgumentException("Voice count must be 1.." + MAX_VOICES);
    this.outputRate = outputRate;
    voices = new Voice[maxVoices];
    for (int i = 0; i < maxVoices; i++) voices[i] = new Voice();
    for (int i = 0; i < MAX_STREAMS; i++) streams[i] = new Stream();
  }

  public int outputRate() {
    return outputRate;
  }

  /** The listener most recently set. The mixer itself only uses per-voice gains. */
  public Listener listener() {
    return listener;
  }

  /** Output frames mixed since construction. */
  public long framesMixed() {
    return framesMixed;
  }

  /** Stream chunks ever allocated; stays constant once streaming reaches a steady state. */
  public int chunksAllocated() {
    return chunksAllocated;
  }

  /** Voices currently playing. */
  public int playingVoices() {
    int count = 0;
    for (Voice voice : voices) if (voice.used && voice.playing) count++;
    return count;
  }

  /** Source frames of {@code stream} played so far. */
  public long streamPlayedFrames(int stream) {
    Stream s = stream(stream);
    return s == null ? 0 : s.played + (long) s.position;
  }

  /**
   * Mixes the next {@code frames} output frames into {@code out} as interleaved left/right samples,
   * clipped to 16 bits, and advances every voice and stream.
   */
  public void mix(short[] out, int frames) {
    Objects.requireNonNull(out, "out");
    if (frames < 0 || out.length < frames * 2L)
      throw new IllegalArgumentException("Output buffer holds fewer than " + frames + " frames");
    if (accumulateLeft.length < frames) {
      accumulateLeft = new float[frames];
      accumulateRight = new float[frames];
    } else {
      Arrays.fill(accumulateLeft, 0, frames, 0f);
      Arrays.fill(accumulateRight, 0, frames, 0f);
    }
    for (Voice voice : voices) if (voice.used && voice.playing) mixVoice(voice, frames);
    for (Stream stream : streams) if (stream.used) mixStream(stream, frames);
    for (int i = 0; i < frames; i++) {
      out[i * 2] = clip(accumulateLeft[i]);
      out[i * 2 + 1] = clip(accumulateRight[i]);
    }
    framesMixed += frames;
  }

  private void mixVoice(Voice voice, int frames) {
    PcmBuffer buffer = voice.buffer;
    short[] data = buffer.samples();
    int channels = buffer.channels();
    int length = buffer.frames();
    if (length == 0) {
      voice.playing = false;
      return;
    }
    double step = (double) buffer.sampleRate() * voice.pitch / outputRate;
    double position = voice.position;
    float left = voice.left, right = voice.right;
    for (int i = 0; i < frames; i++) {
      if (position >= length) {
        if (!voice.looping) {
          voice.playing = false;
          break;
        }
        position %= length;
      }
      int first = (int) position;
      float fraction = (float) (position - first);
      int second = first + 1;
      if (second >= length) second = voice.looping ? 0 : first;
      if (channels == 1) {
        float a = data[first], sample = a + (data[second] - a) * fraction;
        accumulateLeft[i] += sample * left;
        accumulateRight[i] += sample * right;
      } else {
        float a = data[first * 2], b = data[first * 2 + 1];
        accumulateLeft[i] += (a + (data[second * 2] - a) * fraction) * left;
        accumulateRight[i] += (b + (data[second * 2 + 1] - b) * fraction) * right;
      }
      position += step;
    }
    if (voice.playing && !voice.looping && position >= length) voice.playing = false;
    voice.position = position;
  }

  private void mixStream(Stream stream, int frames) {
    double step = (double) stream.rate / outputRate;
    double position = stream.position;
    float gain = stream.gain;
    int channels = stream.channels;
    for (int i = 0; i < frames; i++) {
      while (stream.count > 0 && position >= stream.at(0).frames) {
        Chunk done = stream.pop();
        position -= done.frames;
        stream.played += done.frames;
        stream.queued -= done.frames;
        chunkPool.push(done);
      }
      if (stream.count == 0) break; // underrun: silent until more frames are queued
      Chunk chunk = stream.at(0);
      int first = (int) position;
      float fraction = (float) (position - first);
      Chunk nextChunk = chunk;
      int next = first + 1;
      if (next >= chunk.frames) {
        nextChunk = stream.count > 1 ? stream.at(1) : null;
        next = 0;
      }
      for (int c = 0; c < channels; c++) {
        float a = chunk.data[first * channels + c];
        float b = nextChunk == null ? a : nextChunk.data[next * channels + c];
        float sample = (a + (b - a) * fraction) * gain;
        if (channels == 1) {
          accumulateLeft[i] += sample;
          accumulateRight[i] += sample;
        } else if (c == 0) accumulateLeft[i] += sample;
        else accumulateRight[i] += sample;
      }
      position += step;
    }
    while (stream.count > 0 && position >= stream.at(0).frames) {
      Chunk done = stream.pop();
      position -= done.frames;
      stream.played += done.frames;
      stream.queued -= done.frames;
      chunkPool.push(done);
    }
    if (stream.count == 0) position = 0; // a dry stream resumes at the next frame queued
    stream.position = position;
  }

  private static short clip(float value) {
    int rounded = Math.round(value);
    return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
  }

  @Override
  public int maxVoices() {
    return voices.length;
  }

  @Override
  public int createBuffer(PcmBuffer pcm) {
    Objects.requireNonNull(pcm, "pcm");
    checkOpen();
    Integer free = freeBuffers.poll();
    if (free != null) {
      buffers.set(free, pcm);
      return free + 1;
    }
    buffers.add(pcm);
    return buffers.size();
  }

  @Override
  public void destroyBuffer(int buffer) {
    if (buffer < 1 || buffer > buffers.size() || buffers.get(buffer - 1) == null) return;
    buffers.set(buffer - 1, null);
    freeBuffers.push(buffer - 1);
  }

  /** The buffer behind an identifier, or null; for tests. */
  public PcmBuffer buffer(int buffer) {
    return buffer < 1 || buffer > buffers.size() ? null : buffers.get(buffer - 1);
  }

  @Override
  public int startVoice(int buffer, boolean looping, int startFrame, VoiceParams params) {
    checkOpen();
    PcmBuffer pcm = buffer(buffer);
    if (pcm == null) throw new IllegalArgumentException("Unknown buffer " + buffer);
    for (int slot = 0; slot < voices.length; slot++) {
      Voice voice = voices[slot];
      if (voice.used) continue;
      voice.used = true;
      voice.playing = true;
      voice.looping = looping;
      voice.buffer = pcm;
      voice.position = pcm.frames() == 0 ? 0 : Math.floorMod(startFrame, pcm.frames());
      apply(voice, params);
      return id(slot, voice.generation);
    }
    return 0;
  }

  @Override
  public void updateVoice(int voice, VoiceParams params) {
    Voice v = voice(voice);
    if (v != null) apply(v, params);
  }

  private static void apply(Voice voice, VoiceParams params) {
    voice.left = params.left();
    voice.right = params.right();
    voice.pitch = params.pitch();
  }

  @Override
  public boolean voicePlaying(int voice) {
    Voice v = voice(voice);
    return v != null && v.playing;
  }

  @Override
  public void stopVoice(int voice) {
    Voice v = voice(voice);
    if (v == null) return;
    v.used = false;
    v.playing = false;
    v.buffer = null;
    v.generation = (v.generation + 1) & GENERATION_MASK;
  }

  @Override
  public int openStream(int sampleRate, int channels) {
    checkOpen();
    if (sampleRate < 1 || sampleRate > Pcm.MAX_RATE || channels < 1 || channels > 2)
      throw new IllegalArgumentException("Unsupported stream format");
    for (int slot = 0; slot < streams.length; slot++) {
      Stream stream = streams[slot];
      if (stream.used) continue;
      stream.used = true;
      stream.rate = sampleRate;
      stream.channels = channels;
      stream.gain = 1;
      stream.queued = 0;
      stream.played = 0;
      stream.position = 0;
      return id(slot, stream.generation);
    }
    return 0;
  }

  @Override
  public void queueStream(int stream, short[] samples, int offset, int frames) {
    Stream s = stream(stream);
    if (s == null) return;
    int channels = s.channels;
    Objects.checkFromIndexSize(offset, frames * channels, samples.length);
    int copied = 0;
    while (copied < frames) {
      Chunk tail = s.count == 0 ? null : s.at(s.count - 1);
      if (tail == null || tail.frames == CHUNK_FRAMES) {
        tail = takeChunk();
        s.push(tail);
      }
      int count = Math.min(frames - copied, CHUNK_FRAMES - tail.frames);
      System.arraycopy(
          samples, offset + copied * channels, tail.data, tail.frames * channels, count * channels);
      tail.frames += count;
      copied += count;
    }
    s.queued += frames;
  }

  private Chunk takeChunk() {
    Chunk chunk = chunkPool.poll();
    if (chunk == null) {
      chunk = new Chunk();
      chunksAllocated++;
    }
    chunk.frames = 0;
    return chunk;
  }

  @Override
  public int streamQueuedFrames(int stream) {
    Stream s = stream(stream);
    return s == null ? 0 : (int) Math.max(0, s.queued - (long) s.position);
  }

  @Override
  public void setStreamGain(int stream, float gain) {
    Stream s = stream(stream);
    if (s != null) s.gain = gain;
  }

  @Override
  public void closeStream(int stream) {
    Stream s = stream(stream);
    if (s == null) return;
    while (s.count > 0) chunkPool.push(s.pop());
    s.used = false;
    s.generation = (s.generation + 1) & GENERATION_MASK;
  }

  @Override
  public void setListener(Listener listener) {
    this.listener = Objects.requireNonNull(listener);
  }

  @Override
  public void commit() {}

  @Override
  public void close() {
    closed = true;
    for (int slot = 0; slot < voices.length; slot++)
      if (voices[slot].used) stopVoice(id(slot, voices[slot].generation));
    for (int slot = 0; slot < streams.length; slot++)
      if (streams[slot].used) closeStream(id(slot, streams[slot].generation));
    buffers.clear();
    freeBuffers.clear();
    chunkPool.clear();
  }

  private void checkOpen() {
    if (closed) throw new IllegalStateException("Software mixer is closed");
  }

  private static int id(int slot, int generation) {
    return (generation << SLOT_BITS) | (slot + 1);
  }

  private Voice voice(int id) {
    int slot = (id & SLOT_MASK) - 1;
    if (id <= 0 || slot < 0 || slot >= voices.length) return null;
    Voice voice = voices[slot];
    return voice.used && voice.generation == id >>> SLOT_BITS ? voice : null;
  }

  private Stream stream(int id) {
    int slot = (id & SLOT_MASK) - 1;
    if (id <= 0 || slot < 0 || slot >= streams.length) return null;
    Stream stream = streams[slot];
    return stream.used && stream.generation == id >>> SLOT_BITS ? stream : null;
  }
}
