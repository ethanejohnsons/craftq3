package dev.bluevista.craftq3.engine.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client half of the session with the original baseq3 (pak8) ui and cgame under {@code
 * vm_strictSyscalls 1}: boot to the main menu, play a retail demo to its end and back, run the logo
 * cinematic to its end, skip one with a key.
 */
@RealData
final class RealClientFlowTest {
  @TempDir Path home;

  private TestHost host;
  private EngineSession session;

  private void start() throws Exception {
    host = new TestHost(RealDataRoot.require(), home, false);
    host.keepFrames = false;
    session =
        EngineSession.start(
            host,
            SessionOptions.deterministic(7, null)
                .withCommandLine(CommandLine.parse("+set vm_strictSyscalls 1")));
  }

  private boolean until(SessionState state, int frames) {
    for (int i = 0; i < frames && session.state() != state; i++) {
      host.manual.advance(16);
      session.frame();
    }
    return session.state() == state;
  }

  private void run(int frames) {
    for (int i = 0; i < frames; i++) {
      host.manual.advance(16);
      session.frame();
    }
  }

  @Test
  void menuDemoAndCinematic() throws Exception {
    start();
    try {
      run(30);
      assertEquals(SessionState.MENU, session.state(), host.tail(30));
      assertEquals(UiHost.State.RUNNING, session.ui().state());
      assertTrue(host.quads > 0, "the main menu draws");
      assertEquals(0, session.degradations(), host.tail(30));

      session.command("timedemo 1; demo four");
      long immediateBefore = host.immediateFrames;
      assertTrue(until(SessionState.DEMO, 600), host.tail(30));
      // CG_INIT's loading steps each drew the original information screen and handed it over at
      // once (trap_UpdateScreen re-enters CG_DRAW_ACTIVE_FRAME, as in the original).
      long loadingFrames = host.immediateFrames - immediateBefore;
      assertTrue(loadingFrames >= 5, "loading screens inside CG_INIT: " + loadingFrames);
      assertTrue(host.immediateQuads > 0, "the loading screens draw something");
      long views = host.framesSeen;
      assertTrue(until(SessionState.MENU, 200_000), host.tail(30));
      assertTrue(host.framesSeen > views + 100, "the demo drew frames");
      assertEquals(1, session.demosFinished());
      assertEquals("", session.cvars().string("com_errorMessage"), host.tail(30));
      assertEquals(0, session.degradations(), host.tail(30));
      session.command("timedemo 0");

      session.command("cinematic idlogo.roq");
      assertTrue(until(SessionState.CINEMATIC, 10), host.tail(30));
      assertTrue(until(SessionState.MENU, 60 * 20), host.tail(30));

      session.command("cinematic intro.roq");
      assertTrue(until(SessionState.CINEMATIC, 10), host.tail(30));
      run(30);
      session.keyEvent(KeyNames.SPACE, true);
      session.keyEvent(KeyNames.SPACE, false);
      assertTrue(until(SessionState.MENU, 10), "a key skips the cinematic");
      assertEquals(0, session.degradations(), host.tail(30));
      System.out.println(
          "frames " + host.framesSeen + " quads " + host.quads + "\n" + host.tail(25));
    } finally {
      session.close();
    }
  }
}
