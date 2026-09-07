package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.net.ClientMessageCodec;
import dev.bluevista.craftq3.core.net.LegacyPayloadXor;
import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.Protocol68Channel;
import dev.bluevista.craftq3.core.net.Protocol68ClientSession;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Command;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.NoOp;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Operation;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Packet-backed source tests using production codecs and channels; no socket or original media. */
final class RemoteCgameSourceTest {
  @Test
  void outgoingServerIdAndSystemInfoEffectsAdvanceOnlyAtConsumptionUntilANewGamestate() {
    var peer = new Peer();
    peer.accept(game(0, 91, "initial"));
    var effects = new ArrayList<String>();
    var source = new RemoteCgameSource(peer.client, ignored -> {}, ignored -> 0, effects::add);
    source.initialize(3);
    peer.accept(new Command(1, "cs 1 \"\\sv_serverid\\92\""));
    assertEquals("\\sv_serverid\\92", peer.client.gameState().orElseThrow().configstrings().get(1));
    assertEquals(List.of("\\sv_serverid\\91"), effects);
    peer.client.queue(List.of());
    assertEquals(91, peer.readClient().serverId());
    source.serverCommand(1);
    assertEquals(List.of("\\sv_serverid\\91", "\\sv_serverid\\92"), effects);
    peer.client.queue(List.of());
    assertEquals(92, peer.readClient().serverId());
    source.serverCommand(1);
    assertEquals(2, effects.size(), "An unchanged cs value has no repeated systeminfo effects");
    peer.accept(game(1, 93, "next"));
    assertEquals(93, peer.client.serverId(), "Gamestate applies its server ID immediately");
    assertThrows(RemoteCgameSource.LevelChangedException.class, () -> source.serverCommand(1));
  }

  @Test
  void delayedConstructionUsesOriginalGamestateAndItsNonzeroInitializationSequences() {
    var peer = new Peer();
    peer.accept(NoOp.INSTANCE);
    peer.accept(NoOp.INSTANCE);
    int gameNumber = peer.nextSequence();
    peer.accept(new Command(7, "print covered baseline"), game(7, 91, "initial"));
    int frameNumber = peer.nextSequence();
    peer.accept(
        new Command(8, "cs 100 before"),
        new Frame(snapshot(frameNumber, 12300, 5), null),
        new Command(9, "cs 100 after"));
    assertEquals("after", peer.client.gameState().orElseThrow().configstrings().get(100));
    assertEquals("initial", peer.client.initialGameState().orElseThrow().configstrings().get(100));
    var source = peer.source();
    assertEquals(new CgameSource.Initialization(3, gameNumber, 7, 12300, 5), source.initialize(3));
    assertEquals("initial", source.configStrings().get(100));
    source.refresh();
    assertEquals("initial", source.configStrings().get(100));
    assertEquals(Optional.of("cs 100 before"), source.serverCommand(8));
    assertEquals("before", source.configStrings().get(100));
    assertEquals(Optional.of("cs 100 after"), source.serverCommand(9));
    assertEquals("after", source.configStrings().get(100));
  }

  @Test
  void snapshotMetadataUsesFrameOperationSequenceAndSuppliedPingWithoutInventingCount() {
    var peer = new Peer();
    peer.accept(game(0, 91, "initial"));
    var requestedPings = new ArrayList<Integer>();
    var source =
        new RemoteCgameSource(
            peer.client,
            ignored -> {},
            number -> {
              requestedPings.add(number);
              return 37 + number;
            });
    source.initialize(3);
    int first = peer.nextSequence();
    peer.accept(
        new Command(1, "print before"),
        new Frame(snapshot(first, 100, 3), null),
        new Command(2, "print after"));
    var frame = source.snapshot(first).orElseThrow();
    assertEquals(new CgameSource.SnapshotNumber(first, 100), source.currentSnapshot());
    assertEquals(1, frame.serverCommandSequence());
    assertEquals(-1, frame.serverCommandCount());
    assertEquals(37 + first, frame.ping());
    assertEquals(4, frame.flags());
    assertEquals(32, frame.areaMask().length);
    assertEquals(6, frame.areaMask()[0]);
    assertEquals(3, ByteBuffer.wrap(frame.player()).order(ByteOrder.LITTLE_ENDIAN).getInt(144));
    assertEquals(1, frame.entities().size());
    int second = peer.nextSequence();
    peer.accept(new Frame(snapshot(second, 150, 4), null));
    assertEquals(2, source.snapshot(second).orElseThrow().serverCommandSequence());
    assertEquals(1, source.snapshot(first).orElseThrow().serverCommandSequence());
    assertEquals(List.of(first, second, first), requestedPings);
    frame.player()[0] = 99;
    frame.entities().getFirst()[0] = 99;
    frame.areaMask()[0] = 99;
    assertEquals(0, source.snapshot(first).orElseThrow().player()[0]);
    assertEquals(2, source.snapshot(first).orElseThrow().entities().getFirst()[0]);
    assertEquals(6, source.snapshot(first).orElseThrow().areaMask()[0]);
  }

