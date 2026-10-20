package dev.bluevista.craftq3.engine.client.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.host.EngineCvars;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.client.pipeline.UserCmdHistory;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class ClientConnectionTest {
  private final CvarTable cvars = new CvarTable(new ThreadOwner("test"));
  private final SnapshotPipeline pipeline = new SnapshotPipeline();
  private final UserCmdHistory commands = new UserCmdHistory();
  private final List<String> events = new ArrayList<>();
  private final LoopbackTransport.Pair link = LoopbackTransport.pair();
  private final FakeServer server = new FakeServer(link.server());
  private long now = 0;

  ClientConnectionTest() {
    EngineCvars.register(cvars);
  }

  private ClientConnection connect() {
    return new ClientConnection(
        link.client(),
        link.client().remoteAddress(),
        "localhost",
        Map.of(
            "name",
            "CraftQ3 connection tester",
            "rate",
            "25000",
            "snaps",
            "20",
            "model",
            "sarge/default",
            "handicap",
            "100"),
        42,
        1234,
        pipeline,
        commands,
        cvars,
        ClientConnection.Settings.DEFAULT,
        new ClientConnection.Listener() {
          @Override
          public void levelReady(ServerMessageCodec.GameState gameState) {
            events.add("level " + gameState.clientNumber());
          }

          @Override
          public void disconnected(String reason) {
            events.add("disconnected " + reason);
          }
        },
        now);
  }

  private void step(ClientConnection connection) throws Exception {
    now += 10;
    connection.pump(now);
    server.tick();
    connection.pump(now);
  }

  @Test
  void walksChallengeConnectGamestateAndBecomesActive() throws Exception {
    ClientConnection connection = connect();
    assertEquals(ClientConnection.State.CHALLENGING, connection.state());
    connection.pump(now);
    server.tick();
    connection.pump(now);
    assertEquals(ClientConnection.State.CONNECTING, connection.state());
    for (int i = 0; i < 5 && connection.state() != ClientConnection.State.LOADING; i++)
      step(connection);
    assertEquals(ClientConnection.State.LOADING, connection.state());
    assertEquals(List.of("level 0"), events);
    assertTrue(pipeline.gameState().isPresent());
    assertEquals("\\mapname\\testmap\\sv_hostname\\fake", pipeline.configString(0));

    connection.cgameLoaded();
    connection.presentServerId(FakeServer.configstrings().get(1));
    assertEquals(ClientConnection.State.PRIMED, connection.state());
    OptionalInt time = OptionalInt.empty();
    for (int i = 0; i < 20 && time.isEmpty(); i++) {
      step(connection);
      commands.add(UserCmd.idle((int) now));
      time = connection.serverTime((int) now);
    }
    assertTrue(time.isPresent());
    assertEquals(ClientConnection.State.ACTIVE, connection.state());
    assertTrue(pipeline.latestSnapshot().isPresent());
    assertEquals(3, pipeline.latestSnapshot().get().snapshot().entities().size());
    for (int i = 0; i < 10; i++) {
      commands.add(UserCmd.idle((int) now));
      step(connection);
    }
    assertFalse(server.userCommands.isEmpty(), "user commands reach the server");
  }

  @Test
  void carriesReliableCommandsBothWays() throws Exception {
    ClientConnection connection = connect();
    for (int i = 0; i < 10; i++) step(connection);
    connection.cgameLoaded();
    connection.command("say hello");
    server.command("print \"welcome\"");
    for (int i = 0; i < 10; i++) step(connection);
    assertTrue(server.clientCommands.contains("say hello"));
    int sequence = pipeline.serverCommandSequence();
    assertEquals("print \"welcome\"", pipeline.serverCommand(sequence).orElseThrow());
  }

  @Test
  void timesOutWhenTheServerNeverAnswers() throws Exception {
    server.silent = true;
    ClientConnection connection = connect();
    for (int i = 0; i < 2000 && connection.state() != ClientConnection.State.DISCONNECTED; i++)
      step(connection);
    assertEquals(ClientConnection.State.DISCONNECTED, connection.state());
    assertEquals("Server did not respond", connection.diagnostic());
    assertTrue(now >= ClientConnection.Settings.DEFAULT.connectTimeoutMillis());
  }

  @Test
  void timesOutWhenAConnectedServerFallsSilent() throws Exception {
    cvars.set("cl_timeout", "2");
    ClientConnection connection = connect();
    for (int i = 0; i < 10; i++) step(connection);
    server.silent = true;
    for (int i = 0; i < 400 && connection.state() != ClientConnection.State.DISCONNECTED; i++)
      step(connection);
    assertEquals("Server connection timed out", connection.diagnostic());
  }

  @Test
  void endsOnTheServersDisconnect() throws Exception {
    ClientConnection connection = connect();
    for (int i = 0; i < 10; i++) step(connection);
    server.dropClient();
    step(connection);
    assertEquals(ClientConnection.State.DISCONNECTED, connection.state());
    assertTrue(events.contains("disconnected Server disconnected"));
  }

  @Test
  void disconnectSendsTheReliableDisconnect() throws Exception {
    ClientConnection connection = connect();
    for (int i = 0; i < 10; i++) step(connection);
    connection.disconnect(now);
    server.tick();
    assertEquals(ClientConnection.State.DISCONNECTED, connection.state());
    assertTrue(server.clientCommands.contains("disconnect"));
    connection.pump(now + 100);
    assertEquals("Disconnected", connection.diagnostic());
  }

  @Test
  void reportsTheConnectScreenState() throws Exception {
    ClientConnection connection = connect();
    assertEquals(4, connection.uiState().connState());
    assertEquals("localhost", connection.uiState().serverName());
    for (int i = 0; i < 10; i++) step(connection);
    assertEquals(6, connection.uiState().connState());
    assertEquals(0, connection.uiState().clientNum());
  }
}
