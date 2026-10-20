package dev.bluevista.craftq3.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoundRegistrationTest {
  private final MemoryAssets assets =
      new MemoryAssets()
          .put("sound/weapons/shot.wav", TestSounds.wav16(22050, 2, new short[200]))
          .put("sound/bad.wav", new byte[] {1, 2, 3})
          .put("sound/empty.wav", TestSounds.wav16(22050, 1));
  private final NullAudioDevice device = new NullAudioDevice(16, true);
  private final SoundSystem sound = new SoundSystem(assets, device, SoundSystem.Limits.DEFAULT);

  @Test
  void namesAreMatchedLikeTheOriginal() {
    int handle = sound.registerSound("sound/weapons/shot.wav", false);
    assertTrue(handle > 0);
    assertEquals(handle, sound.registerSound("SOUND\\Weapons\\Shot.WAV", true));
    assertEquals(1, sound.registeredSounds());
  }

  @Test
  void soundsLoadLazilyAsMonoAtTheirNativeRate() {
    int handle = sound.registerSound("sound/weapons/shot.wav", false);
    assertEquals(0, device.bufferCount());
    assertFalse(sound.soundInfo(handle).orElseThrow().loaded());
    sound.startLocalSound(handle, SoundChannel.LOCAL);
    assertEquals(
        List.of("createBuffer 1 rate=22050 channels=1 frames=100"), device.calls("createBuffer"));
    sound.update(100);
    sound.startLocalSound(handle, SoundChannel.LOCAL);
    assertEquals(1, device.bufferCount(), "a sound is uploaded once");
    var info = sound.soundInfo(handle).orElseThrow();
    assertTrue(info.loaded());
    assertFalse(info.placeholder());
    assertEquals(100, info.frames());
  }

  @Test
  void aMissingSoundGetsASilentHandleAndOneWarning() {
    try (var warnings = new TestSounds.Warnings()) {
      int handle = sound.registerSound("sound/missing.wav", false);
      assertNotEquals(0, handle);
      assertEquals(handle, sound.registerSound("sound/missing.wav", false));
      sound.startSound(new Vec3f(1, 2, 3), 4, SoundChannel.WEAPON, handle);
      sound.startLocalSound(handle, SoundChannel.LOCAL);
      sound.update(16);
      assertTrue(sound.soundInfo(handle).orElseThrow().placeholder());
      assertEquals(0, sound.activeVoices());
      assertEquals(0, device.bufferCount());
      assertEquals(1, warnings.count("sound/missing.wav"));
    }
  }

  @Test
  void aMissingSoundStillReplacesItsChannel() {
    int shot = sound.registerSound("sound/weapons/shot.wav", false);
    int missing = sound.registerSound("sound/missing.wav", false);
    sound.startSound(null, 4, SoundChannel.WEAPON, shot);
    assertEquals(1, sound.activeVoices());
    sound.startSound(null, 4, SoundChannel.WEAPON, missing);
    assertEquals(0, sound.activeVoices());
  }

  @Test
  void unreadableAndEmptyFilesBecomeSilence() {
    try (var warnings = new TestSounds.Warnings()) {
      int bad = sound.registerSound("sound/bad.wav", false);
      int empty = sound.registerSound("sound/empty.wav", false);
      assertFalse(sound.precache(bad));
      assertFalse(sound.precache(empty));
      assertTrue(sound.soundInfo(bad).orElseThrow().placeholder());
      assertTrue(sound.soundInfo(empty).orElseThrow().placeholder());
      assertEquals(1, warnings.count("sound/bad.wav"));
      assertEquals(1, warnings.count("sound/empty.wav"));
      assertEquals(0, device.bufferCount());
    }
  }

  @Test
  void invalidNamesGetNoHandle() {
    assertEquals(0, sound.registerSound("", false));
    assertEquals(0, sound.registerSound(null, false));
    assertEquals(0, sound.registerSound("sound/" + "x".repeat(60) + ".wav", false));
    assertEquals(0, sound.registeredSounds());
  }

  @Test
  void resetForgetsHandlesAndFreesBuffers() {
    int handle = sound.registerSound("sound/weapons/shot.wav", false);
    sound.precache(handle);
    sound.startLocalSound(handle, SoundChannel.LOCAL);
    sound.reset(new MemoryAssets());
    assertEquals(0, sound.registeredSounds());
    assertEquals(0, device.bufferCount());
    assertTrue(device.voices().isEmpty());
    assertTrue(sound.soundInfo(handle).isEmpty());
    sound.startLocalSound(handle, SoundChannel.LOCAL); // a stale handle is ignored
    assertEquals(0, sound.activeVoices());
  }

  @Test
  void closeReleasesBuffersButNotTheDevice() {
    int handle = sound.registerSound("sound/weapons/shot.wav", false);
    sound.precache(handle);
    sound.close();
    assertEquals(0, device.bufferCount());
    assertTrue(device.calls("close").isEmpty());
  }
}
