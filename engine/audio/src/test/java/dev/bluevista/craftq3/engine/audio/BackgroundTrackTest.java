package dev.bluevista.craftq3.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackgroundTrackTest {
  private static final int RATE = 22050;
  private static final int TARGET = RATE * BackgroundTrack.BACKLOG_MILLIS / 1000;

  private final MemoryAssets assets =
      new MemoryAssets()
          .put("music/intro.wav", TestSounds.constant(RATE, 1000, 1000))
          .put("music/loop.wav", TestSounds.constant(RATE, 300, -2000))
          .put("music/stereo11k.wav", TestSounds.wav16(11025, 2, new short[2000 * 2]));

  @Test
  void introPlaysOnceThenTheLoopRepeats() {
    var mixer = new SoftwareMixer(RATE, 8);
    var sound = new SoundSystem(assets, mixer, SoundSystem.Limits.DEFAULT);
    sound.setMusicVolume(1f);
    sound.startBackgroundTrack("music/intro.wav", "music/loop.wav");
    List<Integer> left = new ArrayList<>();
    short[] out = new short[441 * 2];
    for (int frame = 0; frame < 20; frame++) {
      mixer.mix(out, 441);
      for (int i = 0; i < 441; i++) left.add((int) out[i * 2]);
      sound.update(20);
    }
    for (int i = 0; i < 1000; i++) assertEquals(1000, (int) left.get(i), "intro frame " + i);
    for (int i = 1000; i < left.size(); i++)
      assertEquals(-2000, (int) left.get(i), "loop frame " + i);
    assertTrue(sound.backgroundTrackLooping());
    assertTrue(sound.backgroundTrackPlaying());
  }

  @Test
  void theStreamIsRefilledAheadOfPlayback() {
    var device = new NullAudioDevice(8, true);
    var sound = new SoundSystem(assets, device, SoundSystem.Limits.DEFAULT);
    sound.startBackgroundTrack("music/intro.wav", "music/loop.wav");
    int stream = 1;
    assertTrue(device.streamQueuedFrames(stream) >= TARGET);
    for (int i = 0; i < 50; i++) {
      device.advance(100);
      sound.update(100);
      assertTrue(device.streamQueuedFrames(stream) >= TARGET, "backlog after update " + i);
    }
    // Five seconds have played, so at least that much was queued, in bounded blocks.
    assertTrue(device.streamTotalQueued(stream) >= RATE * 5L);
    for (String call : device.calls("queueStream")) {
      int frames = Integer.parseInt(call.substring(call.indexOf("frames=") + 7));
      assertTrue(frames <= BackgroundTrack.CHUNK_FRAMES);
    }
    assertEquals(SoundSystem.DEFAULT_MUSIC_VOLUME, device.streamGain(stream));
  }

  @Test
  void anEmptyLoopRepeatsTheIntroAndNamesDefaultToWav() {
    var mixer = new SoftwareMixer(RATE, 8);
    var sound = new SoundSystem(assets, mixer, SoundSystem.Limits.DEFAULT);
    sound.setMusicVolume(1f);
    sound.startBackgroundTrack("music/intro", "");
    short[] out = new short[3000 * 2];
    mixer.mix(out, 3000);
    for (int i = 0; i < 3000; i++) assertEquals(1000, out[i * 2]);
    sound.update(136);
    assertTrue(sound.backgroundTrackLooping());
  }

  @Test
  void aMissingIntroPlaysNothing() {
    var device = new NullAudioDevice(8, true);
    var sound = new SoundSystem(assets, device, SoundSystem.Limits.DEFAULT);
    try (var warnings = new TestSounds.Warnings()) {
      sound.startBackgroundTrack("music/absent.wav", "music/loop.wav");
      assertFalse(sound.backgroundTrackPlaying());
      assertEquals(0, device.streamCount());
      assertEquals(1, warnings.count("music/absent.wav"));
    }
  }

  @Test
  void anIntroWithoutAPlayableLoopEndsAndClosesItsStream() {
    var device = new NullAudioDevice(8, true);
    var sound = new SoundSystem(assets, device, SoundSystem.Limits.DEFAULT);
    sound.startBackgroundTrack("music/intro.wav", "music/absent.wav");
    assertEquals(1000, device.streamTotalQueued(1));
    sound.update(10);
    assertTrue(sound.backgroundTrackPlaying(), "still draining");
    device.advance(100);
    sound.update(100);
    assertFalse(sound.backgroundTrackPlaying());
    assertEquals(0, device.streamCount());
  }

  @Test
  void stopClosesTheStream() {
    var device = new NullAudioDevice(8, true);
    var sound = new SoundSystem(assets, device, SoundSystem.Limits.DEFAULT);
    sound.startBackgroundTrack("music/intro.wav", "music/loop.wav");
    sound.stopBackgroundTrack();
    assertEquals(List.of("closeStream 1"), device.calls("closeStream"));
    assertFalse(sound.backgroundTrackPlaying());
    sound.startBackgroundTrack("music/intro.wav", "");
    sound.stopAllSounds();
    assertEquals(0, device.streamCount());
  }

  @Test
  void aLoopInAnotherFormatReopensTheStreamAfterTheIntroDrains() {
    var device = new NullAudioDevice(8, true);
    var sound = new SoundSystem(assets, device, SoundSystem.Limits.DEFAULT);
    sound.startBackgroundTrack("music/intro.wav", "music/stereo11k.wav");
    assertEquals(List.of("openStream 1 rate=22050 channels=1"), device.calls("openStream"));
    sound.update(10);
    assertEquals(1, device.calls("openStream").size(), "waits for the intro to drain");
    device.advance(100);
    sound.update(100);
    assertEquals(
        List.of("openStream 1 rate=22050 channels=1", "openStream 2 rate=11025 channels=2"),
        device.calls("openStream"));
    assertEquals(List.of("closeStream 1"), device.calls("closeStream"));
    assertTrue(device.streamQueuedFrames(2) >= 11025 * BackgroundTrack.BACKLOG_MILLIS / 1000);
  }

  @Test
  void steadyStreamingReusesMixerChunks() {
    var mixer = new SoftwareMixer(RATE, 8);
    var sound = new SoundSystem(assets, mixer, SoundSystem.Limits.DEFAULT);
    sound.startBackgroundTrack("music/intro.wav", "music/loop.wav");
    short[] out = new short[441 * 2];
    for (int i = 0; i < 50; i++) {
      mixer.mix(out, 441);
      sound.update(20);
    }
    int allocated = mixer.chunksAllocated();
    for (int i = 0; i < 500; i++) {
      mixer.mix(out, 441);
      sound.update(20);
    }
    assertEquals(allocated, mixer.chunksAllocated());
  }
}
