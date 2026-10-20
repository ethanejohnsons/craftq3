package dev.bluevista.craftq3.engine.audio;

import static dev.bluevista.craftq3.engine.audio.TestSounds.FORWARD;
import static dev.bluevista.craftq3.engine.audio.TestSounds.LEFT;
import static dev.bluevista.craftq3.engine.audio.TestSounds.UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoopingSoundTest {
  private static final Vec3f NEAR = new Vec3f(40, 0, 0);

  private NullAudioDevice device;
  private SoundSystem sound;
  private int hum;
  private int buzz;

  @BeforeEach
  void setUp() {
    var assets =
        new MemoryAssets()
            .put("sound/hum.wav", TestSounds.constant(11025, 1000, 500))
            .put("sound/buzz.wav", TestSounds.constant(22050, 2205, 500));
    device = new NullAudioDevice(64, true);
    sound = new SoundSystem(assets, device, new SoundSystem.Limits(16, 2));
    sound.respatialize(0, Vec3f.ZERO, FORWARD, LEFT, UP, false);
    hum = sound.registerSound("sound/hum.wav", false);
    buzz = sound.registerSound("sound/buzz.wav", false);
  }

  private void frame(Runnable loops) {
    sound.clearLoopingSounds(false);
    loops.run();
    sound.update(16);
  }

  @Test
  void aLoopReAddedEveryFrameKeepsOneVoice() {
    for (int i = 0; i < 10; i++) frame(() -> sound.addLoopingSound(5, NEAR, Vec3f.ZERO, hum));
    assertEquals(1, device.calls("startVoice").size());
    assertTrue(device.calls("stopVoice").isEmpty());
    assertTrue(device.voices().getFirst().looping());
    assertEquals(1, sound.activeLoopVoices());
  }

  @Test
  void aLoopNotReAddedStopsAtTheNextUpdate() {
    frame(() -> sound.addLoopingSound(5, NEAR, Vec3f.ZERO, hum));
    int id = device.voices().getFirst().id();
    frame(() -> {});
    assertEquals(java.util.List.of("stopVoice " + id), device.calls("stopVoice"));
    assertEquals(0, sound.activeLoopVoices());
  }

  @Test
  void realLoopsPersistUntilStopped() {
    frame(() -> sound.addRealLoopingSound(5, NEAR, new Vec3f(10, 0, 0), hum));
    assertEquals(10f, device.voices().getFirst().params().velocityX());
    frame(() -> {});
    frame(() -> {});
    assertEquals(1, sound.activeLoopVoices());
    sound.stopLoopingSound(5);
    assertEquals(0, sound.activeLoopVoices());
    assertTrue(device.voices().isEmpty());

    frame(() -> sound.addRealLoopingSound(6, NEAR, Vec3f.ZERO, hum));
    sound.clearLoopingSounds(true);
    sound.update(16);
    assertEquals(0, sound.activeLoopVoices());
    assertTrue(device.voices().isEmpty());
  }

  @Test
  void changingTheSoundRestartsTheLoop() {
    frame(() -> sound.addLoopingSound(5, NEAR, Vec3f.ZERO, hum));
    int first = device.voices().getFirst().id();
    frame(() -> sound.addLoopingSound(5, NEAR, Vec3f.ZERO, buzz));
    int second = device.voices().getFirst().id();
    assertNotEquals(first, second);
    assertEquals(1, device.voices().size());
  }

  @Test
  void loopsFollowTheirSubmittedOrigin() {
    frame(() -> sound.addLoopingSound(5, new Vec3f(0, -50, 0), Vec3f.ZERO, hum));
    VoiceParams right = device.voices().getFirst().params();
    assertTrue(right.right() > right.left());
    frame(() -> sound.addLoopingSound(5, new Vec3f(0, 50, 0), Vec3f.ZERO, hum));
    VoiceParams left = device.voices().getFirst().params();
    assertTrue(left.left() > left.right());
  }

  @Test
  void loopsOutOfEarshotHoldNoVoiceAndRejoinInPhase() {
    frame(() -> sound.addLoopingSound(5, new Vec3f(2000, 0, 0), Vec3f.ZERO, hum));
    assertEquals(0, sound.activeLoopVoices());
    for (int i = 0; i < 4; i++)
      frame(() -> sound.addLoopingSound(5, new Vec3f(2000, 0, 0), Vec3f.ZERO, hum));
    // 5 updates of 16 ms have passed; the first audible update adds another 16 ms.
    frame(() -> sound.addLoopingSound(5, NEAR, Vec3f.ZERO, hum));
    long time = sound.timeMillis();
    assertEquals(96, time);
    // 96 ms at 11025 Hz is 1058 frames; the 1000-frame loop is 58 frames into its second pass.
    assertEquals(58, device.voices().getFirst().startFrame());
  }

  @Test
  void theLoudestLoopsWinWhenOverBudget() {
    frame(
        () -> {
          sound.addLoopingSound(1, new Vec3f(900, 0, 0), Vec3f.ZERO, hum);
          sound.addLoopingSound(2, new Vec3f(100, 0, 0), Vec3f.ZERO, hum);
          sound.addLoopingSound(3, new Vec3f(500, 0, 0), Vec3f.ZERO, hum);
        });
    assertEquals(2, sound.activeLoopVoices());
    for (NullAudioDevice.VoiceState voice : device.voices())
      assertTrue(voice.params().x() < 900, "the farthest loop is dropped");
  }

  @Test
  void aMissingLoopSoundIsIgnored() {
    int missing = sound.registerSound("sound/nothing.wav", false);
    try (var warnings = new TestSounds.Warnings()) {
      frame(() -> sound.addLoopingSound(5, NEAR, Vec3f.ZERO, missing));
      assertEquals(0, sound.activeLoopVoices());
      assertEquals(1, warnings.count("sound/nothing.wav"));
    }
  }
}