  @Test
  void sparseSnapshotsUseMessageAgeEvenWhenTheWireSessionStillRetainsTheOlderObject() {
    var peer = new Peer();
    peer.accept(game(0, 91, "initial"));
    var source = peer.source();
    source.initialize(3);
    assertEquals(new CgameSource.SnapshotNumber(0, 0), source.currentSnapshot());
    assertTrue(source.snapshot(0).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> source.snapshot(1));
    peer.accept(new Frame(snapshot(peer.nextSequence(), 100, 1), null));
    while (peer.nextSequence() < 9) peer.accept(NoOp.INSTANCE);
    assertEquals(2, source.currentSnapshot().number());
    peer.accept(new Frame(snapshot(9, 450, 1), null));
    peer.accept(new Frame(snapshot(10, 500, 1), null));
    while (peer.nextSequence() < 41) peer.accept(NoOp.INSTANCE);
    peer.accept(new Frame(snapshot(41, 2050, 1), null));
    assertTrue(
        peer.client.snapshotState(9).isPresent(), "Wire history is bounded by stored snapshots");
    assertTrue(source.snapshot(9).isEmpty(), "Cgame's32-message window excludes the boundary");
    assertTrue(source.snapshot(10).isPresent());
    assertTrue(source.snapshot(11).isEmpty());
    assertTrue(source.snapshot(-1).isEmpty());
    assertEquals(41, source.currentSnapshot().number());
    assertThrows(IllegalArgumentException.class, () -> source.snapshot(42));
    peer.accept(NoOp.INSTANCE);
    assertThrows(
        IllegalArgumentException.class,
        () -> source.snapshot(42),
        "A command-only packet is not a newer snapshot");
  }

  @Test
  void reliableFragmentEffectsStaySeparateAndRetainedCommandsAreExecutedAgain() {
    var peer = new Peer();
    peer.accept(game(0, 91, "initial"));
    peer.accept(
        new Command(1, "bcs0 00100 a ignored"),
        new Command(2, "bcs2 999 b ignored"),
        new Command(3, "bcs2 777 c ignored"));
    assertEquals("ab c ", peer.client.gameState().orElseThrow().configstrings().get(100));
    var source = peer.source();
    source.initialize(3);
    assertEquals("initial", source.configStrings().get(100));
    assertEquals(Optional.empty(), source.serverCommand(1));
    assertEquals("initial", source.configStrings().get(100));
    assertEquals(Optional.of("cs 00100 \"ab\""), source.serverCommand(2));
    assertEquals("ab", source.configStrings().get(100));
    assertEquals(Optional.of("cs 00100 \"ab\"c\""), source.serverCommand(3));
    assertEquals("ab c ", source.configStrings().get(100));
    assertEquals(Optional.of("cs 00100 \"ab\"c\"b\""), source.serverCommand(2));
    assertEquals("ab c b", source.configStrings().get(100));
    assertEquals(
        "ab c ",
        peer.client.gameState().orElseThrow().configstrings().get(100),
        "Presentation repeat does not mutate wire state");
  }

  @Test
  void fetchingLaterCommandsDoesNotExecutePrecedingCommandsAndRestartResynchronizes() {
    var peer = new Peer();
    peer.accept(game(0, 91, "initial"));
    var source = peer.source();
    source.initialize(3);
    peer.accept(new Command(1, "cs 100 first"), new Command(2, "cs 100 second"));
    source.serverCommand(2);
    assertEquals("second", source.configStrings().get(100));
    source.serverCommand(1);
    assertEquals("first", source.configStrings().get(100));
    var restarted = source.restart();
    assertEquals(2, restarted.serverCommandSequence());
    assertEquals(
        1,
        restarted.serverMessageSequence(),
        "Without a snapshot, restart uses the gamestate message");
    assertEquals("second", source.configStrings().get(100));
    int number = peer.nextSequence();
    peer.accept(
        new Command(3, "cs 101 newest"),
        new Frame(snapshot(number, 750, 8), null),
        new Command(4, "cs 102 tail"));
    var stringsBefore = source.configStrings();
    assertFalse(stringsBefore.containsKey(101));
    restarted = source.restart();
    assertEquals(new CgameSource.Initialization(3, number - 1, 4, 750, 8), restarted);
    assertEquals("newest", source.configStrings().get(101));
    assertEquals("tail", source.configStrings().get(102));
    assertFalse(stringsBefore.containsKey(101));
  }

