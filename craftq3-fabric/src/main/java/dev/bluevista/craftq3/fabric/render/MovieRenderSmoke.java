package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.image.Q3Image;
import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.client.video.RoqPlayback;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.render.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Original RoQ frame readback and dynamic GPU texture lifetime; intentionally silent. */
final class MovieRenderSmoke implements AutoCloseable {
  private final Pk3FileSystem fs;
  private final RoqPlayback playback;
  private final Blaze3dRenderBackend backend;
  private Q3Image pixels;
  private int stage, time, number = -1, held;
  private boolean pending, finished, failed;
  private long heldUploads;

  static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && System.getProperty("craftq3.videoInstallation") != null;
  }

  MovieRenderSmoke(Blaze3dRenderBackend backend) {
    this.backend = backend;
    try {
      fs = Pk3FileSystem.mount(Path.of(System.getProperty("craftq3.videoInstallation")), "baseq3");
      try {
        playback =
            new RoqPlayback(
                fs,
                new VirtualPath("video/idlogo.roq"),
                RoqPlayback.Mode.HOLD,
                true,
                new RoqPlayback.AudioSink() {
                  public void begin(long cycle, long start, int units) {
                    throw new AssertionError("Silent movie acquired audio");
                  }

                  public void samples(RoqDecoder.Audio audio) {
                    throw new AssertionError("Silent movie submitted audio");
                  }

                  public void end() {
                    throw new AssertionError("Silent movie released audio");
                  }
                });
      } catch (IOException | RuntimeException failure) {
        fs.close();
        throw failure;
      }
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot open original movie QA", failure);
    }
  }

  void render(Minecraft client, RenderScene scene) {
    int width = client.getWindow().getWidth(), height = client.getWindow().getHeight();
    if (width < 600 || height < 400) throw new IllegalStateException("Movie QA viewport too small");
    if (stage == 3 || finished) {
      if (!finished) {
        backend.render(scene, new CgameFrame(List.of(), SceneAssets.EMPTY), width, height);
        var diagnostics = backend.movieDiagnostics();
        if (diagnostics.textures() != 0
            || diagnostics.bytes() != 0
            || diagnostics.allocations() != 2)
          throw new IllegalStateException("Movie GPU images survived removal: " + diagnostics);
        finished = true;
        CraftQ3Client.LOGGER.info(
            "CraftQ3 movie render PASS: original-frames=90,180 rgba-readback=true order=true"
                + " reuse=true hold=true resize=true removal=true {}",
            diagnostics);
      }
      backend.render(
          scene,
          new CgameFrame(
              List.of(new CgameFrame.Image(18, 0, 0, 64, 64, pixels)), SceneAssets.EMPTY),
          width,
          height);
      return;
    }
    try {
      if (stage < 2) {
        int target = stage == 0 ? 3000 : 6000;
        time = Math.min(time + 16, target);
        playback.advance(time);
        var frame = playback.video().orElseThrow();
        if (frame.number() != number) {
          number = frame.number();
          pixels = frame.image();
        }
      } else if (pixels.width() != 2) {
        pixels =
            new Q3Image(
                2,
                2,
                new byte[] {
                  (byte) 255,
                  0,
                  0,
                  (byte) 255,
                  0,
                  (byte) 255,
                  0,
                  (byte) 255,
                  0,
                  0,
                  (byte) 255,
                  (byte) 255,
                  (byte) 255,
                  (byte) 255,
                  (byte) 255,
                  (byte) 255
                });
      }
      int x = (width - 512) / 2, y = (height - 256) / 2;
      int drawWidth = stage == 2 ? 64 : 512, drawHeight = stage == 2 ? 64 : 256;
      var movie = new CgameFrame.Image(17, x, y, drawWidth, drawHeight, pixels);
      var background =
          new CgameFrame.Quad(0, 0, width, height, 0, 0, 1, 1, "$whiteimage", 0x0000ffff);
      var overlay =
          new CgameFrame.Quad(
              x + (stage == 2 ? -20 : 0), y, 16, 16, 0, 0, 1, 1, "$whiteimage", 0xff0000ff);
      backend.render(
          scene,
          new CgameFrame(List.of(background, movie, overlay), SceneAssets.EMPTY, time),
          width,
          height);
      var stats = backend.movieDiagnostics();
      if (stats.textures() != 1
          || stats.bytes() != (long) pixels.width() * pixels.height() * 4
          || stats.allocations() != (stage == 2 ? 2 : 1))
        throw new IllegalStateException("Movie texture was not reused/bounded: " + stats);
      boolean ready = stage == 2 || number == (stage == 0 ? 90 : 180);
      if (ready && !pending) {
        if (held++ == 0) heldUploads = stats.uploads();
        if (stats.uploads() != heldUploads)
          throw new IllegalStateException("Held frame was reuploaded");
        if (held == 30) capture(client, x, y, pixels);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("Movie presentation failed", failure);
    }
  }

  private void capture(Minecraft client, int x, int y, Q3Image expected) {
    pending = true;
    int phase = stage;
    Screenshot.takeScreenshot(
        client.gameRenderer.mainRenderTarget(),
        image -> {
          try (image) {
            if ((image.getPixel(x - 8, y - 8) & 0xffffff) != 0x0000ff
                || (image.getPixel(x + (phase == 2 ? -16 : 4), y + 4) & 0xffffff) != 0xff0000)
              throw new IllegalStateException("Movie command/HUD submission order changed");
            int checked = 0, maximumError = 0;
            if (phase < 2) {
              for (int py = 1; py < 255; py++)
                for (int px = 1; px < 511; px++) {
                  if (px < 17 && py < 17) continue;
                  int actual = image.getPixel(x + px, y + py),
                      wanted = expected.rgbaAt(px, py) >>> 8;
                  for (int shift : new int[] {0, 8, 16})
                    maximumError =
                        Math.max(
                            maximumError,
                            Math.abs(((actual >>> shift) & 255) - ((wanted >>> shift) & 255)));
                  checked++;
                }
              if (maximumError > 1)
                throw new IllegalStateException("Movie GPU/CPU pixels differ by " + maximumError);
            } else {
              int[] colors = {0xff0000, 0x00ff00, 0x0000ff, 0xffffff};
              int[][] positions = {{8, 8}, {56, 8}, {8, 56}, {56, 56}};
              for (int i = 0; i < 4; i++) {
                if ((image.getPixel(x + positions[i][0], y + positions[i][1]) & 0xffffff)
                    != colors[i])
                  throw new IllegalStateException(
                      "Resized cinematic orientation/clamp mismatch at " + i);
                checked++;
              }
            }
            image.writeToFile(
                Path.of(
                    "/tmp/craftq3-movie-render-"
                        + (phase == 2 ? "resize" : phase == 0 ? "90" : "180")
                        + ".png"));
            CraftQ3Client.LOGGER.info(
                "CraftQ3 movie GPU readback: phase={} pixels={} maximumError={}",
                phase,
                checked,
                maximumError);
            client.execute(
                () -> {
                  stage++;
                  held = 0;
                  pending = false;
                });
          } catch (IOException | RuntimeException failure) {
            CraftQ3Client.LOGGER.error("CraftQ3 movie render FAIL", failure);
            client.execute(
                () -> {
                  failed = finished = true;
                  client.gui.setScreen(null);
                  client.stop();
                });
          }
        });
  }

  boolean finished() {
    return finished;
  }

  @Override
  public void close() {
    try {
      playback.close();
      fs.close();
    } catch (IOException failure) {
      throw new IllegalStateException("Movie cleanup failed", failure);
    }
    if (!failed && finished && backend.movieDiagnostics().textures() != 0)
      throw new IllegalStateException("Movie texture survived backend close");
    if (!failed && finished)
      CraftQ3Client.LOGGER.info(
          "CraftQ3 movie close PASS: active-image-released=true {}", backend.movieDiagnostics());
  }
}
