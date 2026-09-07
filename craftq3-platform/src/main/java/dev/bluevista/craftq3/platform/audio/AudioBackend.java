package dev.bluevista.craftq3.platform.audio;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.core.math.Vec3;

/**
 * Session audio in canonical Q3 coordinates/units. Handles are backend-owned; registration takes
 * decoded immutable PCM, never a host SoundEvent. Methods submit work without blocking for
 * playback. Call beginFrame, submit this frame's loops/entity positions, then endFrame to reconcile
 * the scene.
 */
public interface AudioBackend extends AutoCloseable {
  int CHAN_AUTO = 0;

  enum Spatial {
    LOCAL,
    POSITION,
    ENTITY
  }

  record Playback(
      int sound, int entity, int channel, Spatial spatial, Vec3 origin, float gain, float pitch) {
    public Playback {
      if (sound < 1
          || entity < -1
          || entity > 1023
          || channel < 0
          || channel > 255
          || spatial == null
          || (spatial == Spatial.POSITION && origin == null)
          || (spatial == Spatial.ENTITY && entity < 0))
        throw new IllegalArgumentException("Invalid audio source");
      validateGainPitch(gain, pitch);
    }
  }

  /** One continuously submitted loop per entity; omitting it from the next frame stops it. */
  record Loop(int entity, int sound, Vec3 origin, float gain, float pitch) {
    public Loop {
      if (entity < 0 || entity > 1023 || sound < 1 || origin == null)
        throw new IllegalArgumentException("Invalid audio loop");
      validateGainPitch(gain, pitch);
    }
  }

  /** Underwater state is retained for future effects; the basic PCM backend does not filter it. */
  record Listener(int entity, Vec3 origin, Vec3 forward, Vec3 up, boolean inWater) {
    public Listener {
      if (entity < -1 || entity > 1023 || origin == null || forward == null || up == null) {
        throw new IllegalArgumentException("Invalid audio listener");
      }
      forward = normalized(forward);
      up = normalized(up.add(forward.scale(-dot(forward, up))));
    }

    public Listener(Vec3 origin, Vec3 forward, Vec3 up) {
      this(-1, origin, forward, up, false);
    }
  }

  record Diagnostics(
      boolean available,
      int registeredSounds,
      int pendingVoices,
      int activeVoices,
      int activeLoops,
      long startedVoices,
      long failures,
      String lastFailure) {}

  int register(String name, PcmSound sound);

  /** Nonzero channels replace a previous voice on the same entity/channel; CHAN_AUTO overlaps. */
  long play(Playback playback);

  /** Local streaming voice; the backend owns and closes the source, including rejected requests. */
  default long stream(PcmStream source, float gain) {
    source.close();
    throw new UnsupportedOperationException("Streaming PCM is unavailable on this audio backend");
  }

  void updateEntity(int entity, Vec3 origin);

  void beginFrame();

  void submitLoop(Loop loop);

  void endFrame(Listener listener);

  void clearLoops();

  void stop(long voice);

  void stopAll();

  /**
   * Stop playback and forget registered assets after a filesystem-view change. Call only after
   * closing every VM that owns sound handles; previous handles are no longer valid. The backend and
   * its borrowed host sound engine remain open. Stateless adapters only need to stop playback.
   */
  default void resetAssets() {
    stopAll();
  }

  /** Q3 session gain in [0,1], multiplied by the host's current master volume. */
  void volume(float gain);

  Diagnostics diagnostics();

  @Override
  void close();

  private static void validateGainPitch(float gain, float pitch) {
    if (!Float.isFinite(gain)
        || gain < 0
        || gain > 1
        || !Float.isFinite(pitch)
        || pitch < .5f
        || pitch > 2) throw new IllegalArgumentException("Invalid audio gain or pitch");
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private static Vec3 normalized(Vec3 value) {
    double length = Math.hypot(Math.hypot(value.x(), value.y()), value.z());
    if (!Double.isFinite(length) || length < 1e-12)
      throw new IllegalArgumentException("Degenerate audio listener basis");
    return value.scale(1 / length);
  }
}
