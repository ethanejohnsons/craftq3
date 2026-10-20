package dev.bluevista.craftq3.engine.audio;

import static dev.bluevista.craftq3.engine.audio.TestSounds.FORWARD;
import static dev.bluevista.craftq3.engine.audio.TestSounds.LEFT;
import static dev.bluevista.craftq3.engine.audio.TestSounds.UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

class AttenuationTest {
  private static final float EPSILON = 1e-5f;
  private static final Listener AT_ORIGIN = new Listener(1, Vec3f.ZERO, FORWARD, LEFT, UP, false);

  @Test
  void fullVolumeWithinEightyUnits() {
    assertEquals(1f, Attenuation.distanceGain(0), EPSILON);
    assertEquals(1f, Attenuation.distanceGain(40), EPSILON);
    assertEquals(1f, Attenuation.distanceGain(Attenuation.FULL_VOLUME_DISTANCE), EPSILON);
  }

  @Test
  void linearRolloffToSilenceAtMaxDistance() {
    // Half the volume is lost 1 / (2 * 0.0008) = 625 units beyond the full-volume radius.
    assertEquals(0.5f, Attenuation.distanceGain(80 + 625), EPSILON);
    assertEquals(0.92f, Attenuation.distanceGain(180), EPSILON);
    assertEquals(1330f, Attenuation.MAX_DISTANCE, EPSILON);
    assertEquals(0f, Attenuation.distanceGain(Attenuation.MAX_DISTANCE), EPSILON);
    assertEquals(0f, Attenuation.distanceGain(5000), EPSILON);
  }

  @Test
  void sourceAheadSplitsEvenly() {
    VoiceParams out = new VoiceParams();
    Attenuation.spatialise(AT_ORIGIN, 50, 0, 0, 1f, out);
    assertEquals(0.5f, out.left(), EPSILON);
    assertEquals(0.5f, out.right(), EPSILON);
    assertEquals(1f, out.gain(), EPSILON);
    assertFalse(out.relative());
  }

  @Test
  void sourceToTheRightPlaysInTheRightEarOnly() {
    VoiceParams out = new VoiceParams();
    Attenuation.spatialise(AT_ORIGIN, 0, -60, 0, 1f, out); // -left is right
    assertEquals(1f, out.right(), EPSILON);
    assertEquals(0f, out.left(), EPSILON);
    Attenuation.spatialise(AT_ORIGIN, 0, 60, 0, 1f, out);
    assertEquals(0f, out.right(), EPSILON);
    assertEquals(1f, out.left(), EPSILON);
  }

  @Test
  void diagonalSourceAndVolumeCombine() {
    VoiceParams out = new VoiceParams();
    // 45 degrees to the right, 705 units away: half volume, then split by 0.5 * (1 +- sqrt(.5)).
    float d = 705f / (float) Math.sqrt(2);
    Attenuation.spatialise(AT_ORIGIN, d, -d, 0, 0.8f, out);
    float side = (float) Math.sqrt(0.5);
    assertEquals(0.4f, out.gain(), 1e-4f);
    assertEquals(0.4f * 0.5f * (1 + side), out.right(), 1e-4f);
    assertEquals(0.4f * 0.5f * (1 - side), out.left(), 1e-4f);
  }

  @Test
  void separationFollowsTheListenerAxis() {
    // Listener turned to face +y: its left is -x, so a source at +x is on its right.
    Listener turned =
        new Listener(1, Vec3f.ZERO, new Vec3f(0, 1, 0), new Vec3f(-1, 0, 0), UP, false);
    VoiceParams out = new VoiceParams();
    Attenuation.spatialise(turned, 70, 0, 0, 1f, out);
    assertEquals(1f, out.right(), EPSILON);
    assertEquals(0f, out.left(), EPSILON);
  }

  @Test
  void sourceAtTheListenerHasNoDirection() {
    VoiceParams out = new VoiceParams();
    Attenuation.spatialise(AT_ORIGIN, 0, 0, 0, 1f, out);
    assertEquals(0.5f, out.left(), EPSILON);
    assertEquals(0.5f, out.right(), EPSILON);
  }
}
