package dev.bluevista.craftq3.fabric.audio;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.mixin.SoundManagerAccessor;
import dev.bluevista.craftq3.platform.audio.*;
import dev.bluevista.craftq3.render.RenderScene;
import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.client.Minecraft;

/** Development-only original movie PCM streaming and native lifecycle audit. */
final class StreamingAudioSmoke {
  private final Minecraft client;
  private final MinecraftAudioBackend backend;
  private final byte[] pcm;
  private final PcmStream.Format format;
  private final long start = System.nanoTime();
  private PcmQueue queue;
  private long voice;
  private int phase, supplied;
  private double since;
  private boolean finished;

  StreamingAudioSmoke(Minecraft client, MinecraftAudioBackend backend, String installation) {
    this.client = client;
    this.backend = backend;
    try (var fs = Pk3FileSystem.mount(Path.of(installation), "baseq3");
        var decoder = new RoqDecoder(fs.open(new VirtualPath("video/idlogo.roq")))) {
      var bytes = new ByteArrayOutputStream();
      PcmStream.Format found = null;
      long frames = 0;
      for (var event = decoder.next(); event.isPresent(); event = decoder.next()) {
        if (!(event.get() instanceof RoqDecoder.Audio audio)) continue;
        var sound = audio.sound();
        var next = new PcmStream.Format(sound.sampleRate(), sound.channels(), sound.bits());
        if (audio.firstSample() != frames || found != null && !found.equals(next))
          throw new IOException("Discontinuous original movie audio");
        found = next;
        frames += sound.frames();
        bytes.write(sound.pcm());
        if (bytes.size() > 2 * 1024 * 1024) throw new IOException("Movie QA PCM exceeds budget");
      }
      if (found == null || frames != 129150)
        throw new IOException("Unexpected original idlogo PCM");
      format = found;
      pcm = bytes.toByteArray();
    } catch (IOException failure) {
      backend.close();
      throw new IllegalStateException("Cannot load original movie audio QA", failure);
    }
    queue = new PcmQueue(format, format.sampleRate());
    voice = backend.stream(queue, .3f);
    if (voice == 0) throw new IllegalStateException("Movie stream submission failed");
  }

  void tick(RenderScene.Camera camera) {
    if (finished) return;
    double seconds = (System.nanoTime() - start) / 1e9;
    try {
      if (seconds > 20) throw new IllegalStateException("Movie audio QA timeout");
      if (phase < 6) {
        backend.beginFrame();
        backend.endFrame(
            new AudioBackend.Listener(camera.origin(), new Vec3(1, 0, 0), new Vec3(0, 0, 1)));
      }
      var state = backend.diagnostics();
      if (state.failures() != 0) throw new IllegalStateException(state.lastFailure());
      if (phase == 0 && seconds > .2) {
        if (state.pendingVoices() != 1 || state.startedVoices() != 0 || queue.consumedFrames() != 0)
          throw new IllegalStateException("Empty stream allocated/started before prebuffering");
        phase = 1;
        since = seconds;
      }
      if (phase == 1) {
        int frames = pcm.length / format.frameBytes();
        while (supplied < frames && queue.queuedFrames() < format.sampleRate() / 2) {
          int count = Math.min(format.sampleRate() / 20, frames - supplied);
          var sound =
              new PcmSound(
                  format.sampleRate(),
                  format.channels(),
                  format.bits(),
                  Arrays.copyOfRange(
                      pcm,
                      supplied * format.frameBytes(),
                      (supplied + count) * format.frameBytes()));
          if (!queue.offer(supplied, sound))
            throw new IllegalStateException("Unexpected QA backpressure");
          supplied += count;
          if (supplied == frames) queue.finish();
        }
        if (queue.closed() && state.activeVoices() == 0 && state.pendingVoices() == 0) {
          if (queue.consumedFrames() != frames
              || state.activeVoices() != 0
              || state.pendingVoices() != 0
              || state.startedVoices() != 1
              || seconds - since < 5.5
              || seconds - since > 7)
            throw new IllegalStateException("Movie PCM completion/timing mismatch: " + state);
          CraftQ3Client.LOGGER.info(
              "CraftQ3 streaming audio EOF: samples={} seconds={}", frames, seconds - since);
          replacement();
          phase = 2;
        }
      } else if (phase >= 2 && phase <= 5 && state.activeVoices() == 1) {
        if (phase == 2) backend.stop(voice);
        else if (phase == 3)
          ((SoundManagerAccessor) client.getSoundManager()).craftq3$soundEngine().stopAll();
        else if (phase == 4) backend.resetAssets();
        else backend.close();
        if (!queue.closed() || backend.diagnostics().activeVoices() != 0)
          throw new IllegalStateException("Stream survived cleanup phase " + phase);
        phase++;
        if (phase < 6) replacement();
      } else if (phase == 6 && backend.closeCompletion().isDone()) {
        if (backend.bufferedSounds() != 0 || backend.diagnostics().registeredSounds() != 0)
          throw new IllegalStateException("Streaming registered or retained static PCM assets");
        CraftQ3Client.LOGGER.info(
            "CraftQ3 streaming audio PASS: original-roq=true samples=129150 prebuffer=true"
                + " refills=true eof=true stop=true host-reset=true asset-reset=true close=true {}",
            backend.diagnostics());
        finished = true;
      }
    } catch (RuntimeException failure) {
      CraftQ3Client.LOGGER.error("CraftQ3 streaming audio FAIL", failure);
      backend.close();
      finished = true;
    }
  }

  private void replacement() {
    queue = new PcmQueue(format, format.sampleRate());
    int bytes = format.sampleRate() / 2 * format.frameBytes();
    if (!queue.offer(
        0,
        new PcmSound(
            format.sampleRate(), format.channels(), format.bits(), Arrays.copyOf(pcm, bytes))))
      throw new IllegalStateException("Replacement stream was rejected");
    queue.finish();
    voice = backend.stream(queue, 0);
    if (voice == 0) throw new IllegalStateException("Replacement stream submission failed");
  }

  boolean finished() {
    return finished;
  }
}
