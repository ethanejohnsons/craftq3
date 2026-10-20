package dev.bluevista.craftq3.engine.audio;

/**
 * Plays what the {@link SoundSystem} decides. The Minecraft mod implements it over Minecraft's
 * OpenAL sound engine; {@link NullAudioDevice} and {@link SoftwareMixer} are the engine's own.
 *
 * <p>Every method is called from the sound system's owner thread and must not block on playback; an
 * implementation that plays on another thread (Minecraft's sound executor) hands work over itself.
 * Identifiers are positive; 0 means "none" and is never passed back to the device. Every identifier
 * a {@code create}/{@code start}/{@code open} method returns is eventually passed to the matching
 * {@code destroy}/{@code stop}/{@code close} method exactly once, which frees it. A device that
 * cannot satisfy a request (no free source, native failure) returns 0 instead of throwing; the
 * sound system degrades and retries where Quake III would.
 *
 * <p>Positions are Quake III world coordinates; the device converts to its host's space.
 */
public interface AudioDevice extends AutoCloseable {
  /** The most voices (not counting streams) the device can play at once. */
  int maxVoices();

  /**
   * Uploads {@code pcm} as a playable buffer. Spatialised sounds arrive as mono. The device may
   * keep a reference to the samples instead of copying them; they are never modified.
   *
   * @return the buffer identifier, or 0 if the device cannot hold it
   */
  int createBuffer(PcmBuffer pcm);

  /** Frees a buffer. No voice that plays it is still running. */
  void destroyBuffer(int buffer);

  /**
   * Starts a voice playing {@code buffer} from frame {@code startFrame} (looping voices start part
   * way through so that every copy of a loop stays in phase, as in the original mixer).
   *
   * @param params initial parameters; copy them, the instance is reused
   * @return the voice identifier, or 0 if no source is free
   */
  int startVoice(int buffer, boolean looping, int startFrame, VoiceParams params);

  /** Applies new parameters to a running voice; copy them, the instance is reused. */
  void updateVoice(int voice, VoiceParams params);

  /**
   * False once a non-looping voice has played to its end (or the device dropped it). The sound
   * system then releases it with {@link #stopVoice}.
   */
  boolean voicePlaying(int voice);

  /** Stops a voice, if still playing, and frees its identifier. */
  void stopVoice(int voice);

  /**
   * Opens a streaming voice for signed 16-bit interleaved PCM at {@code sampleRate}. A stream is
   * unspatialised; it plays queued frames in order as soon as any are queued, falls silent when it
   * runs dry and resumes when more arrive.
   *
   * @return the stream identifier, or 0 if the device cannot open one
   */
  int openStream(int sampleRate, int channels);

  /**
   * Appends {@code frames} frames from {@code samples}, starting at sample index {@code offset}.
   * The device copies the data before returning (the caller reuses the array) into storage it
   * recycles, so steady-state streaming allocates nothing.
   */
  void queueStream(int stream, short[] samples, int offset, int frames);

  /** Frames queued on {@code stream} that have not been played yet. */
  int streamQueuedFrames(int stream);

  /** Sets the gain in [0, 1] of a stream. */
  void setStreamGain(int stream, float gain);

  /** Stops a stream, discards its queue and frees its identifier. */
  void closeStream(int stream);

  /** The listener for this update, in Quake III coordinates. */
  void setListener(Listener listener);

  /**
   * Ends one sound system update: everything since the previous commit forms one batch, which a
   * device that plays on another thread can submit at once.
   */
  void commit();

  /** Releases every native resource. The sound system never calls this; the device's host does. */
  @Override
  void close();
}
