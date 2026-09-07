package dev.bluevista.craftq3.platform.audio;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import org.junit.jupiter.api.Test;

class AudioBackendTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void acceptsDistinctLocalFixedAndFollowingRequests() {
    var local = new AudioBackend.Playback(1, -1, 0, AudioBackend.Spatial.LOCAL, null, 1, 1);
    var positional =
        new AudioBackend.Playback(
            1, 100, 3, AudioBackend.Spatial.POSITION, new Vec3(64, 32, 16), .5f, 1);
    var entity = new AudioBackend.Playback(1, 1023, 0, AudioBackend.Spatial.ENTITY, null, 1, 1);
    assertEquals(AudioBackend.CHAN_AUTO, local.channel());
    assertEquals(new Vec3(64, 32, 16), positional.origin());
    assertEquals(1023, entity.entity());
  }

  @Test
  void rejectsUnresolvableSourcesAndUnboundedValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioBackend.Playback(0, 0, 0, AudioBackend.Spatial.LOCAL, null, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioBackend.Playback(1, -1, 0, AudioBackend.Spatial.ENTITY, null, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioBackend.Playback(1, 0, 0, AudioBackend.Spatial.POSITION, null, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioBackend.Playback(1, 0, -1, AudioBackend.Spatial.LOCAL, null, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioBackend.Playback(1, 0, 0, AudioBackend.Spatial.LOCAL, null, Float.NaN, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AudioBackend.Playback(
                1, 0, 0, AudioBackend.Spatial.LOCAL, null, 1, Float.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> new AudioBackend.Loop(1024, 1, ZERO, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new AudioBackend.Loop(1, 1, ZERO, 2, 1));
  }

  @Test
  void normalizesListenerBasisAndRejectsParallelAxes() {
    var listener = new AudioBackend.Listener(10, ZERO, new Vec3(3, 0, 0), new Vec3(1, 0, 2), true);
    assertEquals(new Vec3(1, 0, 0), listener.forward());
    assertEquals(new Vec3(0, 0, 1), listener.up());
    assertTrue(listener.inWater());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioBackend.Listener(ZERO, ZERO, new Vec3(0, 0, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AudioBackend.Listener(ZERO, new Vec3(1, 0, 0), new Vec3(2, 0, 0)));
  }

  @Test
  void canonicalAudioPositionsMapToHostAxesAndScale() {
    var coordinates = new CoordinateTransform(32, new Vec3(100, 64, 200));
    Vec3 sound = new Vec3(32, 64, 96);
    assertEquals(new Vec3(101, 67, 198), coordinates.toMinecraft(sound));
    assertEquals(sound, coordinates.toQuake(coordinates.toMinecraft(sound)));
  }
}
