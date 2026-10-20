package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.game.net.Protocol68Host;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The per-think listener hook, on every path that runs {@code GAME_CLIENT_THINK}. */
final class ClientThinkHookTest {
  private static final Map<String, String> INFO =
      Map.of("name", "Net", "rate", "25000", "snaps", "20", "handicap", "100");

  @Test
  void everyThinkIsBracketedForItsOwnClientWhateverDrivesIt() {
    Harness h = new Harness().spawn();
    Protocol68Host host = new Protocol68Host(h.server, name -> Optional.empty(), new Random(7));
    List<String> events = new ArrayList<>();
    h.game.thinkObserver = client -> events.add("think " + client);
    h.server.addListener(
        new GameServer.Listener() {
          @Override
          public void beforeClientThink(GameServer server, int client, UserCmd command) {
            events.add("before " + client + " @" + command.serverTime());
          }

          @Override
          public void afterClientThink(GameServer server, int client) {
            events.add("after " + client);
          }
        });

    // A client driven directly through userMove: the first command enters, later ones think.
    int direct = h.server.connect("\\name\\Direct", "10.0.0.2:1", false, -1).client();
    h.server.gamestateSent(direct);
    h.server.userMove(direct, List.of(GameServerTest.cmd(500, 0), GameServerTest.cmd(510, 3)));
    assertEquals(
        List.of("before " + direct + " @510", "think " + direct, "after " + direct), events);

    // A loopback client and a remote one, both through Protocol68Host.
    TestClient loopback = new TestClient(h, host, host.openLoopback());
    assertTrue(loopback.connect(INFO, 1), String.valueOf(loopback.printed));
    LoopbackTransport.Pair pair = LoopbackTransport.pair();
    host.addTransport(pair.server(), false);
    TestClient remote = new TestClient(h, host, pair.client());
    assertTrue(remote.connect(INFO, 2), String.valueOf(remote.printed));
    loopback.stepIdle();
    remote.stepIdle();
    for (int i = 0; i < 4; i++) {
      loopback.step(10);
      remote.step(10);
    }

    // A bot's botlib command.
    h.server.execute("fakebot Sarge");
    int bot = h.server.clientByName("Sarge");
    h.server.botUserCommand(bot, GameServerTest.cmd(h.server.serverTime() + 50, 0));

    Set<Integer> thought = new HashSet<>();
    for (int i = 0; i < events.size(); i++) {
      String event = events.get(i);
      if (!event.startsWith("think ")) continue;
      int client = Integer.parseInt(event.substring(6));
      thought.add(client);
      assertTrue(events.get(i - 1).startsWith("before " + client + " @"), "bracket at " + i);
      assertEquals("after " + client, events.get(i + 1), "bracket at " + i);
    }
    assertEquals(
        0, events.stream().filter(e -> e.startsWith("before")).count() * 3 - events.size());
    assertTrue(thought.contains(direct));
    assertTrue(thought.contains(bot));
    assertEquals(4, thought.size(), "direct, loopback, remote and bot clients thought: " + events);
  }

  @Test
  void puppetsSkipTheReliableWindowAndTheServersAnnouncements() {
    Harness h = new Harness().spawn();
    GameServer.Admission admission = h.server.connectPuppet("\\name\\Zombie\\handicap\\100");
    assertTrue(admission.accepted(), admission.denied());
    int c = admission.client();
    ServerClient slot = h.server.client(c);
    assertEquals(ServerClient.State.ACTIVE, slot.state());
    assertTrue(slot.isPuppet());
    assertFalse(slot.isBot());
    assertTrue(slot.isLocal());
    assertTrue(h.game.log.contains("connect " + c + " first=1 bot=0"), "a human to the game");
    assertTrue(h.game.log.contains("begin " + c));
    assertTrue(
        h.console.stream().noneMatch(l -> l.contains("connecting")), String.valueOf(h.console));
    assertEquals("localhost", slot.userinfoMap().get("ip"));

    // Far more server commands than a reliable window holds: nothing queues, nothing overflows.
    for (int i = 0; i < ServerClient.MAX_RELIABLE * 3; i++)
      h.server.sendServerCommandToAll("print \"spam " + i + "\n\"");
    h.server.setConfigstring(40, "x");
    h.run(500);
    assertEquals(ServerClient.State.ACTIVE, slot.state());
    assertEquals(0, slot.reliableSequence());
    assertEquals(0, h.server.client(c).ping());

    // A map_restart keeps it in the game.
    h.server.mapRestart(0);
    assertEquals(ServerClient.State.ACTIVE, slot.state());
    assertTrue(slot.isPuppet());

    // Dropping it frees the slot at once, without the server's broadcast.
    h.console.clear();
    h.game.log.clear();
    List<String> dropped = new ArrayList<>();
    h.server.addListener(
        new GameServer.Listener() {
          @Override
          public void clientDropped(GameServer server, int client, String reason) {
            dropped.add(client + " " + reason);
          }
        });
    h.server.drop(c, "gone");
    assertEquals(ServerClient.State.FREE, slot.state());
    assertFalse(slot.isPuppet());
    assertEquals(List.of("disconnect " + c), h.game.log);
    assertTrue(h.console.stream().noneMatch(l -> l.contains("Zombie")), String.valueOf(h.console));
    assertEquals(List.of(c + " gone"), dropped);

    // A new level frees puppets: their host re-admits what it still needs.
    int again = h.server.connectPuppet("\\name\\Skeleton").client();
    assertTrue(h.server.spawn("other", false));
    assertEquals(ServerClient.State.FREE, h.server.client(again).state());
    assertEquals(c + " gone", dropped.getFirst());
    assertEquals(again + " level changed", dropped.getLast());
  }
}
