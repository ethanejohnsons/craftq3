package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.platform.net.UdpTransport;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

final class RemoteConnectionTest {
  private static final int CHALLENGE = -13579, NONCE = 123456, QPORT = 32123;
  private static final Map<String, String> INFO =
      Map.of(
          "name",
          "authored loopback player",
          "rate",
          "25000",
          "snaps",
          "20",
          "model",
          "sarge/default");
  private static final RemoteConnection.Settings SETTINGS =
      new RemoteConnection.Settings(100, 1000, 500, 50, 16, 16);

  @Test
  void realLoopbackRetriesConnectsMovesRetransmitsAndClearsInputsOnNewGamestate() throws Exception {
    try (var server = DatagramChannel.open(StandardProtocolFamily.INET)) {
      server.configureBlocking(false);
      server.bind(address(0));
      try (var connection =
          RemoteConnection.open(
              (InetSocketAddress) server.getLocalAddress(), INFO, NONCE, QPORT, 0, SETTINGS)) {
        assertEquals(1, connection.pump(0).sentDatagrams());
        byte[] challenge = receive(server);
        assertEquals(
            "getchallenge " + NONCE + " Quake3Arena",
            ConnectionlessMessage.parse(challenge).line());
        assertEquals(0, connection.pump(99).sentDatagrams());
        assertEquals(1, connection.pump(100).sentDatagrams());
        assertArrayEquals(challenge, receive(server));
        send(
            server,
            connection,
            ConnectionlessPacket.text("challengeResponse " + CHALLENGE + " " + NONCE + " 68"));
        pumpUntil(connection, 101, RemoteConnection.EventKind.STATE);
        assertEquals(RemoteConnection.State.CONNECTING, connection.state());
        String connect =
            ConnectionlessMessage.parse(ConnectPacketCodec.decompress(receive(server))).line();
        assertTrue(connect.contains("\\protocol\\68"));
        assertTrue(connect.contains("\\qport\\" + QPORT));
        send(server, connection, ConnectionlessPacket.text("connectResponse"));
        pumpUntil(connection, 102, RemoteConnection.EventKind.STATE);
        var peer = new Peer();
        assertNull(peer.decode(receive(server), 0, 0, 0, "", 0).movement());
        assertThrows(
            IllegalStateException.class, () -> connection.userCommand(UserCommand.idle(1)));

        for (byte[] packet : peer.packets(0, "", game(0, 91, "abcdef".repeat(1000))))
          send(server, connection, packet);
        var level = pumpUntil(connection, 110, RemoteConnection.EventKind.LEVEL);
        assertTrue(
            level.events().stream().anyMatch(e -> e.kind() == RemoteConnection.EventKind.MESSAGE));
        assertNull(peer.decode(receive(server), 91, 1, 0, "", 73).movement());
        int snapshotSequence = peer.channel.outgoingSequence();
        for (byte[] packet : peer.packets(0, "", new Frame(snapshot(snapshotSequence, 200), null)))
          send(server, connection, packet);
        var frames = pumpUntil(connection, 120, RemoteConnection.EventKind.MESSAGE);
        var frameEvent =
            frames.events().stream()
                .filter(e -> e.kind() == RemoteConnection.EventKind.MESSAGE)
                .findFirst()
                .orElseThrow();
        assertEquals(120, frameEvent.arrivalMillis());
        assertInstanceOf(Frame.class, frameEvent.received().message().operations().getFirst());
        assertEquals(snapshotSequence, connection.lastSnapshotSequence());
        assertEquals(120, connection.lastSnapshotArrivalMillis());

        connection.userCommand(input(201, 100));
        connection.userCommand(input(202, 127));
        assertEquals(1, connection.session().orElseThrow().command("say authored"));
        assertEquals(1, connection.pump(160).sentDatagrams());
        var first = peer.decode(receive(server), 91, snapshotSequence, 0, "", 73);
        assertEquals(List.of(new ClientMessageCodec.Command(1, "say authored")), first.commands());
        assertEquals(2, first.movement().commands().size());
        assertTrue(first.movement().requestDelta());
        assertEquals(127, first.movement().commands().getLast()[21]);
        connection.pump(210);
        var retry = peer.decode(receive(server), 91, snapshotSequence, 0, "", 73);
        assertEquals(first.commands(), retry.commands());
        assertEquals(2, retry.movement().commands().size());
        for (byte[] packet :
            peer.packets(1, "say authored", new Command(1, "print inert server text")))
          send(server, connection, packet);
        pumpUntil(connection, 220, RemoteConnection.EventKind.MESSAGE);
        assertEquals(1, connection.session().orElseThrow().reliableAcknowledge());
        connection.pump(260);
        var acknowledged = peer.decode(receive(server), 91, 3, 1, "print inert server text", 73);
        assertTrue(acknowledged.commands().isEmpty());

        for (byte[] packet : peer.packets(1, "say authored", game(1, 92, "new level")))
          send(server, connection, packet);
        pumpUntil(connection, 270, RemoteConnection.EventKind.LEVEL);
        assertEquals(0, connection.lastSnapshotSequence());
        assertEquals(-1, connection.lastSnapshotArrivalMillis());
        var newLevel = peer.decode(receive(server), 92, 4, 1, "print inert server text", 73);
        assertNull(newLevel.movement());
        connection.userCommand(input(1, 42));
        connection.pump(320);
        var newInput = peer.decode(receive(server), 92, 4, 1, "print inert server text", 73);
        assertEquals(1, newInput.movement().commands().size());
        assertEquals(
            1,
            ByteBuffer.wrap(newInput.movement().commands().getFirst())
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt());
        assertEquals(RemoteConnection.State.TIMED_OUT, connection.pump(770).state());
        assertTrue(connection.diagnostic().contains("No valid server activity"));
      }
    }
  }

