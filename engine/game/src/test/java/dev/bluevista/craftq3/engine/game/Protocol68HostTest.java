package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.game.net.Protocol68Host;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class Protocol68HostTest {
  private static final Map<String, String> INFO =
      Map.of(
          "name",
          "Tester",
          "rate",
          "25000",
          "snaps",
          "20",
          "model",
          "sarge/default",
          "headmodel",
          "sarge/default",
          "team_model",
          "sarge/default",
          "team_headmodel",
          "sarge/default",
          "sex",
          "male",
          "handicap",
          "100");

  private record Setup(Harness harness, Protocol68Host host) {}

  private static Setup setup() {
    Harness h = new Harness().spawn();
    return new Setup(h, new Protocol68Host(h.server, name -> Optional.empty(), new Random(7)));
  }

  /** A client on a link the host treats as remote (challenges and rate limits apply). */
  private static TestClient remote(Setup s) {
    LoopbackTransport.Pair pair = LoopbackTransport.pair();
    s.host.addTransport(pair.server(), false);
    return new TestClient(s.harness, s.host, pair.client());
  }

  private static TestClient local(Setup s) {
    return new TestClient(s.harness, s.host, s.host.openLoopback());
  }

  @Test
  void loopbackClientConnectsReceivesTheGamestateEntersAndGetsSnapshots() {
    Setup s = setup();
    TestClient c = local(s);
    assertTrue(c.connect(INFO, 1), String.valueOf(c.printed));
    assertEquals(ServerClient.State.CONNECTED, s.harness.server.client(0).state());
    c.stepIdle();
    var gamestate = c.session.gameState().orElseThrow();
    assertEquals(0, gamestate.clientNumber());
    assertEquals("fake-game", gamestate.configstrings().get(20));
    assertEquals(ServerClient.State.PRIMED, s.harness.server.client(0).state());
    for (int i = 0; i < 4; i++) c.step(10);
    assertEquals(ServerClient.State.ACTIVE, s.harness.server.client(0).state());
    var snapshot = c.session.snapshot().orElseThrow();
    float x =
        ByteBuffer.wrap(snapshot.player())
            .order(ByteOrder.LITTLE_ENDIAN)
            .getFloat(PlayerStateLayout.ORIGIN);
    assertTrue(x > -200, "the moves ran in the game: " + x);
    assertEquals(0, snapshot.flags() & GameServer.SNAPFLAG_NOT_ACTIVE);
    assertTrue(snapshot.entities().size() >= 1, "the door is visible");
    // A reliable client command round trip.
    c.session.command("echo hi");
    c.step(0);
    c.step(0);
    assertTrue(c.commands.contains("print \"echo hi\""), String.valueOf(c.commands));
    assertTrue(s.harness.server.client(0).reliableAcknowledge() > 0);
    assertEquals(0, s.harness.server.degradationTotal());
  }

  @Test
  void configstringChangesAndKicksReachTheClientReliably() {
    Setup s = setup();
    TestClient c = local(s);
    assertTrue(c.connect(INFO, 2));
    c.stepIdle();
    c.step(0);
    s.harness.server.setConfigstring(33, "changed");
    c.step(0);
    assertTrue(c.commands.contains("cs 33 \"changed\"\n"), String.valueOf(c.commands));
    s.harness.server.execute("clientkick 0");
    c.step(0);
    assertTrue(
        c.commands.contains("print \"Cannot kick host player\n\""), String.valueOf(c.commands));
    assertEquals(ServerClient.State.ACTIVE, s.harness.server.client(0).state());
  }

  @Test
  void remoteClientsNeedAChallengeAndAreDroppedWithADisconnect() {
    Setup s = setup();
    TestClient c = remote(s);
    List<String> replies =
        c.oob(
            "connect \"\\protocol\\68\\qport\\5\\challenge\\99\\name\\xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"");
    assertEquals(List.of("print\nNo or bad challenge for address.\n"), replies);
    replies =
        c.oob(
            "connect \"\\protocol\\67\\qport\\5\\challenge\\99\\name\\xxxxxxxxxxxxxxxxxxxxxxxxxxx\"");
    assertEquals(List.of("print\nServer uses protocol version 68.\n"), replies);
    assertTrue(c.connect(INFO, 5), String.valueOf(c.printed));
    c.stepIdle();
    c.step(0);
    c.step(0);
    assertEquals(ServerClient.State.ACTIVE, s.harness.server.client(0).state());
    s.harness.server.execute("kick Tester");
    for (int i = 0; i < 3; i++) c.stepIdle();
    assertTrue(
        c.commands.stream().anyMatch(t -> t.startsWith("disconnect \"was kicked")),
        String.valueOf(c.commands));
    assertEquals(ServerClient.State.ZOMBIE, s.harness.server.client(0).state());
    for (int i = 0; i < 60; i++) c.frame();
    assertEquals(ServerClient.State.FREE, s.harness.server.client(0).state());
  }

  @Test
  void clientDisconnectCommandFreesTheSlotAfterTheZombieTime() {
    Setup s = setup();
    TestClient c = local(s);
    assertTrue(c.connect(INFO, 3));
    c.stepIdle();
    c.step(0);
    c.session.command("disconnect");
    c.step(0);
    assertEquals(ServerClient.State.ZOMBIE, s.harness.server.client(0).state());
    assertTrue(s.harness.game.log.contains("disconnect 0"));
  }

  @Test
  void getinfoAndGetstatusDescribeTheServer() {
    Setup s = setup();
    TestClient c = remote(s);
    s.harness.server.cvars().set("sv_hostname", "Test Host");
    String info = c.oob("getinfo xyz").getFirst();
    assertTrue(info.startsWith("infoResponse\n"), info);
    assertTrue(info.contains("\\challenge\\xyz"));
    assertTrue(info.contains("\\hostname\\Test Host"));
    assertTrue(info.contains("\\mapname\\corridor"));
    assertTrue(info.contains("\\clients\\0"));
    assertTrue(info.contains("\\protocol\\68"));
    String status = c.oob("getstatus abc").getFirst();
    assertTrue(status.startsWith("statusResponse\n\\"), status);
    assertTrue(status.contains("\\challenge\\abc\n"));
  }

  @Test
  void rconAuthenticatesRateLimitsAndRedirectsOutput() {
    Setup s = setup();
    TestClient c = remote(s);
    assertEquals(List.of("print\nNo rconpassword set on the server.\n"), c.oob("rcon x status"));
    s.harness.server.cvars().set("rconPassword", "hunter2");
    for (int i = 0; i < 10; i++) c.frame();
    assertEquals(List.of("print\nBad rconpassword.\n"), c.oob("rcon wrong status"));
    assertEquals(List.of(), c.oob("rcon hunter2 status"), "within 500 ms of the last request");
    for (int i = 0; i < 10; i++) c.frame();
    List<String> replies = c.oob("rcon hunter2 status");
    assertEquals(1, replies.size());
    assertTrue(replies.getFirst().startsWith("print\nmap: corridor\n"), replies.getFirst());
    for (int i = 0; i < 10; i++) c.frame();
    c.oob("rcon \"hunter2\" set g_testvar 77");
    assertEquals("77", s.harness.server.cvars().string("g_testvar"));
  }

  @Test
  void mapChangeResendsTheGamestateAndLevelEndDisconnects() {
    Setup s = setup();
    TestClient c = local(s);
    assertTrue(c.connect(INFO, 4));
    c.stepIdle();
    c.step(0);
    c.step(0);
    int firstId =
        c.session.gameState().orElseThrow().commandSequence() >= 0
            ? s.harness.server.serverId()
            : 0;
    s.harness.server.execute("map other");
    assertNotEquals(firstId, s.harness.server.serverId());
    for (int i = 0; i < 4; i++) c.step(0);
    assertEquals(
        ServerClient.State.ACTIVE, s.harness.server.client(0).state(), "re-entered the new level");
    s.harness.server.cvars().set("fake_error", "1");
    for (int i = 0; i < 3; i++) c.stepIdle();
    assertEquals(GameServer.State.DROPPED, s.harness.server.state());
    assertTrue(
        c.commands.stream().anyMatch(t -> t.startsWith("disconnect")), String.valueOf(c.commands));
  }

  @Test
  void heartbeatIsOffByDefaultAndSentToMastersWhenEnabled() {
    Harness h = new Harness().spawn();
    List<String> resolved = new ArrayList<>();
    LoopbackTransport.Pair pair = LoopbackTransport.pair();
    Protocol68Host host =
        new Protocol68Host(
            h.server,
            name -> {
              resolved.add(name);
              return Optional.<SocketAddress>of(pair.client().localAddress());
            },
            new Random(1));
    host.addTransport(pair.server(), false);
    TestClient master = new TestClient(h, host, pair.client());
    master.frame();
    assertTrue(master.poll().isEmpty());
    h.server.cvars().set("dedicated", "2");
    h.server.cvars().set("sv_master1", "master.example:27950");
    master.frame();
    List<byte[]> packets = master.poll();
    assertEquals(1, packets.size());
    assertEquals(List.of("master.example:27950"), resolved);
    master.frame();
    assertTrue(master.poll().isEmpty(), "every five minutes only");
  }
}
