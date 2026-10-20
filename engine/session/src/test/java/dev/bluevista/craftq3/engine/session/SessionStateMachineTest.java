package dev.bluevista.craftq3.engine.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.client.input.ClientInput;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session state machine over synthetic VMs and a fake server: boot, local play on both the
 * {@code cq3-sim} thread and synchronously, cvar and command forwarding, record and playback,
 * errors with {@code ERR_DROP} semantics, hosting over UDP, configs and thread ownership.
 */
final class SessionStateMachineTest {
  @TempDir Path root;

  @Test
  void bootsToTheMenuAfterTheStartupConfigs() throws Exception {
    Files.createDirectories(root.resolve("home/baseq3"));
    Files.writeString(root.resolve("home/baseq3/autoexec.cfg"), "set test_autoexec 2\n");
    try (SyntheticRig rig = new SyntheticRig(root, false, "+set test_line 3")) {
      rig.run(5);
      assertEquals(SessionState.MENU, rig.session.state());
      assertEquals(UiHost.State.RUNNING, rig.session.ui().state());
      assertEquals("1", rig.session.cvars().string("test_default"));
      assertEquals("2", rig.session.cvars().string("test_autoexec"));
      assertEquals("3", rig.session.cvars().string("test_line"));
      assertEquals("+forward", rig.session.console().bindings().orElseThrow().binding('w'));
      assertTrue(rig.host.framesSeen >= 5, "one frame per session frame");
      assertEquals(0, rig.session.degradations());
    }
  }

  @Test
  void writesTheConfigOnCleanExit() throws Exception {
    SessionOptions options =
        SessionOptions.deterministic(1, FakeLocalServer.FACTORY).withWriteConfig(true);
    try (SyntheticRig rig = new SyntheticRig(root, false, options)) {
      rig.session.command("seta test_archived 7");
      rig.run(2);
    }
    String config =
        Files.readString(root.resolve("home/baseq3/q3config.cfg"), StandardCharsets.ISO_8859_1);
    assertTrue(config.contains("seta test_archived \"7\""), config);
    assertTrue(config.contains("bind w \"+forward\""), config);
  }