  @Test
  void liveReliableHistoryRejectsExpiredAndFutureCommandsWithoutChangingVisibleStrings() {
    var peer = new Peer();
    peer.accept(game(0, 91, "initial"));
    var source = peer.source();
    source.initialize(3);
    for (int sequence = 1; sequence <= 65; sequence++)
      peer.accept(new Command(sequence, "cs 100 value" + sequence));
    assertEquals(Optional.of("cs 100 value65"), source.serverCommand(65));
    assertEquals("value65", source.configStrings().get(100));
    assertThrows(IllegalStateException.class, () -> source.serverCommand(1));
    assertThrows(IllegalStateException.class, () -> source.serverCommand(-1));
    assertThrows(IllegalArgumentException.class, () -> source.serverCommand(66));
    assertEquals("value65", source.configStrings().get(100));
    assertEquals(Optional.of("cs 100 value2"), source.serverCommand(2));
    assertEquals("value2", source.configStrings().get(100));
    assertEquals(65, source.restart().serverCommandSequence());
    assertEquals("value65", source.configStrings().get(100));
  }

  @Test
  void disconnectIsRaisedOnlyWhenConsumedWithTheOriginalReasonArgument() {
    for (String reason : List.of("", "authored reason")) {
      var peer = new Peer();
      peer.accept(game(0, 91, "initial"));
      var source = peer.source();
      source.initialize(3);
      String text = reason.isEmpty() ? "disconnect" : "disconnect \"authored reason\" ignored";
      peer.accept(new Command(1, text));
      source.refresh();
      assertEquals("initial", source.configStrings().get(100));
      var failure =
          assertThrows(
              RemoteCgameSource.DisconnectedException.class, () -> source.serverCommand(1));
      assertEquals(
          reason.isEmpty() ? "Server disconnected" : "Server disconnected - " + reason,
          failure.getMessage());
      assertEquals("initial", source.configStrings().get(100));
      assertThrows(RemoteCgameSource.DisconnectedException.class, () -> source.serverCommand(1));
    }
  }

  @Test
  void newGamestateInvalidatesTheOldSourceBeforeAnyOutgoingOrPresentationMutation() {
    var peer = new Peer();
    peer.accept(game(0, 91, "first"));
    var inputs = new ArrayList<UserCommand>();
    var source = new RemoteCgameSource(peer.client, inputs::add);
    source.initialize(3);
    peer.accept(new Command(1, "cs 100 old tail"));
    source.serverCommand(1);
    Map<Integer, String> oldView = source.configStrings();
    int gameNumber = peer.nextSequence();
    peer.accept(game(1, 92, "replacement"));
    assertThrows(RemoteCgameSource.LevelChangedException.class, source::refresh);
    assertThrows(RemoteCgameSource.LevelChangedException.class, source::currentSnapshot);
    assertThrows(RemoteCgameSource.LevelChangedException.class, () -> source.snapshot(1));
    assertThrows(RemoteCgameSource.LevelChangedException.class, source::configStrings);
    assertThrows(RemoteCgameSource.LevelChangedException.class, () -> source.serverCommand(1));
    assertThrows(RemoteCgameSource.LevelChangedException.class, source::restart);
    assertThrows(
        RemoteCgameSource.LevelChangedException.class,
        () -> source.userCommand(UserCommand.idle(500)));
    assertThrows(
        RemoteCgameSource.LevelChangedException.class, () -> source.clientCommand("say stale"));
    assertTrue(inputs.isEmpty());
    assertEquals("old tail", oldView.get(100));
    peer.client.queue(List.of());
    assertTrue(peer.readClient().commands().isEmpty());
    var replacement = peer.source();
    assertEquals(new CgameSource.Initialization(3, gameNumber, 1, 0, 0), replacement.initialize(3));
    assertEquals("replacement", replacement.configStrings().get(100));
    assertEquals(new CgameSource.SnapshotNumber(0, 0), replacement.currentSnapshot());
  }

