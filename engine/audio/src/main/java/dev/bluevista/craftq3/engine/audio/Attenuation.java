package dev.bluevista.craftq3.engine.audio;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * Quake III's sound falloff: a source within {@link #FULL_VOLUME_DISTANCE} units plays at full
 * volume; beyond it the volume drops linearly by {@link #ROLLOFF_PER_UNIT} per unit and reaches
 * silence at {@link #MAX_DISTANCE}. Stereo separation splits the volume between the ears by how far
 * the source lies to the listener's right: a source straight ahead gives each ear half, one fully
 * to the right gives the right ear everything.
 */
public final class Attenuation {
  /** Sources this close play at full volume ({@code SOUND_FULLVOLUME}). */
  public static final float FULL_VOLUME_DISTANCE = 80f;

  /** Volume lost per unit beyond {@link #FULL_VOLUME_DISTANCE} ({@code SOUND_ATTENUATE}). */
  public static final float ROLLOFF_PER_UNIT = 0.0008f;

  /** The distance at which a source falls silent: 1330 units. */
  public static final float MAX_DISTANCE = FULL_VOLUME_DISTANCE + 1f / ROLLOFF_PER_UNIT;

  private static final float DIRECTION_EPSILON = 1e-4f;

  private Attenuation() {}

  /** Distance rolloff in [0, 1] for a source {@code distance} units from the listener. */
  public static float distanceGain(float distance) {
    float beyond = Math.max(0f, distance - FULL_VOLUME_DISTANCE);
    return Math.max(0f, 1f - beyond * ROLLOFF_PER_UNIT);
  }

  /**
   * Fills {@code out} with the spatialised parameters of a source at ({@code x}, {@code y}, {@code
   * z}) heard by {@code listener} at {@code volume}. A source at the listener's own position has no
   * direction and is heard at half volume in each ear, as in the original mixer.
   */
  public static void spatialise(
      Listener listener, float x, float y, float z, float volume, VoiceParams out) {
    Vec3f origin = listener.origin();
    float dx = x - origin.x(), dy = y - origin.y(), dz = z - origin.z();
    float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    float overall = volume * distanceGain(distance);
    float side = 0f;
    if (distance > DIRECTION_EPSILON) {
      Vec3f left = listener.left();
      side = -(dx * left.x() + dy * left.y() + dz * left.z()) / distance;
      side = Math.max(-1f, Math.min(1f, side));
    }
    float rightEar = overall * 0.5f * (1f + side);
    float leftEar = overall * 0.5f * (1f - side);
    out.setSpatialised(overall, leftEar, rightEar, x, y, z);
  }
}
