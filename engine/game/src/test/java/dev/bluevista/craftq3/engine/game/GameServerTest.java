package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.vmabi.ConfigStrings;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GameServerTest {
  @Test
  void spawnRunsInitThenFourSettlingFramesWithBotFramesBeforeClientsAreAdmitted() {
    Harness h = new Harness().spawn();
    assertEquals(GameServer.State.RUNNING, h.server.state());
    assertEquals(
        List.of(
            "init 0 restart=0",
            "tokens 10",
            "frame 0",
            "botframe 0",
            "frame 100",
            "botframe 100",
            "frame 200",
            "botframe 200",
            "frame 300",
            "botframe 300"),
        h.game.log);
    assertEquals(400, h.server.levelTime());
    assertEquals("fake-game", h.server.configstring(20));
    assertTrue(h.server.configstring(ConfigStrings.CS_SERVERINFO).contains("\\mapname\\corridor"));
    assertTrue(h.server.configstring(ConfigStrings.CS_SYSTEMINFO).contains("\\sv_serverid\\"));
    assertEquals(0, h.server.degradationTotal());
  }

  @Test
  void framesRunAtSvFpsWithTheRemainderCarried() {
    Harness h = new Harness().spawn();
    h.server.frame(1000);
    h.game.log.clear();
    assertEquals(0, h.server.frame(1030));
    assertEquals(1, h.server.frame(1060));
    assertEquals(List.of("botframe 400", "frame 450"), h.game.log);
    assertEquals(2, h.server.frame(1160));
    assertEquals(550, h.server.levelTime());
    h.server.cvars().set("sv_fps", "40");
    h.game.log.clear();
    assertEquals(4, h.server.frame(1260));
    assertEquals(650, h.server.levelTime());
  }

  @Test
  void admissionCallsTheGameAndDeniedClientsKeepTheirSlotFree() {
    Harness h = new Harness().spawn();
    GameServer.Admission denied =
        h.server.connect("\\name\\x\\deny\\1", "10.0.0.2:27960", false, -1);
    assertFalse(denied.accepted());
    assertEquals("Denied by the test game", denied.denied());
    assertEquals(ServerClient.State.FREE, h.server.client(0).state());

    GameServer.Admission ok =
        h.server.connect("\\name\\Player\\snaps\\10", "10.0.0.2:27960", false, -1);
    assertTrue(ok.accepted());
    assertEquals(0, ok.client());
    ServerClient client = h.server.client(0);
    assertEquals(ServerClient.State.CONNECTED, client.state());
    assertEquals("Player", client.name());
    assertEquals(100, client.snapshotMsec());
    assertTrue(client.userinfo().contains("\\ip\\10.0.0.2:27960"));

    h.server.gamestateSent(0);
    assertEquals(ServerClient.State.PRIMED, client.state());
    h.server.userMove(0, List.of(cmd(500, 0), cmd(510, 10), cmd(520, 10)));
    assertEquals(ServerClient.State.ACTIVE, client.state());
    assertTrue(h.game.log.contains("begin 0"));
    // The first command enters the world; the later ones think.
    assertEquals(-180, h.game.origin(0)[0], 1e-3);
    assertEquals(520, client.lastUsercmd().serverTime());
  }

  @Test
  void privateSlotsNeedThePassword() {
    Harness h = new Harness();
    h.server.cvars().set("sv_privateClients", "2");
    h.server.cvars().set("sv_privatePassword", "secret");
    h.spawn();
    assertEquals(2, h.server.connect("\\name\\a", "1.2.3.4:1", false, -1).client());
    assertEquals(
        0, h.server.connect("\\name\\b\\password\\secret", "1.2.3.4:2", false, -1).client());
  }

  @Test
  void clientCommandsReachTheGameAndReliableCommandsQueuePerClient() {
    Harness h = new Harness().spawn();
    int c = h.server.connect("\\name\\P", "1.1.1.1:1", false, -1).client();
    h.server.clientCommand(c, "echo hello", true);
    assertTrue(h.game.log.contains("command 0 echo"));
    assertEquals(List.of("print \"echo hello\""), h.reliable(c));
    // Flood protection drops a second game command within the same second for active clients.
    h.server.gamestateSent(c);
    h.server.userMove(c, List.of(cmd(500, 0)));
    h.server.clientCommand(c, "echo one", true);
    h.server.clientCommand(c, "echo two", true);
    assertFalse(h.reliable(c).contains("print \"echo two\""));
    h.server.client(c).acknowledgeReliable(h.server.client(c).reliableSequence());
    assertTrue(h.reliable(c).isEmpty());
  }

  @Test
  void configstringChangesBroadcastToPrimedClientsOnly() {
    Harness h = new Harness().spawn();
    int a = h.server.connect("\\name\\A", "1.1.1.1:1", false, -1).client();
    int b = h.server.connect("\\name\\B", "1.1.1.1:2", false, -1).client();
    h.server.gamestateSent(b);
    h.server.setConfigstring(30, "hello");
    h.server.setConfigstring(30, "hello");
    assertTrue(h.reliable(a).isEmpty());
    assertEquals(List.of("cs 30 \"hello\"\n"), h.reliable(b));
    h.server.setConfigstring(31, "x".repeat(2500));
    List<String> big = h.reliable(b);
    assertEquals(4, big.size());
    assertTrue(big.get(1).startsWith("bcs0 31 "));
    assertTrue(big.get(3).startsWith("bcs2 31 "));
  }

  @Test
  void reliableOverflowDropsTheClient() {
    Harness h = new Harness().spawn();
    int c = h.server.connect("\\name\\A", "1.1.1.1:1", false, -1).client();
    for (int i = 0; i < ServerClient.MAX_RELIABLE + 1; i++)
      h.server.sendServerCommand(c, "print \"" + i + "\"");
    assertEquals(ServerClient.State.ZOMBIE, h.server.client(c).state());
    assertTrue(h.game.log.contains("disconnect 0"));
  }

  @Test
  void trapErrorDropsTheLevelWithTheGuestMessageAndNeverThrows() {
    List<String> ended = new java.util.ArrayList<>();
    Harness h = new Harness().spawn();
    h.server.addListener(
        new GameServer.Listener() {
          @Override
          public void levelEnded(GameServer server, String reason) {
            ended.add(reason);
          }
        });
    h.server.connect("\\name\\A", "1.1.1.1:1", false, -1);
    h.server.cvars().set("fake_error", "1");
    assertDoesNotThrow(() -> h.run(200));
    assertEquals(GameServer.State.DROPPED, h.server.state());
    assertTrue(h.server.dropReason().startsWith("fake failure at"), h.server.dropReason());
    assertEquals(List.of(h.server.dropReason()), ended);
    assertEquals(ServerClient.State.FREE, h.server.client(0).state());
    assertEquals(0, h.run(200));
    // A new map recovers.
    h.server.cvars().set("fake_error", "0");
    assertTrue(h.server.spawn("corridor", false));
    assertEquals(GameServer.State.RUNNING, h.server.state());
  }

  @Test
  void mapRestartKeepsClientsAndTogglesTheServerCountBit() {
    Harness h = new Harness().spawn();
    int c = h.server.connect("\\name\\A", "1.1.1.1:1", false, -1).client();
    h.server.gamestateSent(c);
    h.server.userMove(c, List.of(cmd(500, 0)));
    int id = h.server.serverId(), flags = h.server.snapFlags();
    h.game.log.clear();
    h.server.execute("map_restart 0");
    assertEquals(1, h.server.restartCount());
    assertNotEquals(id, h.server.serverId());
    assertEquals(flags ^ GameServer.SNAPFLAG_SERVERCOUNT, h.server.snapFlags());
    assertEquals("shutdown restart=1", h.game.log.getFirst());
    assertTrue(h.game.log.contains("init " + (h.server.levelTime() - 400) + " restart=1"));
    assertTrue(h.game.log.contains("connect 0 first=0 bot=0"));
    assertTrue(h.game.log.contains("begin 0"));
    assertTrue(h.reliable(c).contains("map_restart\n"));
    assertEquals(ServerClient.State.ACTIVE, h.server.client(c).state());
  }

  @Test
  void delayedRestartAnnouncesWarmupThenRestarts() {
    Harness h = new Harness().spawn();
    h.server.execute("map_restart 2");
    assertEquals(Integer.toString(400 + 2000), h.server.configstring(5));
    h.server.frame(0);
    h.run(2100);
    assertEquals(1, h.server.restartCount());
  }

  @Test
  void consoleCommandsFallBackToTheGame() {
    Harness h = new Harness().spawn();
    h.server.execute("fakecmd 42");
    assertTrue(h.game.log.contains("console fakecmd 42"));
    h.server.execute("nosuchcommand");
    assertTrue(h.console.stream().anyMatch(l -> l.contains("Unknown command \"nosuchcommand\"")));
  }

  @Test
  void botsAllocateActiveSlotsReadTheirReliableCommandsAndCanBeKicked() {
    Harness h = new Harness().spawn();
    h.server.execute("fakebot Sarge");
    ServerClient bot = h.server.client(0);
    assertTrue(bot.isBot());
    assertEquals(ServerClient.State.ACTIVE, bot.state());
    assertEquals("Sarge", bot.name());
    h.server.setConfigstring(40, "x");
    assertEquals("cs 40 \"x\"\n", h.server.consoleMessage(0));
    assertNull(h.server.consoleMessage(0));
    h.run(100);
    assertEquals(14, h.server.snapshotEntity(0, 0), "the door straddles the bot's area");
    assertEquals(-1, h.server.snapshotEntity(0, 1));
    h.server.execute("kick Sarge");
    assertEquals(ServerClient.State.FREE, h.server.client(0).state());
    assertTrue(h.game.log.contains("disconnect 0"));
  }

  @Test
  void mapChangeRequestedInsideTheGameRunsAfterTheCallUnwinds() {
    Harness h = new Harness().spawn();
    int c = h.server.connect("\\name\\A", "1.1.1.1:1", false, -1).client();
    h.server.gamestateSent(c);
    int id = h.server.serverId();
    h.server.execute("map other");
    assertEquals("other", h.server.map().name());
    assertNotEquals(id, h.server.serverId());
    // Human clients stay connected and wait for the new gamestate.
    assertEquals(ServerClient.State.CONNECTED, h.server.client(c).state());
    assertEquals("0", h.server.cvars().string("sv_cheats"));
  }

  @Test
  void killserverEndsTheLevelGracefully() {
    Harness h = new Harness().spawn();
    h.server.execute("killserver");
    assertEquals(GameServer.State.IDLE, h.server.state());
    assertTrue(h.game.log.contains("shutdown restart=0"));
  }

  static UserCmd cmd(int time, int forward) {
    return new UserCmd(time, 0, 0, 0, 0, 0, forward, 0, 0);
  }
}
