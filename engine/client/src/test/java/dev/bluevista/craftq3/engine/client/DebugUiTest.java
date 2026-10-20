package dev.bluevista.craftq3.engine.client;

import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ad-hoc diagnostics for one build (DEBUG_GAMES=name prefix); prints its menu's draws. */
@RealData
final class DebugUiTest {
  @Test
  void debug(@TempDir Path scratch) throws Exception {
    String wanted = System.getenv("DEBUG_GAMES");
    if (wanted == null) return;
    Path install = RealDataRoot.require();
    for (RealInstall.Build build : RealInstall.builds(install, scratch)) {
      if (!build.name().startsWith(wanted)) continue;
      try (SearchPath path = build.mount();
          ClientRig rig = new ClientRig(path.view(), install, false)) {
        UiHost ui = new UiHost(rig.services, UiHost.Link.DISCONNECTED);
        rig.services.input().setUiTarget(ui);
        ui.start();
        ui.setActiveMenu(UiHost.Menu.MAIN);
        for (int i = 0; i < 30; i++) {
          rig.advance(16);
          rig.services.renderer().beginFrame(rig.services.milliseconds());
          ui.refresh(rig.services.milliseconds());
          rig.services.renderer().endFrame();
        }
        Set<String> shaders = new LinkedHashSet<>();
        for (CgameFrame.Command c : rig.frames.getLast().commands())
          if (c instanceof CgameFrame.Quad q) shaders.add(q.shader());
        System.out.println(
            build.name() + " catchers " + rig.services.input().catchers() + " shaders " + shaders);
        rig.printed.forEach(l -> System.out.println("> " + l));
      }
    }
  }
}
