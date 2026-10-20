package dev.bluevista.craftq3.engine.audio;

import static dev.bluevista.craftq3.engine.audio.TestSounds.FORWARD;
import static dev.bluevista.craftq3.engine.audio.TestSounds.LEFT;
import static dev.bluevista.craftq3.engine.audio.TestSounds.UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelRulesTest {
  private static final int LISTENER = 3;

  private MemoryAssets assets;
  private NullAudioDevice device;
  private SoundSystem sound;
  private int shot;
  private int hum;
  private int beep;

  @BeforeEach
  void setUp() {
    assets = new MemoryAssets();
    // 22050 frames at 22050 Hz: each sound lasts one second.
    for (String name : List.of("shot", "hum", "beep"))
      assets.put("sound/" + name + ".wav", TestSounds.constant(22050, 22050, 1000));
    device = new NullAudioDevice(256, true);
    sound = new SoundSystem(assets, device, new SoundSystem.Limits(8, 8));
    sound.claimThread();
    sound.respatialize(LISTENER, Vec3f.ZERO, FORWARD, LEFT, UP, false);
    shot = sound.registerSound("sound/shot.wav", false);
    hum = sound.registerSound("sound/hum.wav", false);
    beep = sound.registerSound("sound/beep.wav", false);
  }

  private Vec3f at(float x) {
    return new Vec3f(x, 0, 0);
  }

  @Test
  void autoChannelAlwaysTakesAFreeVoice() {
    sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
    sound.startSound(at(10), 5, SoundChannel.AUTO, hum);
    sound.startSound(at(10), 5, SoundChannel.AUTO, beep);
    assertEquals(3, sound.activeVoices());
    assertEquals(3, device.voices().size());
    assertTrue(device.calls("stopVoice").isEmpty());
  }

  @Test
  void sameEntityAndChannelReplacesThePreviousSound() {
    sound.startSound(at(10), 5, SoundChannel.WEAPON, shot);
    int first = device.voices().getFirst().id();
    sound.startSound(at(10), 5, SoundChannel.WEAPON, hum);
    assertEquals(1, sound.activeVoices());
    assertEquals(List.of("stopVoice " + first), device.calls("stopVoice"));
    assertEquals(device.buffer(device.voices().getFirst().buffer()).frames(), 22050);
  }

  @Test
  void otherEntitiesAndChannelsAreNotReplaced() {
    sound.startSound(at(10), 5, SoundChannel.WEAPON, shot);
    sound.startSound(at(10), 6, SoundChannel.WEAPON, shot);
    sound.startSound(at(10), 5, SoundChannel.VOICE, hum); // same sound would hit the repeat window
    assertEquals(3, sound.activeVoices());
  }

  @Test
  void aRepeatWithinFiftyMillisecondsIsIgnored() {
    sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
    sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
    assertEquals(1, sound.activeVoices());
    sound.update(49);
    sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
    assertEquals(1, sound.activeVoices());
    sound.update(1);
    sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
    assertEquals(2, sound.activeVoices());
    // Another entity is not affected by the window.
    sound.startSound(at(10), 6, SoundChannel.AUTO, shot);
    assertEquals(3, sound.activeVoices());
  }

  @Test
  void anEntityPlaysAtMostFourCopiesOfOneSound() {
    for (int i = 0; i < 6; i++) {
      sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
      sound.update(60);
    }
    assertEquals(SoundSystem.MAX_SAME_SOUND, sound.activeVoices());
  }

  @Test
  void theListenerPlaysUpToEightCopies() {
    var big = new SoundSystem(assets, device, new SoundSystem.Limits(16, 0));
    big.respatialize(LISTENER, Vec3f.ZERO, FORWARD, LEFT, UP, false);
    int handle = big.registerSound("sound/shot.wav", false);
    for (int i = 0; i < 10; i++) {
      big.startLocalSound(handle, SoundChannel.AUTO);
      big.update(60);
    }
    assertEquals(SoundSystem.MAX_SAME_SOUND_LISTENER, big.activeVoices());
  }

  @Test
  void fullVoicesStealTheOldestOfTheSameEntityFirst() {
    var small = new SoundSystem(assets, device, new SoundSystem.Limits(3, 0));
    small.respatialize(LISTENER, Vec3f.ZERO, FORWARD, LEFT, UP, false);
    int a = small.registerSound("sound/shot.wav", false);
    int b = small.registerSound("sound/hum.wav", false);
    int c = small.registerSound("sound/beep.wav", false);
    small.startSound(at(10), 7, SoundChannel.AUTO, a); // oldest, other entity
    small.startSound(at(10), 5, SoundChannel.AUTO, a);
    small.startSound(at(10), 5, SoundChannel.AUTO, b);
    List<NullAudioDevice.VoiceState> before = device.voices();
    device.clearCalls();
    small.startSound(at(10), 5, SoundChannel.AUTO, c);
    assertEquals(List.of("stopVoice " + before.get(1).id()), device.calls("stopVoice"));
    assertEquals(3, small.activeVoices());
    // A different entity takes the oldest voice not heard by the listener.
    device.clearCalls();
    small.startSound(at(10), 9, SoundChannel.AUTO, c);
    assertEquals(List.of("stopVoice " + before.get(0).id()), device.calls("stopVoice"));
  }

  @Test
  void announcerAndListenerVoicesAreNotStolenByOtherEntities() {
    var small = new SoundSystem(assets, device, new SoundSystem.Limits(2, 0));
    small.respatialize(LISTENER, Vec3f.ZERO, FORWARD, LEFT, UP, false);
    int a = small.registerSound("sound/shot.wav", false);
    int b = small.registerSound("sound/hum.wav", false);
    small.startSound(null, 1, SoundChannel.ANNOUNCER, a);
    small.startLocalSound(b, SoundChannel.LOCAL_SOUND);
    device.clearCalls();
    small.startSound(at(10), 9, SoundChannel.AUTO, a);
    assertTrue(device.calls("stopVoice").isEmpty());
    assertTrue(device.calls("startVoice").isEmpty());
    assertEquals(2, small.activeVoices());
  }

  @Test
  void finishedVoicesAreReleasedAtTheNextUpdate() {
    sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
    int id = device.voices().getFirst().id();
    device.advance(999);
    sound.update(999);
    assertEquals(1, sound.activeVoices());
    device.advance(1);
    sound.update(1);
    assertEquals(0, sound.activeVoices());
    assertEquals(List.of("stopVoice " + id), device.calls("stopVoice"));
    assertTrue(device.voices().isEmpty());
  }

  @Test
  void entitySoundsFollowTheirEntity() {
    sound.updateEntityPosition(5, new Vec3f(0, -100, 0));
    sound.startSound(null, 5, SoundChannel.BODY, shot);
    VoiceParams start = device.voices().getFirst().params();
    assertEquals(-100f, start.y());
    assertTrue(start.right() > start.left());
    sound.updateEntityPosition(5, new Vec3f(0, 100, 0));
    sound.update(16);
    VoiceParams moved = device.voices().getFirst().params();
    assertEquals(100f, moved.y());
    assertTrue(moved.left() > moved.right());
  }

  @Test
  void fixedOriginSoundsIgnoreEntityMovement() {
    sound.updateEntityPosition(5, new Vec3f(500, 0, 0));
    sound.startSound(new Vec3f(705, 0, 0), 5, SoundChannel.AUTO, shot);
    sound.updateEntityPosition(5, new Vec3f(0, 0, 0));
    sound.update(16);
    VoiceParams params = device.voices().getFirst().params();
    assertEquals(705f, params.x());
    assertEquals(0.5f * SoundSystem.DEFAULT_VOLUME, params.gain(), 1e-5f);
  }

  @Test
  void listenerEntityAndLocalSoundsAreUnattenuated() {
    sound.setVolume(1f);
    sound.startSound(new Vec3f(5000, 0, 0), LISTENER, SoundChannel.VOICE, shot);
    sound.startLocalSound(hum, SoundChannel.LOCAL_SOUND);
    for (NullAudioDevice.VoiceState voice : device.voices()) {
      assertTrue(voice.params().relative());
      assertEquals(1f, voice.params().left());
      assertEquals(1f, voice.params().right());
    }
    sound.startSound(new Vec3f(5000, 0, 0), 4, SoundChannel.VOICE, beep);
    VoiceParams far = device.voices().getLast().params();
    assertFalse(far.relative());
    assertEquals(0f, far.gain());
  }

  @Test
  void volumeScalesEverySoundEffect() {
    sound.setVolume(0.5f);
    sound.startLocalSound(hum, SoundChannel.LOCAL_SOUND);
    assertEquals(0.5f, device.voices().getFirst().params().gain());
    sound.setVolume(2f);
    sound.update(16);
    assertEquals(1f, device.voices().getFirst().params().gain());
  }

  @Test
  void stopAllSoundsStopsEverything() {
    sound.startSound(at(10), 5, SoundChannel.AUTO, shot);
    sound.addLoopingSound(6, at(20), Vec3f.ZERO, hum);
    sound.update(16);
    assertEquals(2, device.voices().size());
    sound.stopAllSounds();
    assertTrue(device.voices().isEmpty());
    assertEquals(0, sound.activeVoices());
    assertEquals(0, sound.activeLoopVoices());
  }

  @Test
  void invalidArgumentsDegrade() {
    try (var warnings = new TestSounds.Warnings()) {
      sound.startSound(at(0), -1, SoundChannel.AUTO, shot);
      sound.startSound(at(0), SoundSystem.MAX_ENTITIES, SoundChannel.AUTO, shot);
      sound.startSound(at(0), 1, SoundChannel.AUTO, 999);
      sound.startSound(at(0), 1, SoundChannel.AUTO, 0);
      sound.startLocalSound(-4, SoundChannel.AUTO);
      assertEquals(0, sound.activeVoices());
      assertTrue(warnings.count("out of range") >= 2);
    }
  }

  @Test
  void deviceListenerIsSetEveryUpdate() {
    sound.respatialize(9, new Vec3f(1, 2, 3), FORWARD, LEFT, UP, true);
    sound.update(16);
    assertEquals(9, device.listener().entity());
    assertTrue(device.listener().inWater());
    assertEquals(1, device.commits());
  }
}