  @Test
  void mapStartsALocalGameOnTheSimThread() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, true)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 2000), rig.tail());
      FakeLocalServer server = rig.server();
      Thread sim = rig.session.serverThread().orElseThrow();
      assertEquals(ServerRunner.THREAD_NAME, sim.getName());
      assertEquals(List.of(sim), server.frameThreads, "the server only runs on cq3-sim");
      // The server's tables belong to cq3-sim: touching them here fails the ownership check.
      assertThrows(IllegalStateException.class, () -> server.cvars().string("g_gametype"));
      assertTrue(
          rig.until(() -> rig.session.pipeline().flatMap(p -> p.latestSnapshot()).isPresent(), 500),
          "snapshots arrive over loopback");
      assertEquals(1, rig.session.levelsLoaded());
      assertTrue(rig.host.states.contains(SessionState.LOADING));
      rig.session.command("disconnect");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.MENU, 500), rig.tail());
      assertTrue(rig.session.serverThread().isEmpty());
      assertFalse(sim.isAlive(), "the server thread ends with the server");
      assertTrue(server.closed);
    }
  }

  @Test
  void mapAndConnectCloseTheConsole() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      var input = rig.session.services().input();
      input.setCatchers(input.catchers() | ClientInput.KEYCATCH_CONSOLE);
      for (char c : "half typed".toCharArray()) rig.session.output().field().insert(c);
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      assertEquals(0, rig.session.services().input().catchers() & ClientInput.KEYCATCH_CONSOLE);
      assertEquals("", rig.session.output().field().text(), "Con_Close clears the input line");
      input = rig.session.services().input();
      input.setCatchers(input.catchers() | ClientInput.KEYCATCH_CONSOLE);
      rig.session.command("connect localhost");
      rig.run(2);
      assertEquals(0, rig.session.services().input().catchers() & ClientInput.KEYCATCH_CONSOLE);
    }
  }

  @Test
  void startsOverAPremountedSearchPath() throws Exception {
    Path install = root.resolve("install");
    SyntheticRig.writeInstall(install);
    SearchPath mounted = SearchPath.mount(install, "baseq3");
    TestHost host = new TestHost(install, root.resolve("home"), false);
    try (EngineSession session =
        EngineSession.start(
            host, SessionOptions.deterministic(1, FakeLocalServer.FACTORY), mounted)) {
      assertSame(mounted, session.searchPath(), "the host's mount is used");
      session.command("map test");
      for (int i = 0; i < 50 && session.state() != SessionState.LOCAL; i++) {
        host.manual.advance(16);
        session.frame();
      }
      assertEquals(SessionState.LOCAL, session.state());
    }
    Files.createDirectories(install.resolve("othermod"));
    SearchPath other = SearchPath.mount(install, "othermod");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EngineSession.start(
                host, SessionOptions.deterministic(1, FakeLocalServer.FACTORY), other));
  }

  @Test
  void failOnDegradationFailsTheFrameOnceAndCountingContinues() throws Exception {
    SessionOptions options =
        SessionOptions.deterministic(1, FakeLocalServer.FACTORY).withFailOnDegradation(true);
    try (SyntheticRig rig = new SyntheticRig(root, false, options)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.server().degradations = 2;
      SessionDegradedException failure =
          assertThrows(SessionDegradedException.class, () -> rig.run(2));
      assertTrue(failure.getMessage().startsWith("2 syscall degradation"), failure.getMessage());
      rig.run(2);
      assertEquals(2, rig.session.degradations(), "reported once");
      assertEquals(SessionState.LOCAL, rig.session.state(), "the session keeps running");
    }
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.server().degradations = 3;
      rig.run(2);
      assertEquals(3, rig.session.degradations(), "counted without failing");
      rig.session.command("disconnect");
      rig.run(2);
      assertEquals(3, rig.session.degradations(), "kept after the server stopped");
    }
  }

  @Test
  void synchronousModeRunsTheServerOnTheClientThread() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      assertEquals(List.of(Thread.currentThread()), rig.server().frameThreads);
      assertTrue(rig.session.serverThread().isEmpty());
    }
  }

  @Test
  void clientCvarsAndServerCommandsReachTheServerInOrder() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("set g_gametype 3; map test; addbot sarge 3");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      FakeLocalServer server = rig.server();
      assertEquals("3", server.gametypeAtSpawn, "forwarded before the map command ran");
      assertTrue(rig.until(() -> server.executed.contains("addbot sarge 3"), 50));
      rig.session.command("set fraglimit 15");
      rig.run(2);
      assertEquals("15", server.cvars().string("fraglimit"));
      // The serverinfo mirror reaches the client table.
      assertEquals("fake", rig.session.cvars().string("sv_hostname"));
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 100));
    }
  }

  @Test
  void mapChangeReloadsTheCgameWithoutReconnecting() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.session.command("map other");
      assertTrue(rig.until(() -> rig.session.levelsLoaded() == 2, 200), rig.tail());
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200));
      assertEquals(1, FakeLocalServer.CREATED.stream().filter(s -> s == rig.server()).count());
      assertEquals("other", rig.session.serverStatus().orElseThrow().map());
    }
  }

  @Test
  void missingAndFailingMapsStayInTheMenu() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map nowhere");
      rig.run(3);
      assertEquals(SessionState.MENU, rig.session.state());
      assertTrue(
          rig.host.log.stream().anyMatch(l -> l.contains("Can't find map maps/nowhere.bsp")));
      rig.session.command("map bad");
      rig.run(5);
      assertEquals(SessionState.MENU, rig.session.state(), rig.tail());
      assertTrue(rig.session.serverStatus().isEmpty(), "the unused server was stopped");
    }
  }

  @Test
  void serverDropReturnsToTheMenuWithTheError() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.session.command("rconPassword x; rcon fake_drop");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.MENU, 200), rig.tail());
      assertTrue(rig.session.cvars().string("com_errorMessage").contains("Fake game error"));
      assertTrue(rig.session.serverStatus().isEmpty());
      // The session keeps working afterwards.
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
    }
  }

  @Test
  void beingKickedEndsTheGameWithTheServerMessage() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.session.command("kick visitor");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.MENU, 200), rig.tail());
      assertTrue(
          rig.session.cvars().string("com_errorMessage").startsWith("Server disconnected"),
          rig.session.cvars().string("com_errorMessage"));
    }
  }

  @Test
  void recordsAndPlaysBackADemo() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.session.command("record t");
      rig.run(60);
      assertEquals("1", rig.session.cvars().string("cl_demoRecording"));
      rig.session.command("stoprecord");
      rig.run(1);
      assertTrue(Files.size(root.resolve("home/baseq3/demos/t.dm_68")) > 100, rig.tail());
      rig.session.command("demo t");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.DEMO, 100), rig.tail());
      assertTrue(rig.session.serverStatus().isEmpty(), "demo playback stops the local server");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.MENU, 2000), rig.tail());
      assertEquals(1, rig.session.demosFinished());
      assertEquals("", rig.session.cvars().string("com_errorMessage"));
    }
  }

  @Test
  void anyKeyLeavesADemo() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.session.command("record k");
      rig.run(40);
      rig.session.command("stoprecord; demo k");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.DEMO, 100), rig.tail());
      rig.session.cvars().set("nextdemo", "demo k");
      rig.session.keyEvent('x', true);
      rig.session.keyEvent('x', false);
      rig.run(2);
      assertEquals(SessionState.MENU, rig.session.state(), rig.tail());
      assertEquals("", rig.session.cvars().string("nextdemo"), "a key cancels the attract loop");
    }
  }

  @Test
  void escapeOpensTheInGameMenu() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      assertTrue(rig.until(() -> rig.session.state() == SessionState.LOCAL, 200), rig.tail());
      rig.session.keyEvent(KeyNames.ESCAPE, true);
      rig.session.keyEvent(KeyNames.ESCAPE, false);
      rig.run(2);
      assertEquals(SessionState.LOCAL, rig.session.state(), "escape in a game opens the menu only");
    }
  }

  @Test
  void hostingIsReachableOverUdp() throws Exception {
    Path second = root.resolve("second");
    try (SyntheticRig host =
            new SyntheticRig(
                root, true, "+set net_enabled 1", "+set net_ip 127.0.0.1", "+set net_port 0");
        SyntheticRig client = new SyntheticRig(second, true)) {
      host.session.command("map test");
      assertTrue(host.until(() -> host.session.state() == SessionState.HOSTING, 2000), host.tail());
      FakeLocalServer server = host.server();
      int port = host.session.hostedAddress().orElseThrow().getPort();
      client.session.command("connect 127.0.0.1:" + port);
      for (int i = 0; i < 3000 && client.session.state() != SessionState.REMOTE; i++) {
        host.run(1);
        client.run(1);
      }
      assertEquals(SessionState.REMOTE, client.session.state(), client.tail());
      for (int i = 0; i < 50; i++) {
        host.run(1);
        client.run(1);
      }
      assertEquals(2, server.maxPeers, "local and remote clients are both connected");
      client.session.command("disconnect");
      client.run(3);
      assertEquals(SessionState.MENU, client.session.state());
    }
  }

  @Test
  void deterministicRunsAreReproducible() throws Exception {
    List<String> first = digests(root.resolve("a"));
    List<String> second = digests(root.resolve("b"));
    assertFalse(first.isEmpty());
    assertEquals(first, second);
  }

  private List<String> digests(Path dir) throws Exception {
    try (SyntheticRig rig = new SyntheticRig(dir, false)) {
      rig.session.command("map test");
      rig.run(120);
      List<String> out = new ArrayList<>();
      for (var frame : rig.host.frames) out.add(frame.timeMillis() + ":" + frame.commands());
      return out;
    }
  }

  @Test
  void callsFromAnotherThreadFailTheOwnershipCheck() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread other =
          new Thread(
              () -> {
                try {
                  rig.session.frame();
                } catch (Throwable t) {
                  failure.set(t);
                }
              });
      other.start();
      other.join();
      assertInstanceOf(IllegalStateException.class, failure.get());
    }
  }

  @Test
  void unknownCinematicStaysInTheMenu() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("cinematic nothing.roq");
      rig.run(3);
      assertEquals(SessionState.MENU, rig.session.state());
    }
  }

  @Test
  void quitAsksTheHost() throws Exception {
    try (SyntheticRig rig = new SyntheticRig(root, false)) {
      rig.session.command("map test");
      rig.run(10);
      rig.session.command("quit");
      rig.run(1);
      assertTrue(rig.session.quitRequested());
      assertTrue(rig.host.quitRequested);
      assertTrue(rig.session.serverStatus().isEmpty());
    }
  }

  @Test
  void commandLineSplitsAtPlus() {
    assertEquals(
        List.of("set dedicated 1", "map q3dm17", "set name \"a+b\""),
        CommandLine.parse("+set", "dedicated", "1", "+map", "q3dm17", "+set name \"a+b\""));
    assertEquals("1", CommandLine.setValue(CommandLine.parse("+set dedicated 1"), "dedicated"));
  }
}
