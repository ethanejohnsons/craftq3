package dev.bluevista.craftq3.engine.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A silent {@link AudioDevice} for headless runs and tests. It keeps the state a real device would
 * (buffers, voices, stream queues) so the sound system behaves as it does with sound, and advances
 * playback only through {@link #advance}: non-looping voices end after their buffer's duration and
 * streams drain at their sample rate. When built with {@code record} it also logs every call as a
 * line of text, for tests.
 *
 * <p>Single-threaded, like the sound system that drives it.
 */
public final class NullAudioDevice implements AudioDevice {
  /** A voice as the device last saw it. */
  public record VoiceState(
      int id,
      int buffer,
      boolean looping,
      int startFrame,
      VoiceParams params,
      double playedMillis) {}

  private final int maxVoices;
  private final boolean record;
  private final List<String> calls = new ArrayList<>();
  private final Map<Integer, PcmBuffer> buffers = new LinkedHashMap<>();
  private final Map<Integer, MutableVoice> voices = new LinkedHashMap<>();
  private final Map<Integer, MutableStream> streams = new LinkedHashMap<>();
  private Listener listener = Listener.DEFAULT;
  private int nextBuffer = 1;
  private int nextVoice = 1;
  private int nextStream = 1;
  private int commits;

  private static final class MutableVoice {
    final int buffer;
    final boolean looping;
    final int startFrame;
    final VoiceParams params;
    boolean playing = true;
    double playedMillis;

    MutableVoice(int buffer, boolean looping, int startFrame, VoiceParams params) {
      this.buffer = buffer;
      this.looping = looping;
      this.startFrame = startFrame;
      this.params = params;
    }
  }

  private static final class MutableStream {
    final int rate;
    final int channels;
    float gain = 1;
    double queued;
    long totalQueued;
    double played;

    MutableStream(int rate, int channels) {
      this.rate = rate;
      this.channels = channels;
    }
  }

  /** A device with {@code maxVoices} sources, optionally logging every call. */
  public NullAudioDevice(int maxVoices, boolean record) {
    if (maxVoices < 1) throw new IllegalArgumentException("maxVoices must be positive");
    this.maxVoices = maxVoices;
    this.record = record;
  }

  /** Plays {@code millis} milliseconds: ends finished voices and drains streams. */
  public void advance(double millis) {
    if (millis < 0) throw new IllegalArgumentException("Negative time");
    for (MutableVoice voice : voices.values()) {
      if (!voice.playing) continue;
      voice.playedMillis += millis;
      PcmBuffer buffer = buffers.get(voice.buffer);
      if (!voice.looping && (buffer == null || voice.playedMillis >= buffer.durationMillis()))
        voice.playing = false;
    }
    for (MutableStream stream : streams.values()) {
      double frames = Math.min(stream.queued, stream.rate * millis / 1000.0);
      stream.queued -= frames;
      stream.played += frames;
    }
  }

  /** Every call logged so far (empty unless recording). */
  public List<String> calls() {
    return Collections.unmodifiableList(calls);
  }

  /** Logged calls starting with {@code prefix}. */
  public List<String> calls(String prefix) {
    return calls.stream().filter(call -> call.startsWith(prefix)).toList();
  }

  public void clearCalls() {
    calls.clear();
  }

  /** Voices started and not yet stopped, in start order. */
  public List<VoiceState> voices() {
    List<VoiceState> result = new ArrayList<>();
    voices.forEach(
        (id, v) ->
            result.add(
                new VoiceState(
                    id, v.buffer, v.looping, v.startFrame, v.params.copy(), v.playedMillis)));
    return result;
  }

  /** The state of one voice, or null if it was stopped. */
  public VoiceState voice(int id) {
    MutableVoice v = voices.get(id);
    return v == null
        ? null
        : new VoiceState(id, v.buffer, v.looping, v.startFrame, v.params.copy(), v.playedMillis);
  }

  /** The buffer behind an identifier, or null if destroyed. */
  public PcmBuffer buffer(int id) {
    return buffers.get(id);
  }

  public int bufferCount() {
    return buffers.size();
  }

  public int streamCount() {
    return streams.size();
  }

  /** Frames ever queued on a stream. */
  public long streamTotalQueued(int stream) {
    MutableStream s = streams.get(stream);
    return s == null ? 0 : s.totalQueued;
  }

  public float streamGain(int stream) {
    MutableStream s = streams.get(stream);
    return s == null ? 0 : s.gain;
  }

  public Listener listener() {
    return listener;
  }

  public int commits() {
    return commits;
  }

  @Override
  public int maxVoices() {
    return maxVoices;
  }

  @Override
  public int createBuffer(PcmBuffer pcm) {
    Objects.requireNonNull(pcm);
    int id = nextBuffer++;
    buffers.put(id, pcm);
    log(
        "createBuffer "
            + id
            + " rate="
            + pcm.sampleRate()
            + " channels="
            + pcm.channels()
            + " frames="
            + pcm.frames());
    return id;
  }

  @Override
  public void destroyBuffer(int buffer) {
    buffers.remove(buffer);
    log("destroyBuffer " + buffer);
  }

  @Override
  public int startVoice(int buffer, boolean looping, int startFrame, VoiceParams params) {
    if (!buffers.containsKey(buffer)) throw new IllegalArgumentException("Unknown buffer");
    if (voices.size() >= maxVoices) {
      log("startVoice refused buffer=" + buffer);
      return 0;
    }
    int id = nextVoice++;
    voices.put(id, new MutableVoice(buffer, looping, startFrame, params.copy()));
    log(
        "startVoice "
            + id
            + " buffer="
            + buffer
            + " loop="
            + looping
            + " start="
            + startFrame
            + " "
            + params);
    return id;
  }

  @Override
  public void updateVoice(int voice, VoiceParams params) {
    MutableVoice v = voices.get(voice);
    if (v != null) v.params.set(params);
    log("updateVoice " + voice + " " + params);
  }

  @Override
  public boolean voicePlaying(int voice) {
    MutableVoice v = voices.get(voice);
    return v != null && v.playing;
  }

  @Override
  public void stopVoice(int voice) {
    voices.remove(voice);
    log("stopVoice " + voice);
  }

  @Override
  public int openStream(int sampleRate, int channels) {
    int id = nextStream++;
    streams.put(id, new MutableStream(sampleRate, channels));
    log("openStream " + id + " rate=" + sampleRate + " channels=" + channels);
    return id;
  }

  @Override
  public void queueStream(int stream, short[] samples, int offset, int frames) {
    MutableStream s = streams.get(stream);
    if (s == null) return;
    Objects.checkFromIndexSize(offset, frames * s.channels, samples.length);
    s.queued += frames;
    s.totalQueued += frames;
    log("queueStream " + stream + " frames=" + frames);
  }

  @Override
  public int streamQueuedFrames(int stream) {
    MutableStream s = streams.get(stream);
    return s == null ? 0 : (int) Math.ceil(s.queued);
  }

  @Override
  public void setStreamGain(int stream, float gain) {
    MutableStream s = streams.get(stream);
    if (s != null) s.gain = gain;
  }

  @Override
  public void closeStream(int stream) {
    streams.remove(stream);
    log("closeStream " + stream);
  }

  @Override
  public void setListener(Listener listener) {
    this.listener = Objects.requireNonNull(listener);
  }

  @Override
  public void commit() {
    commits++;
  }

  @Override
  public void close() {
    voices.clear();
    streams.clear();
    buffers.clear();
    log("close");
  }

  private void log(String call) {
    if (record) calls.add(call);
  }
}
