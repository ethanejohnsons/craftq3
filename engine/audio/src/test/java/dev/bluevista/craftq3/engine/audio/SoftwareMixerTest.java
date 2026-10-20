package dev.bluevista.craftq3.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SoftwareMixerTest {
  private static VoiceParams ears(float left, float right) {
    VoiceParams params = new VoiceParams();
    params.setSpatialised(Math.max(left, right), left, right, 0, 0, 0);
    return params;
  }

  private static short[] ramp(int frames) {
    short[] samples = new short[frames];
    for (int i = 0; i < frames; i++) samples[i] = (short) (i * 100);
    return samples;
  }

  @Test
  void aVoiceAtTheOutputRatePlaysExactlyOnce() {
    var mixer = new SoftwareMixer(22050, 4);
    int buffer = mixer.createBuffer(new PcmBuffer(22050, 1, ramp(10)));
    int voice = mixer.startVoice(buffer, false, 0, ears(1, 1));
    short[] out = new short[12 * 2];
    mixer.mix(out, 12);
    for (int i = 0; i < 10; i++) assertEquals(i * 100, out[i * 2]);
    assertEquals(0, out[20]);
    assertFalse(mixer.voicePlaying(voice));
    mixer.stopVoice(voice);
    assertFalse(mixer.voicePlaying(voice));
  }

  @Test
  void lowerRatesAreResampledByInterpolation() {
    var mixer = new SoftwareMixer(22050, 4);
    int buffer = mixer.createBuffer(new PcmBuffer(11025, 1, ramp(10)));
    int voice = mixer.startVoice(buffer, false, 0, ears(1, 1));
    short[] out = new short[20 * 2];
    mixer.mix(out, 19);
    for (int i = 0; i < 19; i++) assertEquals(i * 50, out[i * 2], "frame " + i);
    assertTrue(mixer.voicePlaying(voice));
    mixer.mix(out, 1);
    assertFalse(mixer.voicePlaying(voice), "10 frames at half rate last 20 output frames");
  }

  @Test
  void higherRatesAreDecimated() {
    var mixer = new SoftwareMixer(11025, 4);
    int buffer = mixer.createBuffer(new PcmBuffer(22050, 1, ramp(10)));
    mixer.startVoice(buffer, false, 0, ears(1, 1));
    short[] out = new short[5 * 2];
    mixer.mix(out, 5);
    assertEquals(0, out[0]);
    assertEquals(200, out[2]);
    assertEquals(800, out[8]);
  }

  @Test
  void earGainsPan() {
    var mixer = new SoftwareMixer(22050, 4);
    int buffer = mixer.createBuffer(new PcmBuffer(22050, 1, new short[] {1000, 1000}));
    mixer.startVoice(buffer, false, 0, ears(0.25f, 1f));
    short[] out = new short[2 * 2];
    mixer.mix(out, 2);
    assertEquals(250, out[0]);
    assertEquals(1000, out[1]);
  }

  @Test
  void stereoBuffersKeepTheirChannels() {
    var mixer = new SoftwareMixer(22050, 4);
    int buffer = mixer.createBuffer(new PcmBuffer(22050, 2, new short[] {100, -100, 200, -200}));
    mixer.startVoice(buffer, false, 0, ears(1, 1));
    short[] out = new short[2 * 2];
    mixer.mix(out, 2);
    assertEquals(100, out[0]);
    assertEquals(-100, out[1]);
    assertEquals(200, out[2]);
    assertEquals(-200, out[3]);
  }

  @Test
  void mixedVoicesClipToSixteenBits() {
    var mixer = new SoftwareMixer(22050, 4);
    int loud = mixer.createBuffer(new PcmBuffer(22050, 1, new short[] {30000, -30000}));
    mixer.startVoice(loud, false, 0, ears(1, 1));
    mixer.startVoice(loud, false, 0, ears(1, 1));
    short[] out = new short[2 * 2];
    mixer.mix(out, 2);
    assertEquals(Short.MAX_VALUE, out[0]);
    assertEquals(Short.MIN_VALUE, out[2]);
  }

  @Test
  void loopingVoicesWrapFromTheirStartFrame() {
    var mixer = new SoftwareMixer(22050, 4);
    int buffer = mixer.createBuffer(new PcmBuffer(22050, 1, ramp(4)));
    int voice = mixer.startVoice(buffer, true, 6, ears(1, 1));
    short[] out = new short[10 * 2];
    mixer.mix(out, 10);
    short[] left = new short[10];
    for (int i = 0; i < 10; i++) left[i] = out[i * 2];
    // Start frame 6 of a 4-frame loop is frame 2.
    assertEquals(
        Arrays.toString(new short[] {200, 300, 0, 100, 200, 300, 0, 100, 200, 300}),
        Arrays.toString(left));
    assertTrue(mixer.voicePlaying(voice));
  }

  @Test
  void voicesRunOutAndIdentifiersGoStale() {
    var mixer = new SoftwareMixer(22050, 2);
    int buffer = mixer.createBuffer(new PcmBuffer(22050, 1, ramp(4)));
    int a = mixer.startVoice(buffer, true, 0, ears(1, 1));
    int b = mixer.startVoice(buffer, true, 0, ears(1, 1));
    assertEquals(0, mixer.startVoice(buffer, true, 0, ears(1, 1)));
    mixer.stopVoice(a);
    int c = mixer.startVoice(buffer, true, 0, ears(1, 1));
    assertTrue(c != 0 && c != a);
    assertFalse(mixer.voicePlaying(a), "a stale identifier no longer names the reused slot");
    assertTrue(mixer.voicePlaying(b));
    assertEquals(2, mixer.playingVoices());
  }

  @Test
  void streamsResampleAcrossChunkBoundariesAndRecycleChunks() {
    var mixer = new SoftwareMixer(22050, 2);
    int stream = mixer.openStream(11025, 1);
    short[] block = new short[SoftwareMixer.CHUNK_FRAMES + 10];
    for (int i = 0; i < block.length; i++) block[i] = (short) i;
    mixer.queueStream(stream, block, 0, block.length);
    assertEquals(block.length, mixer.streamQueuedFrames(stream));
    assertEquals(2, mixer.chunksAllocated());
    short[] out = new short[(SoftwareMixer.CHUNK_FRAMES * 2 + 4) * 2];
    mixer.mix(out, SoftwareMixer.CHUNK_FRAMES * 2 + 4);
    // The interpolated frame between the chunks' last and first samples is continuous.
    int boundary = (SoftwareMixer.CHUNK_FRAMES - 1) * 2 + 1;
    assertEquals(SoftwareMixer.CHUNK_FRAMES - 1, out[(boundary - 1) * 2]);
    assertEquals(Math.round(SoftwareMixer.CHUNK_FRAMES - 0.5f), out[boundary * 2], "boundary");
    assertEquals(SoftwareMixer.CHUNK_FRAMES, out[(boundary + 1) * 2]);
    assertEquals(SoftwareMixer.CHUNK_FRAMES + 2, mixer.streamPlayedFrames(stream));
    mixer.queueStream(stream, block, 0, SoftwareMixer.CHUNK_FRAMES);
    assertEquals(2, mixer.chunksAllocated(), "the drained chunk is reused");
    mixer.closeStream(stream);
    assertEquals(0, mixer.streamQueuedFrames(stream));
  }

  @Test
  void invalidRequestsAreRejected() {
    var mixer = new SoftwareMixer(22050, 2);
    assertThrows(IllegalArgumentException.class, () -> new SoftwareMixer(10, 2));
    assertThrows(IllegalArgumentException.class, () -> new SoftwareMixer(22050, 0));
    assertThrows(IllegalArgumentException.class, () -> mixer.mix(new short[2], 2));
    assertThrows(
        IllegalArgumentException.class, () -> mixer.startVoice(99, false, 0, new VoiceParams()));
  }
}
