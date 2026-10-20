package dev.bluevista.craftq3.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.client.input.ClientInput;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boots every effective ui of the installation to its main menu headlessly under {@code
 * vm_strictSyscalls 1} and runs 120 frames of synthetic input (mouse motion, a submenu opened with
 * the keyboard and closed with escape, a click). Requires zero degradations and 2D draws. Writes
 * {@code build/reports/client/ui-boot.txt}.
 */
@RealData
final class RealUiBootTest {
  static final int FRAMES = 120;
  static final int FRAME_MILLIS = 16;

  private static final List<String> REPORT = new ArrayList<>();

  @TestFactory
  Stream<DynamicTest> everyUiBootsToItsMainMenu(@TempDir Path scratch) throws Exception {
    Path install = RealDataRoot.require();
    return RealInstall.builds(install, scratch).stream()
        .map(build -> DynamicTest.dynamicTest(build.name(), () -> boot(build, install)));
  }

  private static void boot(RealInstall.Build build, Path install) throws Exception {
    try (SearchPath path = build.mount();
        ClientRig rig = new ClientRig(path.view(), install, true)) {
      rig.keepFrames = false;
      UiHost ui = new UiHost(rig.services, UiHost.Link.DISCONNECTED);
      ClientInput input = rig.services.input();
      input.setUiTarget(ui);
      try {
        ui.start();
        ui.setActiveMenu(UiHost.Menu.MAIN);
        for (int frame = 0; frame < FRAMES; frame++) {
          rig.advance(FRAME_MILLIS);
          int now = rig.services.milliseconds();
          switch (frame) {
            case 5 -> input.mouseEvent(120, 60, now);
            case 10 -> tap(input, KeyNames.DOWNARROW, now);
            case 20 -> tap(input, KeyNames.ENTER, now);
            case 60 -> tap(input, KeyNames.ESCAPE, now);
            case 80 -> input.mouseEvent(-40, 30, now);
            case 90 -> tap(input, KeyNames.MOUSE1, now);
            case 100 -> tap(input, KeyNames.ESCAPE, now);
            default -> {}
          }
          rig.frameCommands();
          rig.services.renderer().beginFrame(now);
          ui.refresh(now);
          rig.services.renderer().endFrame();
          rig.services.updateSound(FRAME_MILLIS);
        }
        String line =
            String.format(
                Locale.ROOT,
                "%-22s %-7s api %d  frames %4d  quads %6d  views %3d  shaders %4d  degradations %d  state %s",
                build.name(),
                ui.profile().key(),
                ui.apiVersion(),
                rig.framesSeen,
                rig.quads,
                rig.views,
                rig.services.assets().shaderCount(),
                ui.degradationTotal(),
                ui.state());
        record(line);
        assertEquals(0, ui.degradationTotal(), build.name() + " degraded");
        assertEquals(UiHost.State.RUNNING, ui.state(), build.name() + " stopped");
        assertTrue(rig.quads > 0, build.name() + " drew nothing");
      } catch (RuntimeException | Error failure) {
        record(build.name() + " FAILED: " + failure);
        List<String> tail =
            rig.printed.subList(Math.max(0, rig.printed.size() - 15), rig.printed.size());
        throw new AssertionError(
            build.name() + " failed; console tail:\n" + String.join("\n", tail), failure);
      } finally {
        ui.shutdown();
      }
    }
  }

  private static void tap(ClientInput input, int key, int time) {
    input.keyEvent(key, true, time);
    input.keyEvent(key, false, time);
  }

  private static synchronized void record(String line) throws java.io.IOException {
    REPORT.add(line);
    System.out.println(line);
    Path out = Path.of("build/reports/client/ui-boot.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, String.join("\n", REPORT) + "\n", StandardCharsets.UTF_8);
  }
}
