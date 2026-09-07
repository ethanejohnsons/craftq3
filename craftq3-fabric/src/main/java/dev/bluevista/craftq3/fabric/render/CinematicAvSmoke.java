package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.video.Cinematics;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Development-only synchronized original RoQ playback through the production cinematic service. */
final class CinematicAvSmoke implements AutoCloseable {
  private final Pk3FileSystem fs;
  private final MinecraftAudioBackend audio;
  private final Cinematics movies;
  private final Blaze3dRenderBackend renderer;
  private final long start = System.nanoTime();
  private final List<String> errors = new ArrayList<>();
  private int handle, phase, previousFrame = -1, uniqueFrames;
  private long maxSkew, consumed;
  private boolean sawAudioEnd, captured, finished;

  static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && System.getProperty("craftq3.cinematicInstallation") != null;
  }

  CinematicAvSmoke(Minecraft client, Blaze3dRenderBackend renderer) {
    this.renderer = renderer;
    try {
      fs =
          Pk3FileSystem.mount(
              Path.of(System.getProperty("craftq3.cinematicInstallation")), "baseq3");
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot open movie QA", failure);
    }
    audio = new MinecraftAudioBackend(client);
    audio.volume(.3f);
    movies =
        new Cinematics(
            fs,
            audio,
            System::nanoTime,
            message -> {
              errors.add(message);
              CraftQ3Client.LOGGER.error("Cinematic service: {}", message);
            });
    handle = movies.play("idlogo", 0, 0, 640, 480, 0, 0);
    if (handle < 0) throw new IllegalStateException("Movie startup failed: " + errors);
  }

  void render(Minecraft client, RenderScene scene) {
    if (finished) return;
    int width = client.getWindow().getWidth(), height = client.getWindow().getHeight();
    try {
      int time = (int) ((System.nanoTime() - start) / 1_000_000);
      if (time > 35000) throw new IllegalStateException("Cinematic AV QA timed out");
      if (!errors.isEmpty() || audio.diagnostics().failures() != 0)
        throw new IllegalStateException(
            "Cinematic AV failure: " + errors + " " + audio.diagnostics());
      if (phase < 2) {
        int status = movies.run(handle, time);
        if (status == Cinematics.EOF) {
          if (phase != 0 || uniqueFrames < 180 || consumed != 129150 || !sawAudioEnd)
            throw new IllegalStateException(
                "Incomplete original movie: frames="
                    + uniqueFrames
                    + " samples="
                    + consumed
                    + " errors="
                    + errors
                    + " audio="
                    + audio.diagnostics());
          CraftQ3Client.LOGGER.info(
              "CraftQ3 cinematic AV EOF: frames={} samples={} maxClockSkewMs={}",
              uniqueFrames,
              consumed,
              maxSkew);
          handle = movies.play("idlogo", 0, 0, 640, 480, Cinematics.LOOP, time);
          if (handle < 0) throw new IllegalStateException("Loop startup failed: " + errors);
          phase = 1;
        }
        var info = movies.info(handle).orElseThrow();
        if (info.audioStart().isPresent()) {
          long nativeMillis =
              Math.max(0, (System.nanoTime() - info.audioStart().getAsLong()) / 1_000_000);
          long localMillis = info.milliseconds() - info.cycle() * 7000;
          maxSkew = Math.max(maxSkew, Math.abs(localMillis - nativeMillis));
          if (maxSkew > 50)
            throw new IllegalStateException("Cinematic/native clock skew=" + maxSkew);
          if (info.frame() != Math.min(209, (int) (localMillis * 30 / 1000)))
            throw new IllegalStateException("Cinematic frame does not match audio-anchored clock");
        }
        if (phase == 0) {
          if (info.frame() != previousFrame) {
            uniqueFrames++;
            previousFrame = info.frame();
          }
          consumed = Math.max(consumed, info.consumedSamples());
          sawAudioEnd |= info.audioClosed();
        }
        var image = movies.draw(handle, width, height).orElseThrow();
        renderer.render(
            scene, new CgameFrame(List.of(image), SceneAssets.EMPTY, time), width, height);
        if (phase == 0 && info.frame() >= 90 && !captured) {
          captured = true;
          Screenshot.takeScreenshot(
              client.gameRenderer.mainRenderTarget(),
              screenshot -> {
                try (screenshot) {
                  screenshot.writeToFile(Path.of("/tmp/craftq3-cinematic-av.png"));
                } catch (IOException failure) {
                  errors.add("Movie screenshot failed: " + failure.getMessage());
                }
              });
        }
        audio.beginFrame();
        audio.endFrame(
            new AudioBackend.Listener(
                scene.camera().origin(), new Vec3(1, 0, 0), new Vec3(0, 0, 1)));
        if (phase == 1
            && info.cycle() == 1
            && info.audioStart().isPresent()
            && info.milliseconds() >= 7300) {
          movies.stop(handle);
          if (audio.diagnostics().activeVoices() != 0 || movies.active() != 0)
            throw new IllegalStateException("Loop cancellation retained voices/handles");
          phase = 2;
        }
      } else if (phase == 2) {
        int held = movies.play("idlogo", 0, 0, 640, 480, Cinematics.HOLD | Cinematics.SILENT, 0);
        int status = Cinematics.PLAY;
        for (int i = 0; i < 8 && status == Cinematics.PLAY; i++) status = movies.run(held, 8000);
        if (status != Cinematics.IDLE || movies.info(held).orElseThrow().frame() != 209)
          throw new IllegalStateException("Original movie hold failed");
        movies.clear();
        renderer.render(scene, new CgameFrame(List.of(), SceneAssets.EMPTY), width, height);
        if (renderer.movieDiagnostics().textures() != 0)
          throw new IllegalStateException("Movie GPU resource survived EOF");
        audio.close();
        phase = 3;
      } else if (audio.closeCompletion().isDone()) {
        if (audio.diagnostics().activeVoices() != 0
            || audio.diagnostics().pendingVoices() != 0
            || audio.diagnostics().startedVoices() != 3
            || !errors.isEmpty())
          throw new IllegalStateException("Cinematic close failed: " + audio.diagnostics());
        finished = true;
        CraftQ3Client.LOGGER.info(
            "CraftQ3 cinematic AV PASS: original-roq=true native-audio=true frames={} samples={}"
                + " maxClockSkewMs={} eof=true loop=true hold=true cancel=true gpu-release=true"
                + " close=true {}",
            uniqueFrames,
            consumed,
            maxSkew,
            audio.diagnostics());
      }
    } catch (RuntimeException failure) {
      finished = true;
      CraftQ3Client.LOGGER.error("CraftQ3 cinematic AV FAIL", failure);
      client.execute(
          () -> {
            client.gui.setScreen(null);
            client.stop();
          });
    }
  }

  boolean finished() {
    return finished;
  }

  @Override
  public void close() {
    movies.close();
    audio.close();
    try {
      fs.close();
    } catch (IOException failure) {
      throw new IllegalStateException("Movie VFS cleanup failed", failure);
    }
  }
}
