package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Protocol68ServerSessionTest {
  private static final int CHALLENGE = 13579, QPORT = 24567;

  @Test
  void fragmentedGamestateAndBothReliableDirectionsUseMatchingOwnedKeys() {
    var p = new Peer();
    p.server.command("print initial");
    p.server.queueGameState(
        new GameState(
            999,
            Map.of(1, "\\sv_serverid\\100", 100, "abcdefghi".repeat(800)),
            Baselines.EMPTY,
            3,
            12345),
        100);
    var initial = p.toClient();
    assertTrue(p.fragments > 0);
    assertEquals(1, p.client.serverCommandSequence());
    assertEquals(3, p.client.gameState().orElseThrow().clientNumber());
    p.client.command("say hello");
    p.client.queue(List.of(user(1100)));
    var received = p.toServer();
    assertEquals(Protocol68ServerSession.Status.ACCEPTED, received.status());
    assertEquals("say hello", received.commands().getFirst().text());
    assertEquals(1100, time(received.userCommands().getFirst()));
    received.userCommands().getFirst()[0] = 0;
    assertEquals(1100, time(received.userCommands().getFirst()));
    p.server.queueKeepalive();
    p.toClient();
    assertEquals(1, p.client.reliableAcknowledge());
    assertNotNull(initial.message());
  }

  @Test
  void packetLossRetransmitsReliableCommandsAndDeltasFromTheAcknowledgedFrame() {
    var p = new Peer();
    p.start();
    p.snapshot(1000);
    var first = p.toClient();
    p.ack(1000);
    p.server.command("print after-loss");
    p.snapshot(1050);
    while (p.server.hasPendingPacket()) p.server.pollPacket();
    p.ack(1050);
    p.snapshot(1100);
    var recovered = p.toClient();
    var delta = (Frame) recovered.message().operations().getLast();
    assertNotNull(delta.previous());
    assertEquals(
        ((Frame) first.message().operations().getLast()).current().sequence(),
        delta.previous().sequence());
    assertEquals("print after-loss", recovered.commands().getFirst().text());
    assertEquals(1100, p.client.snapshot().orElseThrow().time());
  }

  @Test
  void duplicateClientCommandsAndMovementNeverExecuteTwice() {
    var p = new Peer();
    p.start();
    p.client.command("score");
    p.client.queue(List.of(user(1000), user(1050)));
    var first = p.toServer();
    assertEquals(1, first.commands().size());
    assertEquals(2, first.userCommands().size());
    p.client.queue(List.of(user(1000), user(1050), user(1100)));
    var second = p.toServer();
    assertTrue(second.commands().isEmpty());
    assertEquals(1, second.userCommands().size());
    assertEquals(1100, time(second.userCommands().getFirst()));
  }

  @Test
  void lateReliableGapCannotPublishAnEarlierCommandOrItsMovement() {
    var p = new Peer();
    p.start();
    var bad =
        p.forged(
            100,
            1,
            0,
            List.of(
                new ClientMessageCodec.Command(1, "say before"),
                new ClientMessageCodec.Command(3, "say gap")),
            List.of(user(1100)),
            "");
    assertEquals(Protocol68ServerSession.Status.REJECTED, bad.status());
    assertEquals(0, p.server.clientCommandSequence());
    var good =
        p.forged(
            100,
            1,
            0,
            List.of(new ClientMessageCodec.Command(1, "say before")),
            List.of(user(1100)),
            "");
    assertEquals(Protocol68ServerSession.Status.ACCEPTED, good.status());
    assertEquals(1, good.commands().size());
    assertEquals(1, good.userCommands().size());
  }

  @Test
  void newGameRejectsOldCommandsAndStartsIndependentSnapshotHistory() {
    var p = new Peer();
    p.start();
    p.snapshot(5000);
    p.toClient();
    p.ack(5000);
    p.server.command("print next");
    p.server.queueGameState(game(200), 200);
    p.toClient();
    p.client.command("say current");
    p.client.queue(List.of());
    while (p.client.pollPacket().isPresent()) {}
    var old =
        p.forged(
            100,
            3,
            1,
            List.of(new ClientMessageCodec.Command(1, "say obsolete")),
            List.of(user(6000)),
            "print next");
    assertEquals(Protocol68ServerSession.Status.WRONG_GAME, old.status());
    assertEquals(0, p.server.clientCommandSequence());
    var next =
        p.forged(
            200,
            3,
            1,
            List.of(new ClientMessageCodec.Command(1, "say current")),
            List.of(user(100)),
            "print next");
    assertEquals(Protocol68ServerSession.Status.ACCEPTED, next.status());
    assertEquals(100, time(next.userCommands().getFirst()));
    p.snapshot(150);
    var payload = p.toClient();
    assertNull(((Frame) payload.message().operations().getLast()).previous());
  }

  @Test
  void unretainedAndFutureMessageAcknowledgementsAlwaysUseFullSnapshots() {
    var p = new Peer();
    p.start();
    p.snapshot(1000);
    p.toClient();
    p.ack(1000);
    for (int i = 0; i < 32; i++) {
      p.server.queueKeepalive();
      p.toClient();
    }
    p.snapshot(2000);
    assertNull(((Frame) p.toClient().message().operations().getLast()).previous());
    assertEquals(
        Protocol68ServerSession.Status.ACCEPTED,
        p.forged(100, 999, 0, List.of(), List.of(user(2000)), "").status());
    p.snapshot(2050);
    assertNull(((Frame) p.toClient().message().operations().getLast()).previous());
  }

  @Test
  void unsentAndExpiredReliableAcknowledgementsAreRejectedWithoutPublication() {
    var p = new Peer();
    p.start();
    p.server.command("print pending");
    var unsent = p.forged(100, 1, 1, List.of(), List.of(user(1000)), "print pending");
    assertEquals(Protocol68ServerSession.Status.REJECTED, unsent.status());
    assertEquals(0, p.server.reliableAcknowledge());
    for (int i = 2; i <= 64; i++) p.server.command("print " + i);
    assertThrows(IllegalStateException.class, () -> p.server.command("overflow"));
    p.server.queueKeepalive();
    p.toClient();
    assertEquals(
        Protocol68ServerSession.Status.REJECTED,
        p.forged(100, 2, 0, List.of(), List.of(user(1100)), "").status());
    var fresh = p.forged(100, 2, 64, List.of(), List.of(user(1100)), "print 64");
    assertEquals(Protocol68ServerSession.Status.ACCEPTED, fresh.status());
    assertEquals(64, p.server.reliableAcknowledge());
    assertEquals(65, p.server.command("print available"));
  }

  private static GameState game(int id) {
    return new GameState(0, Map.of(1, "\\sv_serverid\\" + id), Baselines.EMPTY, 0, 12345);
  }

  private static byte[] user(int time) {
    byte[] bytes = new byte[24];
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(time);
    return bytes;
  }

  private static int time(byte[] bytes) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static class Peer {
    final Protocol68ServerSession server = new Protocol68ServerSession(CHALLENGE, QPORT);
    final Protocol68ClientSession client = new Protocol68ClientSession(CHALLENGE, QPORT);
    final Protocol68Channel raw = new Protocol68Channel(Protocol68Channel.Endpoint.CLIENT, QPORT);
    int fragments;

    void start() {
      server.queueGameState(game(100), 100);
      toClient();
    }

    void snapshot(int time) {
      server.queueSnapshot(time, 0, new byte[0], new byte[468], List.of());
    }

    void ack(int time) {
      client.queue(List.of(user(time)));
      assertEquals(Protocol68ServerSession.Status.ACCEPTED, toServer().status());
    }

    Protocol68ClientSession.Received toClient() {
      Protocol68ClientSession.Received result = null;
      while (server.hasPendingPacket()) {
        result = client.receive(server.pollPacket().orElseThrow());
        if (result.status() == Protocol68ClientSession.Status.PARTIAL) fragments++;
      }
      assertNotNull(result);
      assertEquals(Protocol68ClientSession.Status.ACCEPTED, result.status(), result.diagnostic());
      return result;
    }

    Protocol68ServerSession.Received toServer() {
      Protocol68ServerSession.Received result = null;
      for (var packet = client.pollPacket(); packet.isPresent(); packet = client.pollPacket())
        result = server.receive(packet.orElseThrow());
      return result;
    }

    Protocol68ServerSession.Received forged(
        int id,
        int ack,
        int reliable,
        List<ClientMessageCodec.Command> commands,
        List<byte[]> movement,
        String key) {
      var encoded = new MessageWriter();
      ClientMessageCodec.write(
          encoded,
          new ClientMessageCodec.Message(
              id, ack, reliable, commands, new ClientMessageCodec.Movement(true, movement)),
          12345,
          key);
      // Keep authored channel sequence ahead of any normal client traffic in mixed fixtures.
      while (raw.outgoingSequence() <= server.clientCommandSequence() + 2) {
        raw.queue(new byte[0]);
        while (raw.hasPendingPacket()) raw.pollPacket();
      }
      raw.queue(LegacyPayloadXor.client(encoded.bytes(), CHALLENGE, id, ack, key));
      Protocol68ServerSession.Received result = null;
      while (raw.hasPendingPacket()) result = server.receive(raw.pollPacket().orElseThrow());
      return result;
    }
  }
}