  @Test
  void outgoingCommandsAreQueuedAndInputsAreBorrowedWithoutInvokingALocalGame() {
    var peer = new Peer();
    peer.accept(game(0, 91, "initial"));
    var inputs = new ArrayList<UserCommand>();
    var source = new RemoteCgameSource(peer.client, inputs::add);
    source.initialize(3);
    var command = new UserCommand(1234, 1, 2, 3, 7, 8, -128, 127, 1);
    source.userCommand(command);
    assertEquals(List.of(command), inputs);
    assertSame(command, inputs.getFirst());
    assertFalse(peer.client.hasPendingPacket(), "The host schedules wire transmission");
    source.clientCommand("say \"literal; text\"");
    source.clientCommand("score");
    assertFalse(source.consoleCommand(CommandParser.tokenize("map local")));
    assertTrue(source.additionalEngineCommands().isEmpty());
    peer.client.queue(List.of());
    var message = peer.readClient();
    assertEquals(
        List.of(
            new ClientMessageCodec.Command(1, "say \"literal; text\""),
            new ClientMessageCodec.Command(2, "score")),
        message.commands());
    assertEquals(91, message.serverId());
    peer.client.queue(List.of());
    assertEquals(
        message.commands(),
        peer.readClient().commands(),
        "Source text uses the session's reliable retransmission");
  }

  @Test
  void lifecycleRequiresGamestateAndAssignedClientBeforeItCanConsumeOrSend() {
    var peer = new Peer();
    var source = peer.source();
    assertThrows(IllegalStateException.class, () -> source.initialize(3));
    assertThrows(IllegalStateException.class, source::refresh);
    assertThrows(IllegalStateException.class, source::currentSnapshot);
    assertThrows(IllegalStateException.class, () -> source.snapshot(0));
    assertThrows(IllegalStateException.class, source::configStrings);
    assertThrows(IllegalStateException.class, () -> source.serverCommand(0));
    assertThrows(IllegalStateException.class, source::restart);
    assertThrows(IllegalStateException.class, () -> source.userCommand(UserCommand.idle(0)));
    assertThrows(IllegalStateException.class, () -> source.clientCommand("say early"));
    peer.accept(game(0, 91, "initial"));
    assertThrows(IllegalArgumentException.class, () -> source.initialize(2));
    assertEquals(new CgameSource.Initialization(3, 1, 0, 0, 0), source.initialize(3));
    assertThrows(IllegalStateException.class, () -> source.initialize(3));
    assertEquals(0, source.restart().selectedWeapon());
  }

  private static GameState game(int command, int serverId, String value) {
    return new GameState(
        command, Map.of(1, "\\sv_serverid\\" + serverId, 100, value), Baselines.EMPTY, 3, 73);
  }

  private static SnapshotDeltaCodec.Snapshot snapshot(int sequence, int time, int weapon) {
    byte[] player = new byte[468], entity = new byte[208];
    ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN).putInt(144, weapon);
    ByteBuffer.wrap(entity).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 2);
    return new SnapshotDeltaCodec.Snapshot(
        sequence, time, 4, new byte[] {6}, player, List.of(entity));
  }

  private static final class Peer {
    static final int CHALLENGE = -13579, QPORT = 31234;
    final Protocol68ClientSession client = new Protocol68ClientSession(CHALLENGE, QPORT);
    final Protocol68Channel server =
        new Protocol68Channel(Protocol68Channel.Endpoint.SERVER, QPORT);
    Baselines baselines = Baselines.EMPTY;

    int nextSequence() {
      return server.outgoingSequence();
    }

    RemoteCgameSource source() {
      return new RemoteCgameSource(client, ignored -> {});
    }

    void accept(Operation... operations) {
      var writer = new MessageWriter();
      ServerMessageCodec.write(
          writer, new ServerMessageCodec.Message(0, List.of(operations)), baselines);
      server.queue(LegacyPayloadXor.server(writer.bytes(), CHALLENGE, nextSequence(), ""));
      Protocol68ClientSession.Received result = null;
      while (server.hasPendingPacket()) result = client.receive(server.pollPacket().orElseThrow());
      assertNotNull(result);
      assertEquals(Protocol68ClientSession.Status.ACCEPTED, result.status(), result.diagnostic());
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
      return ClientMessageCodec.read(
          new MessageReader(plain),
          client.gameState().orElseThrow().checksumFeed(),
          ignored -> key);
    }
  }
}
