package dev.bluevista.craftq3.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.demo.DemoPlayback;
import dev.bluevista.craftq3.engine.client.host.CgameHost;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.client.pipeline.UserCmdHistory;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plays every demo the installation's search paths expose (baseq3 and CPMA: protocols 48, 66 and
 * 68) through the real cgame to its end, as a timedemo, under {@code vm_strictSyscalls 1}. The
 * protocol-48 demos (1.25 and 1.27) play through pak8's 1.32 cgame. Writes {@code
 * build/reports/client/demos.txt}.
 */
@RealData
final class RealDemoPlaybackTest {
  static final int MAX_FRAMES = 200_000;
  private static final List<String> REPORT = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> everyDemoPlaysToItsEnd(@TempDir Path scratch) throws Exception {
    Path install = RealDataRoot.require();
    List<DynamicTest> tests = new ArrayList<>();
    for (RealInstall.Build build : RealInstall.builds(install, scratch)) {
      if (!build.name().equals("baseq3 (pak8)") && !build.name().equals("cpma")) continue;
      List<String> demos;
      try (SearchPath path = build.mount()) {
        demos =
            path.view().list("demos", "").stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).matches(".*\\.dm_\\d+"))
                .filter(n -> build.game().equals("baseq3") || !inBase(install, n))
                .toList();
      }
      for (String demo : demos)
        tests.add(
            DynamicTest.dynamicTest(
                build.name() + " " + demo, () -> play(build, install, "demos/" + demo)));
    }
    return tests.stream();
  }

  private static boolean inBase(Path install, String demo) {
    try (SearchPath base = SearchPath.mount(install, "baseq3")) {
      return base.view().exists(VirtualPath.of("demos/" + demo));
    } catch (java.io.IOException e) {
      return false;
    }
  }

  static void play(RealInstall.Build build, Path install, String demo) throws Exception {
    try (SearchPath path = build.mount();
        ClientRig rig = new ClientRig(path.view(), install, true)) {
      rig.keepFrames = false;
      SnapshotPipeline pipeline = new SnapshotPipeline();
      UserCmdHistory commands = new UserCmdHistory();
      DemoPlayback playback =
          DemoPlayback.open(demo, path.view().open(VirtualPath.of(demo)), pipeline);
      CgameHost cgame =
          new CgameHost(
              rig.services,
              pipeline,
              commands,
              new CgameHost.Link() {
                @Override
                public void clientCommand(String text) {}

                @Override
                public boolean demoPlayback() {
                  return true;
                }
              });
      int frames = 0, restarts = 0;
      long start = System.nanoTime();
      try (playback) {
        playback.prime();
        cgame.start();
        while (!playback.ended() && frames < MAX_FRAMES) {
          rig.advance(50);
          OptionalInt time = playback.frame(50, 0, 1, false, true, rig.services.milliseconds());
          if (cgame.stale() && pipeline.gameState().isPresent()) {
            cgame.shutdown();
            cgame.start();
            restarts++;
          }
          if (time.isEmpty()) continue;
          commands.add(UserCmdLayout.UserCmd.idle(time.getAsInt()));
          rig.services.renderer().beginFrame(rig.services.milliseconds());
          cgame.drawActiveFrame(time.getAsInt());
          rig.services.renderer().endFrame();
          frames++;
        }
        String line =
            String.format(
                Locale.ROOT,
                "%-38s %-7s frames %6d  views %6d  quads %7d  records %6d  restarts %d  end %s  degradations %d  %.1fs",
                build.game() + "/" + demo,
                cgame.profile().key(),
                frames,
                rig.views,
                rig.quads,
                playback.recordsRead(),
                restarts,
                playback.end(),
                cgame.degradationTotal(),
                (System.nanoTime() - start) / 1e9);
        record(line);
        assertTrue(playback.ended(), demo + " did not reach its end");
        assertTrue(frames > 0, demo + " produced no cgame frames");
        assertTrue(rig.views > 0, demo + " rendered no scene");
        assertEquals(0, cgame.degradationTotal(), demo + " degraded");
      } catch (RuntimeException | Error failure) {
        record(demo + " FAILED after " + frames + " frames: " + failure);
        List<String> tail =
            rig.printed.subList(Math.max(0, rig.printed.size() - 15), rig.printed.size());
        throw new AssertionError(
            demo + " failed; console tail:\n" + String.join("\n", tail), failure);
      } finally {
        cgame.shutdown();
      }
    }
  }

  private static synchronized void record(String line) throws java.io.IOException {
    REPORT.add(line);
    System.out.println(line);
    Path out = Path.of("build/reports/client/demos.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, String.join("\n", REPORT) + "\n", StandardCharsets.UTF_8);
  }
}