  @Test
  void wouldBlockRetainsBytesAndDefersInboundAcknowledgementUntilSocketAcceptance()
      throws Exception {
    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, SETTINGS);
    var peer = new Peer();
    deliver(endpoint, peer.packets(0, "", game(0, 91, "fixture")));
    connection.pump(3);
    endpoint.sent.clear();
    connection.userCommand(input(10, 127));
    connection.session().orElseThrow().command("reliable once");
    endpoint.writable = false;
    assertEquals(0, connection.pump(53).sentDatagrams());
    byte[] firstAttempt = endpoint.attempts.getLast();
    deliver(endpoint, peer.packets(1, "reliable once", NoOp.INSTANCE));
    int priorPolls = endpoint.pollCalls;
    var blocked = connection.pump(54);
    assertEquals(0, blocked.receivedDatagrams());
    assertEquals(priorPolls, endpoint.pollCalls);
    assertEquals(0, connection.session().orElseThrow().reliableAcknowledge());
    assertArrayEquals(firstAttempt, endpoint.attempts.getLast());
    endpoint.writable = true;
    var accepted = connection.pump(55);
    assertEquals(1, accepted.sentDatagrams());
    assertEquals(1, accepted.receivedDatagrams());
    assertArrayEquals(firstAttempt, endpoint.sent.getFirst());
    assertEquals(1, connection.session().orElseThrow().reliableAcknowledge());
    connection.close();
    connection.close();
    assertEquals(1, endpoint.closeCalls);
  }

  @Test
  void fragmentBudgetsHoldNewLevelUntilPendingMessageIsSent() throws Exception {
    var limits = new RemoteConnection.Settings(100, 1000, 1000, 50, 1, 1);
    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, limits);
    var peer = new Peer();
    deliver(endpoint, peer.packets(0, "", game(0, 91, "old")));
    connection.pump(3);
    connection.userCommand(input(30, 127));
    Random random = new Random(68);
    for (int i = 0; i < 4; i++) {
      var text = new StringBuilder();
      for (int j = 0; j < 900; j++) text.append((char) ('a' + random.nextInt(26)));
      connection.session().orElseThrow().command(text.toString());
    }
    assertEquals(1, connection.pump(53).sentDatagrams());
    assertTrue(connection.session().orElseThrow().hasPendingPacket());
    deliver(endpoint, peer.packets(0, "", game(0, 92, "new")));
    int received = 0;
    for (long now = 54; now < 80; now++) {
      boolean pending = connection.session().orElseThrow().hasPendingPacket();
      var result = connection.pump(now);
      assertTrue(result.sentDatagrams() <= 1);
      assertTrue(result.receivedDatagrams() <= 1);
      received += result.receivedDatagrams();
      if (connection.session().orElseThrow().serverId() == 92) break;
      if (pending) assertEquals(0, result.receivedDatagrams());
    }
    assertEquals(1, received);
    assertEquals(92, connection.session().orElseThrow().serverId());
    assertEquals(-1, connection.lastSnapshotArrivalMillis());
    connection.close();
  }

  @Test
  void newest32InputsAreCanonicalOrderedAndSameTimeReplacesTheLastSample() throws Exception {
    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, SETTINGS);
    var peer = new Peer();
    deliver(endpoint, peer.packets(0, "", game(0, 91, "fixture")));
    connection.pump(3);
    for (int i = 1; i <= 40; i++) connection.userCommand(input(i, 100));
    connection.userCommand(new UserCommand(40, -1, 0x12345678, -65536, -1, 255, -128, 127, -128));
    connection.pump(53);
    byte[] packet = endpoint.sent.getLast();
    var decoded = peer.decode(packet, 91, 1, 0, "", 73);
    var commands = decoded.movement().commands();
    assertEquals(32, commands.size());
    assertEquals(9, ByteBuffer.wrap(commands.getFirst()).order(ByteOrder.LITTLE_ENDIAN).getInt());
    var last = ByteBuffer.wrap(commands.getLast()).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(40, last.getInt());
    assertEquals(65535, last.getInt());
    assertEquals(0x5678, last.getInt());
    assertEquals(0, last.getInt());
    assertEquals(65535, last.getInt());
    assertEquals(255, Byte.toUnsignedInt(last.get()));
    assertEquals(-127, last.get());
    assertEquals(127, last.get());
    assertEquals(-127, last.get());
    connection.userCommand(input(5, 42));
    connection.pump(103);
    var reset = peer.decode(endpoint.sent.getLast(), 91, 1, 0, "", 73).movement().commands();
    assertEquals(1, reset.size());
    assertEquals(5, ByteBuffer.wrap(reset.getFirst()).order(ByteOrder.LITTLE_ENDIAN).getInt());
    assertDoesNotThrow(() -> connection.userCommand(UserCommand.idle(Integer.MIN_VALUE)));
    assertDoesNotThrow(() -> connection.userCommand(UserCommand.idle(Integer.MIN_VALUE)));
    connection.close();
  }

  @Test
  void onlyValidatedActivityExtendsTimeoutAndPrintRemainsInert() throws Exception {
    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, SETTINGS);
    endpoint.incoming.add(
        new UdpTransport.Poll(UdpTransport.Status.PACKET, new byte[] {0, 0, 0, 0}));
    endpoint.incoming.add(new UdpTransport.Poll(UdpTransport.Status.OVERSIZE, new byte[0]));
    endpoint.incoming.add(new UdpTransport.Poll(UdpTransport.Status.EMPTY_DATAGRAM, new byte[0]));
    endpoint.offer(ConnectionlessPacket.text("disconnect"));
    var rejected = connection.pump(400);
    assertEquals(3, rejected.events().size());
    assertTrue(
        rejected.events().stream().allMatch(e -> e.kind() == RemoteConnection.EventKind.REJECTED));
    assertEquals(RemoteConnection.State.CONNECTED, connection.state());
    assertEquals(RemoteConnection.State.TIMED_OUT, connection.pump(502).state());
    assertEquals(1, endpoint.closeCalls);

    var denial = new FakeEndpoint();
    var exchange = new RemoteConnection(denial, INFO, NONCE, QPORT, 0, SETTINGS);
    denial.offer(ConnectionlessPacket.text("print\nServer is full; quit; exec dangerous.cfg"));
    var printed = exchange.pump(900);
    assertEquals(
        "Server is full; quit; exec dangerous.cfg", printed.events().getFirst().diagnostic());
    assertEquals(RemoteConnection.EventKind.PRINT, printed.events().getFirst().kind());
    assertEquals(RemoteConnection.State.CHALLENGING, exchange.state());
    assertEquals(RemoteConnection.State.TIMED_OUT, exchange.pump(1000).state());
  }

  @Test
  void receiveWorkIsBoundedAndTransportErrorsBecomeTerminalDiagnostics() throws Exception {
    var limits = new RemoteConnection.Settings(100, 1000, 500, 50, 2, 1);
    var endpoint = new FakeEndpoint();
    var connection = new RemoteConnection(endpoint, INFO, NONCE, QPORT, 0, limits);
    for (int i = 0; i < 5; i++) endpoint.offer(new byte[] {1});
    var result = connection.pump(0);
    assertEquals(2, result.receivedDatagrams());
    assertEquals(1, result.sentDatagrams());
    assertEquals(3, endpoint.incoming.size());
    assertThrows(UnsupportedOperationException.class, () -> result.events().clear());
    assertThrows(IllegalArgumentException.class, () -> connection.pump(-1));
    endpoint.failPoll = true;
    var failed = connection.pump(1);
    assertEquals(RemoteConnection.State.FAILED, failed.state());
    assertEquals(RemoteConnection.EventKind.IO_ERROR, failed.events().getFirst().kind());
    assertEquals(1, endpoint.closeCalls);
    assertEquals(0, connection.pump(1000).receivedDatagrams());
    connection.close();
    assertEquals(1, endpoint.closeCalls);
  }

  @Test
  void explicitDisconnectFlushesReliableTextWithoutOldMovementAndAlwaysCloses() throws Exception {
    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, SETTINGS);
    var peer = new Peer();
    deliver(endpoint, peer.packets(0, "", game(0, 91, "fixture")));
    connection.pump(3);
    connection.userCommand(input(40, 127));
    var closed = connection.disconnectAndClose(4);
    assertEquals(RemoteConnection.State.CLOSED, closed.state());
    assertEquals(1, closed.sentDatagrams());
    var packet = peer.decode(endpoint.sent.getLast(), 91, 1, 0, "", 73);
    assertNull(packet.movement());
    assertEquals(List.of(new ClientMessageCodec.Command(1, "disconnect")), packet.commands());
    assertEquals(1, endpoint.closeCalls);
    var blockedEndpoint = new FakeEndpoint();
    var blocked = connected(blockedEndpoint, SETTINGS);
    blockedEndpoint.writable = false;
    assertEquals(0, blocked.disconnectAndClose(3).sentDatagrams());
    assertEquals(1, blockedEndpoint.closeCalls);
  }

  @Test
  void connectingRetriesTheSameCompressedPacketAndReportsAnInertDenial() throws Exception {
    var endpoint = new FakeEndpoint();
    var connection = new RemoteConnection(endpoint, INFO, NONCE, QPORT, 0, SETTINGS);
    connection.pump(0);
    endpoint.offer(
        ConnectionlessPacket.text("challengeResponse " + CHALLENGE + " " + NONCE + " 68"));
    connection.pump(1);
    byte[] request = endpoint.sent.getLast();
    assertEquals(0, connection.pump(100).sentDatagrams());
    assertEquals(1, connection.pump(101).sentDatagrams());
    assertArrayEquals(request, endpoint.sent.getLast());
    endpoint.offer(ConnectionlessPacket.text("print\nPassword required; exec remains text"));
    var result = connection.pump(102);
    assertEquals(RemoteConnection.State.CONNECTING, result.state());
    assertEquals(RemoteConnection.EventKind.PRINT, result.events().getFirst().kind());
    assertEquals("Password required; exec remains text", result.events().getFirst().diagnostic());
    assertEquals(RemoteConnection.State.TIMED_OUT, connection.pump(1000).state());
    assertEquals(1, endpoint.closeCalls);
  }

  @Test
  void expiredPendingOutputIsNotSentAndFinalGamestateClearsEarlierFrameArrival() throws Exception {
    var blocked = new FakeEndpoint();
    blocked.writable = false;
    var exchange = new RemoteConnection(blocked, INFO, NONCE, QPORT, 0, SETTINGS);
    exchange.pump(0);
    blocked.writable = true;
    var expired = exchange.pump(1000);
    assertEquals(RemoteConnection.State.TIMED_OUT, expired.state());
    assertEquals(0, expired.sentDatagrams());
    assertTrue(blocked.sent.isEmpty());

    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, SETTINGS);
    var peer = new Peer();
    deliver(endpoint, peer.packets(0, "", game(0, 91, "old")));
    connection.pump(3);
    int sequence = peer.channel.outgoingSequence();
    deliver(
        endpoint, peer.packets(0, "", new Frame(snapshot(sequence, 10), null), game(0, 92, "new")));
    var result = connection.pump(4);
    assertTrue(
        result.events().stream().anyMatch(e -> e.kind() == RemoteConnection.EventKind.MESSAGE));
    assertTrue(connection.session().orElseThrow().snapshot().isEmpty());
    assertEquals(0, connection.lastSnapshotSequence());
    assertEquals(-1, connection.lastSnapshotArrivalMillis());
    connection.close();
  }

  @Test
  void snapshotPingUsesTheQueuedInputAndSuccessfulSendTimeAcrossWouldBlock() throws Exception {
    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, SETTINGS);
    var peer = new Peer();
    deliver(endpoint, peer.packets(0, "", game(0, 91, "fixture")));
    connection.pump(3);
    connection.userCommand(input(100, 127));
    endpoint.writable = false;
    connection.pump(53);
    connection.userCommand(input(200, 42));
    endpoint.writable = true;
    connection.pump(60);
    int sequence = peer.channel.outgoingSequence();
    byte[] player = new byte[468];
    ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN).putInt(100);
    deliver(
        endpoint,
        peer.packets(
            0,
            "",
            new Frame(new Snapshot(sequence, 300, 0, new byte[0], player, List.of()), null)));
    connection.pump(70);
    assertEquals(10, connection.snapshotPing(sequence));
    assertEquals(999, connection.snapshotPing(sequence + 1));
    deliver(endpoint, peer.packets(0, "", game(0, 92, "next")));
    connection.pump(80);
    assertEquals(999, connection.snapshotPing(sequence));
    connection.close();
  }

  @Test
  void fragmentedMessagePingStartsAtItsFirstAcceptedDatagram() throws Exception {
    var limits = new RemoteConnection.Settings(100, 1000, 500, 50, 16, 1);
    var endpoint = new FakeEndpoint();
    var connection = connected(endpoint, limits);
    var peer = new Peer();
    deliver(endpoint, peer.packets(0, "", game(0, 91, "fixture")));
    connection.pump(3);
    connection.userCommand(input(100, 127));
    var random = new Random(6811);
    for (int i = 0; i < 4; i++) {
      var command = new StringBuilder();
      for (int j = 0; j < 900; j++) command.append((char) ('a' + random.nextInt(26)));
      connection.session().orElseThrow().command(command.toString());
    }
    connection.pump(53);
    assertTrue(connection.session().orElseThrow().hasPendingPacket());
    connection.userCommand(input(200, 42));
    for (long now = 54; connection.session().orElseThrow().hasPendingPacket(); now++) {
      assertTrue(now < 70);
      connection.pump(now);
    }
    int sequence = peer.channel.outgoingSequence();
    byte[] player = new byte[468];
    ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN).putInt(100);
    deliver(
        endpoint,
        peer.packets(
            0,
            "",
            new Frame(new Snapshot(sequence, 300, 0, new byte[0], player, List.of()), null)));
    connection.pump(100);
    assertEquals(47, connection.snapshotPing(sequence));
    connection.close();
  }

  @Test
  void endpointAndSchedulingBoundsFailBeforeOpeningOrTransferringOwnership() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteConnection.open(
                InetSocketAddress.createUnresolved("no-dns.invalid", 27960),
                INFO,
                NONCE,
                QPORT,
                0));
    var endpoint = new FakeEndpoint();
    assertThrows(
        IllegalArgumentException.class,
        () -> new RemoteConnection(endpoint, INFO, NONCE, QPORT, -1, SETTINGS));
    assertEquals(0, endpoint.closeCalls);
    assertThrows(
        IllegalArgumentException.class, () -> new RemoteConnection.Settings(0, 100, 100, 10, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RemoteConnection.Settings(100, 99, 100, 10, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RemoteConnection.Settings(100, 100, 100, 10, 257, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RemoteConnection.Settings(100, 100, 100, 10, 1, 0));
  }

  private static GameState game(int sequence, int id, String value) {
    return new GameState(
        sequence, Map.of(1, "\\sv_serverid\\" + id, 100, value), Baselines.EMPTY, 0, 73);
  }

  private static Snapshot snapshot(int sequence, int time) {
    return new Snapshot(sequence, time, 0, new byte[0], new byte[468], List.of());
  }

  private static UserCommand input(int time, int forward) {
    return new UserCommand(time, 0, 1, 0, 0, 2, forward, 0, 0);
  }

  private static InetSocketAddress address(int port) throws Exception {
    return new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port);
  }

  private static RemoteConnection connected(
      FakeEndpoint endpoint, RemoteConnection.Settings limits) {
    var connection = new RemoteConnection(endpoint, INFO, NONCE, QPORT, 0, limits);
    connection.pump(0);
    endpoint.offer(
        ConnectionlessPacket.text("challengeResponse " + CHALLENGE + " " + NONCE + " 68"));
    connection.pump(1);
    endpoint.offer(ConnectionlessPacket.text("connectResponse"));
    connection.pump(2);
    assertEquals(RemoteConnection.State.CONNECTED, connection.state());
    return connection;
  }

  private static void deliver(FakeEndpoint endpoint, List<byte[]> packets) {
    packets.forEach(endpoint::offer);
  }

  private static void send(DatagramChannel peer, RemoteConnection connection, byte[] packet)
      throws Exception {
    assertEquals(packet.length, peer.send(ByteBuffer.wrap(packet), connection.localAddress()));
  }

  private static byte[] receive(DatagramChannel channel) throws Exception {
    ByteBuffer b = ByteBuffer.allocate(16385);
    long until = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < until) {
      if (channel.receive(b) != null) return Arrays.copyOf(b.array(), b.position());
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("No loopback datagram");
  }

  private static RemoteConnection.PumpResult pumpUntil(
      RemoteConnection connection, long now, RemoteConnection.EventKind kind) {
    long until = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < until) {
      var result = connection.pump(now);
      if (result.events().stream().anyMatch(e -> e.kind() == kind)) return result;
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("No loopback event " + kind);
  }

  private static final class FakeEndpoint implements RemoteConnection.Endpoint {
    final ArrayDeque<UdpTransport.Poll> incoming = new ArrayDeque<>();
    final List<byte[]> sent = new ArrayList<>(), attempts = new ArrayList<>();
    boolean writable = true, failPoll;
    int pollCalls, closeCalls;

    public InetSocketAddress peer() {
      try {
        return address(27960);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    public InetSocketAddress localAddress() {
      try {
        return address(12345);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    void offer(byte[] p) {
      incoming.add(new UdpTransport.Poll(UdpTransport.Status.PACKET, p));
    }

    public UdpTransport.Poll poll() throws IOException {
      pollCalls++;
      if (failPoll) throw new IOException("authored endpoint failure");
      return incoming.isEmpty()
          ? new UdpTransport.Poll(UdpTransport.Status.EMPTY, new byte[0])
          : incoming.removeFirst();
    }

    public boolean send(byte[] p) {
      attempts.add(p.clone());
      if (!writable) return false;
      sent.add(p.clone());
      return true;
    }

    public void close() {
      closeCalls++;
    }
  }

  private static final class Peer {
    final Protocol68Channel channel =
        new Protocol68Channel(Protocol68Channel.Endpoint.SERVER, QPORT);

    List<byte[]> packets(int acknowledge, String key, Operation... operations) {
      var writer = new MessageWriter();
      ServerMessageCodec.write(
          writer, new Message(acknowledge, List.of(operations)), Baselines.EMPTY);
      channel.queue(
          LegacyPayloadXor.server(writer.bytes(), CHALLENGE, channel.outgoingSequence(), key));
      var packets = new ArrayList<byte[]>();
      while (channel.hasPendingPacket()) packets.add(channel.pollPacket().orElseThrow());
      return packets;
    }

    ClientMessageCodec.Message decode(
        byte[] packet, int serverId, int ack, int commandAck, String key, int feed) {
      var result = channel.receive(packet);
      assertEquals(Protocol68Channel.Status.COMPLETE, result.status());
      byte[] plain = LegacyPayloadXor.client(result.payload(), CHALLENGE, serverId, ack, key);
      var message = ClientMessageCodec.read(new MessageReader(plain), feed, ignored -> key);
      assertEquals(serverId, message.serverId());
      assertEquals(ack, message.messageAcknowledge());
      assertEquals(commandAck, message.serverCommandAcknowledge());
      return message;
    }
  }
}
