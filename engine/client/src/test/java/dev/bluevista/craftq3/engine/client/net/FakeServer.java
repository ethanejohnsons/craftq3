package dev.bluevista.craftq3.engine.client.net;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.chan.Protocol68ServerSession;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec;
import dev.bluevista.craftq3.engine.net.msg.CommandTokens;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectPacketCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessMessage;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessPacket;
import dev.bluevista.craftq3.engine.net.oob.Protocol68Datagram;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A minimal protocol-68 server end over a loopback transport for client tests: answers the
 * handshake, sends a gamestate, then a snapshot per {@link #tick} with the given entities.
 */
public final class FakeServer {
  public static final int CHALLENGE = 0x1234567;
  public static final int SERVER_ID = 77;

  public final LoopbackTransport transport;
  public final List<String> clientCommands = new ArrayList<>();
  public final List<byte[]> userCommands = new ArrayList<>();
  public Protocol68ServerSession session;
  public boolean silent;
  public int entities = 3;
  private final byte[] buffer = new byte[Protocol68.MAX_MSGLEN + 1];
  private int time = 1000;

  public FakeServer(LoopbackTransport transport) {
    this.transport = transport;
  }

  /** The gamestate configstrings. */
  public static Map<Integer, String> configstrings() {
    Map<Integer, String> strings = new TreeMap<>();
    strings.put(Protocol68.CS_SERVERINFO, "\\mapname\\testmap\\sv_hostname\\fake");
    strings.put(Protocol68.CS_SYSTEMINFO, "\\sv_serverid\\" + SERVER_ID + "\\sv_pure\\0");
    return strings;
  }

  /** Processes client datagrams; then, once connected, sends one snapshot. */
  public void tick() throws IOException {
    receive();
    if (session == null || silent) return;
    time += 50;
    byte[] player = new byte[Protocol68.PLAYER_STATE_BYTES];
    ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN).putInt(0, time);
    List<byte[]> states = new ArrayList<>();
    for (int i = 0; i < entities; i++) {
      byte[] entity = new byte[Protocol68.ENTITY_STATE_BYTES];
      ByteBuffer.wrap(entity).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 10 + i).putInt(8, time);
      states.add(entity);
    }
    session.queueSnapshot(time, 0, new byte[0], player, states);
    flush();
  }

  public void command(String text) throws IOException {
    session.command(text);
  }

  private void receive() throws IOException {
    while (true) {
      int length = transport.poll(buffer);
      if (length < 0) return;
      byte[] datagram = java.util.Arrays.copyOf(buffer, length);
      if (Protocol68Datagram.classify(datagram) == Protocol68Datagram.Kind.CONNECTIONLESS) {
        connectionless(datagram);
        continue;
      }
      if (session == null) continue;
      var received = session.receive(datagram);
      if (received.status() == Protocol68ServerSession.Status.ACCEPTED) {
        for (var command : received.commands()) clientCommands.add(command.text());
        userCommands.addAll(received.userCommands());
      }
    }
  }

  private void connectionless(byte[] datagram) throws IOException {
    if (silent) return;
    byte[] raw = datagram;
    String line = ConnectionlessMessage.parse(datagram).line();
    if (line.startsWith("connect")) {
      raw = ConnectPacketCodec.decompress(datagram);
      line = ConnectionlessMessage.parse(raw).line();
    }
    CommandTokens tokens = CommandTokens.tokenize(line);
    switch (tokens.argument(0)) {
      case "getchallenge" ->
          send("challengeResponse " + CHALLENGE + " " + tokens.argument(1) + " 68");
      case "connect" -> {
        Map<String, String> info = InfoString.parse(tokens.argument(1), Protocol68.MAX_INFO_STRING);
        session = new Protocol68ServerSession(CHALLENGE, Integer.parseInt(info.get("qport")));
        send("connectResponse " + CHALLENGE);
        session.queueGameState(
            new ServerMessageCodec.GameState(
                0, configstrings(), SnapshotDeltaCodec.Baselines.EMPTY, 0, 0),
            SERVER_ID);
        flush();
      }
      default -> {}
    }
  }

  /** Sends {@code disconnect} out of band, as a dropped client receives. */
  public void dropClient() throws IOException {
    send("disconnect");
  }

  private void send(String text) throws IOException {
    byte[] packet = ConnectionlessPacket.text(text);
    transport.send(transport.remoteAddress(), packet, 0, packet.length);
  }

  private void flush() throws IOException {
    while (true) {
      var packet = session.pollPacket();
      if (packet.isEmpty()) return;
      transport.send(transport.remoteAddress(), packet.get(), 0, packet.get().length);
    }
  }
}
