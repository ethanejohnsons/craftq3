package dev.bluevista.craftq3.engine.headless;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.ServerRunner;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 6 scripted flow with the original baseq3 (pak8) ui, cgame and qagame, in real time with
 * the server on {@code cq3-sim} and thread assertions on: menu → {@code map q3dm17} → 10 s of play
 * with input → {@code addbot sarge 3} → {@code map_restart} → {@code map q3dm1} → {@code record t}
 * for 5 s → {@code stoprecord} → {@code demo t} to its end → {@code disconnect} → menu.
 */
@RealData
final class RealScriptedFlowTest {
  @TempDir Path home;

  @Test
  void menuToPlayToDemoAndBack() throws Exception {
    HeadlessClient.Options options =
        HeadlessClient.options(RealDataRoot.require())
            .withManualClock(false)
            .withHome(home)
            .withCommandLine(CommandLine.parse("+set vm_strictSyscalls 1 +set sv_pure 0"))
            .withLog(line -> System.out.println("Q3: " + line));
    try (HeadlessClient client = HeadlessClient.start(options)) {
      assertTrue(client.runUntil(SessionState.MENU, 5_000));
      client.run(500);
      assertTrue(client.frames().quads() > 0, "the main menu draws");

      client.command("map q3dm17");
      assertTrue(client.runUntil(SessionState.LOCAL, 120_000), client.tail(30));
      Thread sim = client.session().serverThread().orElseThrow();
      assertEquals(ServerRunner.THREAD_NAME, sim.getName());
      long views = client.frames().views();
      client.key(KeyNames.UPARROW, true);
      for (int i = 0; i < 100; i++) {
        client.mouse(i % 20 < 10 ? 6 : -6, 0);
        if (i % 25 == 0) client.key(KeyNames.MOUSE1, true);
        if (i % 25 == 5) client.key(KeyNames.MOUSE1, false);
        client.run(100);
      }
      client.key(KeyNames.UPARROW, false);
      assertEquals(SessionState.LOCAL, client.state(), client.tail(30));
      assertTrue(client.frames().views() > views + 100, "the cgame renders the level");

      client.command("addbot sarge 3");
      assertTrue(
          client.runUntil(
              () -> client.session().serverStatus().map(LocalServer.Status::bots).orElse(0) == 1,
              20_000),
          client.tail(30));
      client.command("map_restart 0");
      client.run(3_000);
      assertEquals(SessionState.LOCAL, client.state(), client.tail(30));
      int levels = client.session().levelsLoaded();

      client.command("map q3dm1");
      assertTrue(
          client.runUntil(
              () ->
                  client.session().levelsLoaded() > levels && client.state() == SessionState.LOCAL,
              120_000),
          client.tail(30));
      assertEquals("q3dm1", client.session().serverStatus().orElseThrow().map());

      client.command("record t");
      client.run(5_000);
      client.command("stoprecord");
      client.run(200);
      Path demo = home.resolve("baseq3/demos/t.dm_68");
      assertTrue(Files.size(demo) > 2_000, Files.size(demo) + " bytes\n" + client.tail(20));

      client.command("demo t");
      assertTrue(client.runUntil(SessionState.DEMO, 60_000), client.tail(30));
      assertTrue(client.session().serverStatus().isEmpty(), "the demo replaced the local game");
      assertTrue(client.runUntil(SessionState.MENU, 60_000), client.tail(30));
      assertEquals(1, client.session().demosFinished());
      assertEquals("", client.session().cvars().string("com_errorMessage"), client.tail(30));

      client.command("map q3dm17");
      assertTrue(client.runUntil(SessionState.LOCAL, 120_000), client.tail(30));
      client.command("disconnect");
      assertTrue(client.runUntil(SessionState.MENU, 5_000), client.tail(30));
      assertTrue(client.session().serverThread().isEmpty());
      sim.join(5_000);
      assertFalse(sim.isAlive(), "cq3-sim ends with the server");
      assertEquals(0, client.session().degradations(), client.tail(30));
      System.out.println(
          "flow: states "
              + client.states()
              + ", frames "
              + client.frames().frames()
              + ", views "
              + client.frames().views());
    }
  }
}
