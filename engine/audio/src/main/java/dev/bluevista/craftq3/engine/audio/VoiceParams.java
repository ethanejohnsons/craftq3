package dev.bluevista.craftq3.engine.audio;

/**
 * The playback parameters of one voice, recomputed by the {@link SoundSystem} every update. The
 * system reuses instances: a device must copy what it needs during the call that passes them.
 *
 * <p>The sound system has already applied Quake III spatialisation and the volume setting, so a
 * device picks one of two equivalent views:
 *
 * <ul>
 *   <li>A 3D device (OpenAL) plays the voice at {@link #x()}, {@link #y()}, {@link #z()} with its
 *       own distance attenuation disabled and gain {@link #gain()}, which already includes the
 *       Quake III distance rolloff. A {@link #relative()} voice is played at the listener with no
 *       panning.
 *   <li>A 2D mixer applies {@link #left()} and {@link #right()} directly; these reproduce the
 *       original mixer's per-ear volumes, including its stereo separation.
 * </ul>
 */
public final class VoiceParams {
  private float gain = 1;
  private float left = 1;
  private float right = 1;
  private float pitch = 1;
  private float x;
  private float y;
  private float z;
  private float velocityX;
  private float velocityY;
  private float velocityZ;
  private boolean relative = true;

  /** Overall gain in [0, 1]: volume times distance rolloff, without stereo separation. */
  public float gain() {
    return gain;
  }

  /** Left-ear gain in [0, 1] as the original stereo mixer computes it. */
  public float left() {
    return left;
  }

  /** Right-ear gain in [0, 1] as the original stereo mixer computes it. */
  public float right() {
    return right;
  }

  /** Playback rate multiplier; Quake III always plays at 1. */
  public float pitch() {
    return pitch;
  }

  /** Source position in Quake III world coordinates. */
  public float x() {
    return x;
  }

  public float y() {
    return y;
  }

  public float z() {
    return z;
  }

  /** Source velocity in units per second (real looping sounds only; otherwise zero). */
  public float velocityX() {
    return velocityX;
  }

  public float velocityY() {
    return velocityY;
  }

  public float velocityZ() {
    return velocityZ;
  }

  /** True for unspatialised voices: listener-entity sounds and local (menu) sounds. */
  public boolean relative() {
    return relative;
  }

  /** An independent copy, for devices that keep parameters past the call. */
  public VoiceParams copy() {
    VoiceParams copy = new VoiceParams();
    copy.set(this);
    return copy;
  }

  /** Copies every field of {@code other} into this instance. */
  public void set(VoiceParams other) {
    gain = other.gain;
    left = other.left;
    right = other.right;
    pitch = other.pitch;
    x = other.x;
    y = other.y;
    z = other.z;
    velocityX = other.velocityX;
    velocityY = other.velocityY;
    velocityZ = other.velocityZ;
    relative = other.relative;
  }

  void setUnspatialised(float volume, float px, float py, float pz) {
    gain = volume;
    left = volume;
    right = volume;
    pitch = 1;
    x = px;
    y = py;
    z = pz;
    velocityX = velocityY = velocityZ = 0;
    relative = true;
  }

  void setSpatialised(float overall, float leftEar, float rightEar, float px, float py, float pz) {
    gain = overall;
    left = leftEar;
    right = rightEar;
    pitch = 1;
    x = px;
    y = py;
    z = pz;
    velocityX = velocityY = velocityZ = 0;
    relative = false;
  }

  void setVelocity(float vx, float vy, float vz) {
    velocityX = vx;
    velocityY = vy;
    velocityZ = vz;
  }

  @Override
  public String toString() {
    return String.format(
        java.util.Locale.ROOT,
        "VoiceParams[gain=%.4f left=%.4f right=%.4f at (%.1f %.1f %.1f)%s]",
        gain,
        left,
        right,
        x,
        y,
        z,
        relative ? " relative" : "");
  }
}
