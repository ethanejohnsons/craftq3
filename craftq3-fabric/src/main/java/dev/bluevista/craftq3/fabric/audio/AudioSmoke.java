package dev.bluevista.craftq3.fabric.audio;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.mixin.SoundManagerAccessor;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.RenderScene;
import java.util.Arrays;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Development-only, silent resource/lifecycle proof; never emits test tones or changes options. */
public final class AudioSmoke implements AutoCloseable {
  private final Minecraft client;
  private final MinecraftAudioBackend backend;
  private final long started = System.nanoTime();
  private int phase, mono, stereo;
  private double phaseStart;
  private boolean finished;
  private StreamingAudioSmoke streaming;

  public AudioSmoke(Minecraft client) {
    if (!enabled()) throw new IllegalStateException("Audio smoke is development-only");
    this.client = client;
    backend = new MinecraftAudioBackend(client);
    String movie = System.getProperty("craftq3.audioMovie");
    if (movie != null && !movie.isBlank()) {
      streaming = new StreamingAudioSmoke(client, backend, movie);
      return;
    }
    byte[] silence16 = new byte[22050 * 2 * 4];
    byte[] silence8 = new byte[22050 * 2 * 4];
    Arrays.fill(silence8, (byte) 128);
    mono = backend.register("sound/craftq3/silent_mono.wav", new PcmSound(22050, 1, 16, silence16));
    stereo =
        backend.register("sound/craftq3/silent_stereo.wav", new PcmSound(22050, 2, 8, silence8));
  }

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.audioSmoke");
  }

  public void tick(RenderScene.Camera camera) {
    if (streaming != null) {
      streaming.tick(camera);
      return;
    }
    if (finished) return;
    double seconds = (System.nanoTime() - started) / 1e9;
    try {
      if (seconds > 12)
        throw new IllegalStateException("Audio smoke timed out: " + backend.diagnostics());
      double yaw = Math.toRadians(camera.yaw()), pitch = Math.toRadians(camera.pitch());
      Vec3 forward =
          new Vec3(
              Math.cos(yaw) * Math.cos(pitch), Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch));
      var listener =
          new AudioBackend.Listener(0, camera.origin(), forward, new Vec3(0, 0, 1), false);
      if (phase == 0) {
        if (!backend.diagnostics().available())
          throw new IllegalStateException("Minecraft sound engine unavailable");
        backend.play(local(stereo, 7, 1));
        backend.play(local(stereo, 7, 1)); // Replace the preceding same-channel voice.
        backend.play(local(stereo, 7, AudioBackend.CHAN_AUTO));
        backend.play(local(stereo, 7, AudioBackend.CHAN_AUTO));
        backend.play(
            new AudioBackend.Playback(
                mono,
                8,
                0,
                AudioBackend.Spatial.POSITION,
                camera.origin().add(new Vec3(128, 0, 0)),
                1,
                1));
        backend.play(
            new AudioBackend.Playback(mono, 9, 0, AudioBackend.Spatial.ENTITY, null, 1, 1));
        phase = 1;
        phaseStart = seconds;
      }
      if (phase <= 3) {
        backend.beginFrame();
        backend.updateEntity(9, camera.origin().add(new Vec3(0, 64 + seconds * 32, 0)));
        if (phase == 1 || phase == 3)
          backend.submitLoop(new AudioBackend.Loop(10, mono, camera.origin(), 1, 1));
        backend.endFrame(listener);
      }
      var state = backend.diagnostics();
      if (state.failures() > 0) throw new IllegalStateException(state.lastFailure());
      if (phase == 1 && seconds - phaseStart > .25 && state.activeVoices() == 6) {
        CraftQ3Client.LOGGER.info(
            "CraftQ3 audio smoke PLAY: {} buffers={}", state, backend.bufferedSounds());
        phase = 2;
        phaseStart = seconds;
      } else if (phase == 2 && seconds - phaseStart > .15) {
        if (state.activeLoops() != 0)
          throw new IllegalStateException("Unsubmitted loop was not stopped");
        ((SoundManagerAccessor) client.getSoundManager()).craftq3$soundEngine().stopAll();
        if (backend.bufferedSounds() != 0 || backend.diagnostics().activeVoices() != 0)
          throw new IllegalStateException("Host stopAll did not reclaim Q3 resources");
        backend.play(local(stereo, 7, 1));
        phase = 3;
        phaseStart = seconds;
      } else if (phase == 3 && seconds - phaseStart > .25 && state.activeVoices() == 2) {
        CraftQ3Client.LOGGER.info(
            "CraftQ3 audio smoke RESUME: {} buffers={}", state, backend.bufferedSounds());
        backend.stopAll();
        backend.close();
        phase = 4;
      } else if (phase == 4 && backend.closeCompletion().isDone()) {
        if (backend.bufferedSounds() != 0)
          throw new IllegalStateException("Q3 buffers survived close");
        CraftQ3Client.LOGGER.info(
            "CraftQ3 audio smoke PASS: silent PCM, channel replacement/overlap, position/entity,"
                + " loops, host stopAll/restart and close verified; {}",
            backend.diagnostics());
        finished = true;
      }
    } catch (RuntimeException failure) {
      CraftQ3Client.LOGGER.error("CraftQ3 audio smoke FAIL", failure);
      close();
      finished = true;
    }
  }

  private AudioBackend.Playback local(int sound, int entity, int channel) {
    return new AudioBackend.Playback(
        sound, entity, channel, AudioBackend.Spatial.LOCAL, null, 1, 1);
  }

  public boolean finished() {
    return streaming == null ? finished : streaming.finished();
  }

  @Override
  public void close() {
    backend.close();
  }
}
