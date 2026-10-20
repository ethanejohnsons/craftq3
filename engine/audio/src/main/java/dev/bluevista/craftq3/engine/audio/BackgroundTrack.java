package dev.bluevista.craftq3.engine.audio;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.QPath;
import dev.bluevista.craftq3.engine.formats.wav.PcmSound;
import dev.bluevista.craftq3.engine.formats.wav.WavFormatException;
import dev.bluevista.craftq3.engine.formats.wav.WavReader;
import java.util.Optional;

/**
 * Background music: an intro played once, then a loop repeated until stopped, streamed to one
 * device stream. The stream is kept {@link #BACKLOG_MILLIS} ahead of playback in chunks of at most
 * {@link #CHUNK_FRAMES} frames, queued straight from the decoded track (the device copies them).
 * When the loop's format differs from the intro's, the stream drains and reopens in the new format.
 */
final class BackgroundTrack {
  /** How far ahead of playback the stream is kept filled. */
  static final int BACKLOG_MILLIS = 400;

  /** The largest block queued in one call. */
  static final int CHUNK_FRAMES = 4096;

  private static final int MAX_QUEUES_PER_REFILL = 64;
  private static final Log LOG = Log.get(BackgroundTrack.class);

  private record Track(String name, short[] samples, int rate, int channels) {
    int frames() {
      return samples.length / channels;
    }
  }

  private final AudioDevice device;
  private String loopName;
  private Track current;
  private Track loop;
  private int position;
  private boolean looping;
  private int stream;
  private int streamRate;
  private int streamChannels;

  BackgroundTrack(AudioDevice device) {
    this.device = device;
  }

  /**
   * Starts {@code intro} followed by {@code loop} (the intro again when empty). Names without an
   * extension get {@code .wav}. Returns false, leaving the music stopped, when the intro cannot be
   * played.
   */
  boolean start(AssetLookup assets, String intro, String loopTrack) {
    stop();
    String introName = withExtension(intro == null ? "" : intro);
    if (introName.isEmpty()) return false;
    String loopTrackName =
        loopTrack == null || loopTrack.isBlank() ? introName : withExtension(loopTrack);
    Optional<Track> first = decode(assets, introName);
    if (first.isEmpty()) return false;
    current = first.get();
    loopName = loopTrackName;
    loop = loopTrackName.equals(introName) ? current : decode(assets, loopTrackName).orElse(null);
    if (loop != null && loop.frames() == 0) loop = null;
    position = 0;
    looping = false;
    return true;
  }

  /** True while a track is queued or still draining. */
  boolean playing() {
    return current != null || stream != 0;
  }

  /** True once the intro has finished and the loop is playing. */
  boolean inLoop() {
    return looping;
  }

  /** The loop track's name, or null when stopped. */
  String loopName() {
    return current == null ? null : loopName;
  }

  /** Keeps the stream {@link #BACKLOG_MILLIS} ahead; called every sound system update. */
  void refill(float gain) {
    if (current == null) {
      // The last track ended: close the stream once what was queued has played.
      if (stream != 0 && device.streamQueuedFrames(stream) == 0) closeStream();
      return;
    }
    if (stream != 0 && (streamRate != current.rate() || streamChannels != current.channels())) {
      if (device.streamQueuedFrames(stream) > 0) return; // drain before changing format
      closeStream();
    }
    if (stream == 0) {
      stream = device.openStream(current.rate(), current.channels());
      if (stream == 0) {
        LOG.warnOnce("music-stream", "The audio device has no stream free for music");
        return;
      }
      streamRate = current.rate();
      streamChannels = current.channels();
    }
    device.setStreamGain(stream, gain);
    int target = (int) ((long) streamRate * BACKLOG_MILLIS / 1000);
    for (int queued = 0;
        queued < MAX_QUEUES_PER_REFILL && device.streamQueuedFrames(stream) < target;
        queued++) {
      int remaining = current.frames() - position;
      if (remaining <= 0) {
        if (loop == null) {
          current = null; // no loop: the intro played once
          return;
        }
        current = loop;
        position = 0;
        looping = true;
        if (current.rate() != streamRate || current.channels() != streamChannels) return;
        continue;
      }
      int frames = Math.min(CHUNK_FRAMES, remaining);
      device.queueStream(stream, current.samples(), position * current.channels(), frames);
      position += frames;
    }
  }

  void stop() {
    closeStream();
    current = null;
    loop = null;
    loopName = null;
    position = 0;
    looping = false;
  }

  private void closeStream() {
    if (stream != 0) device.closeStream(stream);
    stream = 0;
  }

  private static Optional<Track> decode(AssetLookup assets, String name) {
    Optional<byte[]> bytes = assets.read(name);
    if (bytes.isEmpty()) {
      LOG.warnOnce("music-missing:" + name, "Cannot open music file " + name);
      return Optional.empty();
    }
    try {
      PcmSound sound = WavReader.read(bytes.get());
      return Optional.of(
          new Track(name, Pcm.interleaved16(sound), sound.sampleRate(), sound.channels()));
    } catch (WavFormatException | IllegalArgumentException bad) {
      LOG.warnOnce("music-bad:" + name, "Cannot play music file " + name + ": " + bad.getMessage());
      return Optional.empty();
    }
  }

  /** {@code COM_DefaultExtension}: appends {@code .wav} to a name without an extension. */
  static String withExtension(String name) {
    String normalized = QPath.normalize(name);
    if (normalized.isEmpty()) return normalized;
    return QPath.extension(normalized).isEmpty() ? normalized + ".wav" : normalized;
  }
}
