package dev.bluevista.craftq3.engine.headless;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.session.TransportFactory;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dedicated server main's path with the original baseq3 qagame: original argv, UDP on
 * localhost, a headless client joins, rcon reaches the server console and bots join.
 */
@RealData
final class RealDedicatedServerTest {
  @TempDir Path home;

  @Test
  void clientsJoinOverUdpAndRconWorks() throws Exception {
    Path install = RealDataRoot.require();
    List<String> out = new CopyOnWriteArrayList<>();
    try (HeadlessServer server =
        new HeadlessServer(
            CommandLine.parse(
                "+set fs_basepath",
                install.toString(),
                "+set fs_homepath",
                home.toString(),
                "+set dedicated 1 +set net_ip 127.0.0.1 +set net_port 0 +set sv_pure 0",
                "+set rconPassword secret +set vm_strictSyscalls 1 +map q3dm17"),
            out::add,
            TransportFactory.UDP)) {
      long end = System.nanoTime() + 120_000_000_000L;
      while (System.nanoTime() < end && server.status().phase() != LocalServer.Phase.RUNNING)
        Thread.sleep(20);
      assertEquals(LocalServer.Phase.RUNNING, server.status().phase(), String.join("\n", out));
      int port = server.address().getPort();
      try (HeadlessClient client =
          HeadlessClient.start(
              HeadlessClient.options(install)
                  .withManualClock(false)
                  .withCommandLine(
                      CommandLine.parse("+set vm_strictSyscalls 1 +set rconPassword secret")))) {
        client.command("connect 127.0.0.1:" + port);
        assertTrue(client.runUntil(SessionState.REMOTE, 60_000), client.tail(30));
        client.command("rconAddress 127.0.0.1:" + port + "; rcon addbot sarge 3");
        assertTrue(
            client.runUntil(() -> server.status().bots() == 1, 20_000), String.join("\n", out));
        client.command("rcon status");
        assertTrue(
            client.runUntil(() -> client.printed("sarge") || client.printed("Sarge"), 10_000),
            client.tail(20));
        client.run(3_000);
        assertEquals(SessionState.REMOTE, client.state(), client.tail(20));
        assertEquals(1, server.status().humans());
        assertEquals(0, server.status().degradations());
        assertEquals(0, client.session().degradations(), client.tail(20));
      }
    }
  }
}
