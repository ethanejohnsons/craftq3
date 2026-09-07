package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Protocol68ClientSessionTest {
  private static final int CHALLENGE = -13579;
  private static final int QPORT = 31234;

  @Test
  void acceptedRecordingPayloadIsOwnedDecryptedAndRejectedMessagesHaveNone() {
    var peer = new Peer();
    var game =
        new GameState(
            0,
            Map.of(1, "\\sv_serverid\\91", 100, "abcdefghij".repeat(700)),
            Baselines.EMPTY,
            3,
            73);
    var received = peer.send(0, "", game);
    var record = received.demoRecord().orElseThrow();
    var expected = new MessageWriter();
    ServerMessageCodec.write(expected, new Message(0, List.of(game)), Baselines.EMPTY);
    assertArrayEquals(expected.bytes(), record.payload());
    assertEquals(1, record.sequence());
    assertTrue(peer.fragments > 0);
    record.payload()[0] ^= 127;
    assertArrayEquals(expected.bytes(), record.payload());
    var rejected = peer.send(0, "", new Command(2, "gap"));
    assertEquals(Protocol68ClientSession.Status.REJECTED, rejected.status());
    assertTrue(rejected.demoRecord().isEmpty());
    peer.client.command("score");
    assertEquals(1, peer.client.reliableSequence());
    assertEquals(0, peer.client.reliableAcknowledge());
  }

  @Test
  void fullSnapshotOverrideSurvivesIncomingSnapshotsUntilRecorderReleasesIt() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    peer.client.fullSnapshots(true);
    for (int i = 0; i < 3; i++) {
      peer.accept(0, "", new Frame(snapshot(peer.nextSequence()), null));
      peer.client.queue(List.of(new byte[24]));
      assertFalse(peer.readClient().movement().requestDelta());
    }
    peer.client.fullSnapshots(false);
    peer.client.queue(List.of(new byte[24]));
    assertTrue(peer.readClient().movement().requestDelta());
  }

  @Test
  void fragmentedGamestateAndMovementOwnTheirStateAndKeys() {
    var peer = new Peer();
    var game =
        new GameState(
            0, Map.of(1, "\\sv_serverid\\91", 100, "abcdef".repeat(900)), Baselines.EMPTY, 3, 73);
    var received = peer.send(0, "", game);
    assertEquals(Protocol68ClientSession.Status.ACCEPTED, received.status());
    assertTrue(peer.fragments > 0);
    assertEquals(91, peer.client.serverId());
    assertEquals(1, peer.client.gameStateMessageSequence());
    assertEquals(3, peer.client.gameState().orElseThrow().clientNumber());
    var snapshot = snapshot(peer.nextSequence());
    peer.accept(0, "", new Frame(snapshot, null));
    byte[] command = new byte[24];
    command[0] = 27;
    peer.client.queue(List.of(command));
    command[0] = 42;
    var message = peer.readClient();
    assertTrue(message.movement().requestDelta());
    assertEquals(27, message.movement().commands().getFirst()[0]);
    assertEquals(91, message.serverId());
    assertEquals(snapshot.sequence(), message.messageAcknowledge());
  }

  @Test
  void reliableCommandsRetransmitUntilAcknowledgedAndWindowCannotOverflow() {
    var peer = new Peer();
    for (int i = 1; i <= 64; i++) assertEquals(i, peer.client.command("command " + i));
    assertThrows(IllegalStateException.class, () -> peer.client.command("overflow"));
    peer.client.queue(List.of());
    var first = peer.readClient();
    assertEquals(64, first.commands().size());
    peer.client.queue(List.of());
    assertEquals(first.commands(), peer.readClient().commands());
    peer.accept(20, "command 20", NoOp.INSTANCE);
    peer.client.queue(List.of());
    var remaining = peer.readClient();
    assertEquals(44, remaining.commands().size());
    assertEquals(21, remaining.commands().getFirst().sequence());
    for (int i = 65; i <= 84; i++) peer.client.command("command " + i);
    peer.client.queue(List.of());
    assertEquals(64, peer.readClient().commands().size());
    peer.accept(84, "command 84", NoOp.INSTANCE);
    assertEquals(84, peer.client.reliableAcknowledge());
    peer.client.queue(List.of());
    assertTrue(peer.readClient().commands().isEmpty());
  }

  @Test
  void largeBacklogUsesFittingPrefixesAndRejectsAcknowledgementsForUnsentCommands() {
    var peer = new Peer();
    var random = new Random(68);
    var keys = new ArrayList<String>();
    keys.add("");
    for (int i = 1; i <= 64; i++) {
      var text = new StringBuilder();
      for (int j = 0; j < 1000; j++) text.append((char) ('a' + random.nextInt(26)));
      keys.add(text.toString());
      peer.client.command(text.toString());
    }
    peer.client.queue(List.of());
    var first = peer.readClient();
    int sent = first.commands().getLast().sequence();
    assertTrue(sent > 0 && sent < 64);
    assertEquals(
        Protocol68ClientSession.Status.REJECTED,
        peer.send(64, keys.get(64), NoOp.INSTANCE).status());
    assertEquals(0, peer.client.reliableAcknowledge());
    peer.accept(sent, keys.get(sent), NoOp.INSTANCE);
    while (sent < 64) {
      peer.client.queue(List.of());
      var next = peer.readClient();
      assertEquals(sent + 1, next.commands().getFirst().sequence());
      sent = next.commands().getLast().sequence();
      peer.accept(sent, keys.get(sent), NoOp.INSTANCE);
    }
    assertEquals(64, peer.client.reliableAcknowledge());
  }

  @Test
  void snapshotMetadataCapturesCommandSequenceAtItsOperationAndDeduplicatesRepeats() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    int sequence = peer.nextSequence();
    var result =
        peer.send(
            0,
            "",
            new Command(1, "print before"),
            new Frame(snapshot(sequence), null),
            new Command(2, "print after"));
    assertEquals(2, result.commands().size());
    assertEquals(1, peer.client.snapshotState(sequence).orElseThrow().serverCommandSequence());
    assertEquals(2, peer.client.serverCommandSequence());
    assertEquals(0, peer.client.gameState().orElseThrow().commandSequence());
    assertEquals("print after", peer.client.serverCommand(2).orElseThrow());
    assertTrue(
        peer.send(0, "", new Command(1, "print before"), new Command(2, "print after"))
            .commands()
            .isEmpty());
    peer.client.queue(List.of(new byte[24]));
    var outgoing = peer.readClient();
    assertEquals(2, outgoing.serverCommandAcknowledge());
    assertFalse(
        outgoing.movement().requestDelta(), "Acknowledged command-only message is not a snapshot");
  }

  @Test
  void malformedLateOperationCannotPartiallyApplyReliableOrLevelState() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    int accepted = peer.client.messageAcknowledge();
    var result = peer.send(0, "", new Command(1, "cs 100 good"), new Command(3, "gap"));
    assertEquals(Protocol68ClientSession.Status.REJECTED, result.status());
    assertEquals(0, peer.client.serverCommandSequence());
    assertEquals(accepted, peer.client.messageAcknowledge());
    assertFalse(peer.client.gameState().orElseThrow().configstrings().containsKey(100));
    peer.accept(0, "", new Command(1, "cs 100 good"));
    assertEquals("good", peer.client.gameState().orElseThrow().configstrings().get(100));
  }

  @Test
  void missingHistoryRequestsFullUpdateAndLaterCompleteSnapshotRecovers() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    var first = snapshot(peer.nextSequence());
    peer.accept(0, "", new Frame(first, null));
    int omitted = peer.nextSequence();
    peer.skip(0, "", new Frame(snapshot(omitted), first));
    var missing = peer.send(0, "", new Frame(snapshot(peer.nextSequence()), snapshot(omitted)));
    assertEquals(Protocol68ClientSession.Status.REJECTED, missing.status());
    peer.client.queue(List.of(new byte[24]));
    assertFalse(peer.readClient().movement().requestDelta());
    // A valid command-only packet must not re-enable deltas after the failure.
    peer.accept(0, "", NoOp.INSTANCE);
    peer.client.queue(List.of(new byte[24]));
    assertFalse(peer.readClient().movement().requestDelta());
    peer.accept(0, "", new Frame(snapshot(peer.nextSequence()), null));
    peer.client.queue(List.of(new byte[24]));
    assertTrue(peer.readClient().movement().requestDelta());
    peer.client.requestFullSnapshot();
    peer.client.queue(List.of(new byte[24]));
    assertFalse(peer.readClient().movement().requestDelta());
  }

  @Test
  void configstringFragmentsAndServerIdUpdatesAreBoundedAndTransactional() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    peer.accept(0, "", new Command(1, "bcs0 100 \"first \""));
    assertFalse(peer.client.gameState().orElseThrow().configstrings().containsKey(100));
    assertEquals(
        Protocol68ClientSession.Status.REJECTED,
        peer.send(0, "", new Command(2, "cs 1024 wrong")).status());
    peer.accept(
        0,
        "",
        new Command(2, "bcs1 101 \"middle \""),
        new Command(3, "bcs2 -42 last"),
        new Command(4, "cs 1 \"\\sv_serverid\\92\""));
    assertEquals(
        "first middle last", peer.client.gameState().orElseThrow().configstrings().get(100));
    assertEquals(92, peer.client.serverId());
    assertEquals(
        Protocol68ClientSession.Status.REJECTED,
        peer.send(0, "", new Command(5, "cs 1 invalid")).status());
    assertEquals(92, peer.client.serverId());
    peer.accept(0, "", new Command(5, "cs 100 \"\""));
    assertFalse(peer.client.gameState().orElseThrow().configstrings().containsKey(100));
  }

  @Test
  void gamestateResetInvalidatesSnapshotsWhileKeepingTheReliableKeyAndInitMetadata() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    int oldSequence = peer.nextSequence();
    peer.accept(0, "", new Command(1, "print retained"), new Frame(snapshot(oldSequence), null));
    int resetSequence = peer.nextSequence();
    peer.accept(0, "", game(1, 92));
    assertEquals(resetSequence, peer.client.gameStateMessageSequence());
    assertEquals(1, peer.client.gameState().orElseThrow().commandSequence());
    assertTrue(peer.client.snapshotState(oldSequence).isEmpty());
    assertTrue(peer.client.snapshot().isEmpty());
    peer.client.queue(List.of(new byte[24]));
    var message = peer.readClient();
    assertFalse(message.movement().requestDelta());
    assertEquals(92, message.serverId());
    assertEquals(1, message.serverCommandAcknowledge());
  }

  @Test
  void snapshotAndCommandHistoriesStayBoundedAndRejectedHeadersCannotAcknowledge() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    for (int i = 1; i <= 80; i++)
      peer.accept(
          0, "", new Command(i, "print " + i), new Frame(snapshot(peer.nextSequence()), null));
    assertTrue(peer.client.snapshotState(49).isEmpty());
    assertTrue(peer.client.snapshotState(50).isPresent());
    assertTrue(peer.client.serverCommand(16).isEmpty());
    assertTrue(peer.client.serverCommand(17).isPresent());
    peer.client.command("unsent");
    assertEquals(
        Protocol68ClientSession.Status.REJECTED, peer.send(1, "unsent", NoOp.INSTANCE).status());
    assertEquals(0, peer.client.reliableAcknowledge());
    assertEquals(
        Protocol68ClientSession.Status.REJECTED, peer.client.receive(new byte[2]).status());
  }

  @Test
  void pendingFragmentsMustBeDrainedBeforeReplacingTheMessage() {
    var peer = new Peer();
    peer.client.queue(List.of());
    assertThrows(IllegalStateException.class, () -> peer.client.queue(List.of()));
    peer.readClient();
    assertFalse(peer.client.hasPendingPacket());
    assertTrue(peer.client.pollPacket().isEmpty());
    assertThrows(IllegalStateException.class, () -> peer.client.queue(List.of(new byte[24])));
  }

  @Test
  void gamestateCannotRewindCommandsEarlierInTheSamePayload() {
    var peer = new Peer();
    peer.accept(0, "", game(0, 91));
    var received = peer.send(0, "", new Command(1, "first"), new Command(2, "second"), game(1, 92));
    assertEquals(Protocol68ClientSession.Status.REJECTED, received.status());
    assertTrue(received.commands().isEmpty());
    assertEquals(0, peer.client.serverCommandSequence());
    assertEquals(91, peer.client.serverId());
    peer.accept(0, "", new Command(1, "first"), new Command(2, "second"), game(2, 92));
    assertEquals(2, peer.client.serverCommandSequence());
  }

  @Test
  void queuedCommandsAreNotAcknowledgableUntilAllDatagramsAreHandedToTransport() {
    var peer = new Peer();
    String key = "x".repeat(1000);
    peer.client.command(key);
    peer.client.command(key);
    peer.client.queue(List.of());
    assertEquals(
        Protocol68ClientSession.Status.REJECTED, peer.send(2, key, NoOp.INSTANCE).status());
    assertEquals(0, peer.client.reliableAcknowledge());
    assertTrue(peer.client.hasPendingPacket());
    assertEquals(
        Protocol68Channel.Status.PARTIAL,
        peer.server.receive(peer.client.pollPacket().orElseThrow()).status());
    assertTrue(peer.client.hasPendingPacket());
    assertEquals(
        Protocol68ClientSession.Status.REJECTED, peer.send(2, key, NoOp.INSTANCE).status());
    assertEquals(0, peer.client.reliableAcknowledge());
    peer.readClient();
    peer.accept(2, key, NoOp.INSTANCE);
    assertEquals(2, peer.client.reliableAcknowledge());
  }

  private static GameState game(int commandSequence, int id) {
    return new GameState(
        commandSequence, Map.of(1, "\\sv_serverid\\" + id), Baselines.EMPTY, 0, 73);
  }

  private static Snapshot snapshot(int sequence) {
    return new Snapshot(sequence, sequence * 50, 0, new byte[0], new byte[468], List.of());
  }

  private static final class Peer {
    final Protocol68ClientSession client = new Protocol68ClientSession(CHALLENGE, QPORT);
    final Protocol68Channel server =
        new Protocol68Channel(Protocol68Channel.Endpoint.SERVER, QPORT);
    Baselines baselines = Baselines.EMPTY;
    int fragments;

    int nextSequence() {
      return server.outgoingSequence();
    }

    void accept(int ack, String key, Operation... operations) {
      var result = send(ack, key, operations);
      assertEquals(Protocol68ClientSession.Status.ACCEPTED, result.status(), result.diagnostic());
    }

    Protocol68ClientSession.Received send(int ack, String key, Operation... operations) {
      queue(ack, key, operations);
      Protocol68ClientSession.Received result = null;
      while (server.hasPendingPacket()) {
        result = client.receive(server.pollPacket().orElseThrow());
        if (result.status() == Protocol68ClientSession.Status.PARTIAL) fragments++;
      }
      return result;
    }

    void skip(int ack, String key, Operation... operations) {
      queue(ack, key, operations);
      while (server.hasPendingPacket()) server.pollPacket().orElseThrow();
    }

    void queue(int ack, String key, Operation... operations) {
      var writer = new MessageWriter();
      ServerMessageCodec.write(writer, new Message(ack, List.of(operations)), baselines);
      server.queue(LegacyPayloadXor.server(writer.bytes(), CHALLENGE, nextSequence(), key));
      for (var operation : operations)
        if (operation instanceof GameState game) baselines = game.baselines();
    }

    ClientMessageCodec.Message readClient() {
      Protocol68Channel.Received received = null;
      while (client.hasPendingPacket())
        received = server.receive(client.pollPacket().orElseThrow());
      assertNotNull(received);
      assertEquals(Protocol68Channel.Status.COMPLETE, received.status());
      String key = client.serverCommand(client.serverCommandSequence()).orElseThrow();
      byte[] plain =
          LegacyPayloadXor.client(
              received.payload(), CHALLENGE, client.serverId(), client.messageAcknowledge(), key);
      int feed = client.gameState().map(GameState::checksumFeed).orElse(0);
      return ClientMessageCodec.read(new MessageReader(plain), feed, ignored -> key);
    }
  }
}
