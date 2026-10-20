package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.game.net.Protocol68Host;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.chan.Protocol68ClientSession;
import dev.bluevista.craftq3.engine.net.chan.Protocol68Handshake;
import dev.bluevista.craftq3.engine.net.oob.ConnectPacketCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessPacket;
import dev.bluevista.craftq3.engine.net.oob.Protocol68Datagram;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** A wire-level protocol-68 client over a loopback end, driving a host frame by frame. */
final class TestClient {
  final LoopbackTransport transport;
  final Harness harness;
  final Protocol68Host host;
  final List<String> commands = new ArrayList<>();
  final List<String> printed = new ArrayList<>();
  Protocol68ClientSession session;
  int time = 1000;
  private final byte[] buffer = new byte[Protocol68.MAX_MSGLEN + 1];

  TestClient(Harness harness, Protocol68Host host, LoopbackTransport transport) {
    this.harness = harness;
    this.host = host;
    this.transport = transport;
  }

  void frame() {
    harness.now += 50;
    harness.clock.advance(50);
    host.frame(harness.now);
  }

  void send(byte[] data) {
    try {
      transport.send(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<byte[]> poll() {
    List<byte[]> packets = new ArrayList<>();
    try {
      int n;
      while ((n = transport.poll(buffer)) != PacketTransport.NONE)
        if (n > 0) packets.add(Arrays.copyOf(buffer, n));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return packets;
  }

  /** Sends a connectionless line and returns the replies' text after their marker. */
  List<String> oob(String line) {
    byte[] request = ConnectionlessPacket.text(line);
    send(line.startsWith("connect ") ? ConnectPacketCodec.compress(request) : request);
    frame();
    List<String> replies = new ArrayList<>();
    for (byte[] packet : poll())
      if (Protocol68Datagram.classify(packet) == Protocol68Datagram.Kind.CONNECTIONLESS)
        replies.add(new String(packet, 4, packet.length - 4, StandardCharsets.ISO_8859_1));
    return replies;
  }

  /** Runs the challenge/connect exchange; returns false with {@link #printed} on refusal. */
  boolean connect(Map<String, String> userinfo, int qport) {
    var handshake = new Protocol68Handshake(1234, qport, userinfo);
    for (int attempt = 0; attempt < 10; attempt++) {
      send(handshake.request());
      frame();
      for (byte[] packet : poll()) {
        var result = handshake.receive(packet);
        if (result == Protocol68Handshake.Result.PRINT) {
          printed.add(handshake.print());
          return false;
        }
      }
      if (handshake.state() == Protocol68Handshake.State.CONNECTED) {
        session = new Protocol68ClientSession(handshake.challenge(), qport);
        return true;
      }
    }
    return false;
  }

  /** Receives everything waiting and records new reliable server commands. */
  void receive() {
    for (byte[] packet : poll()) {
      if (Protocol68Datagram.classify(packet) == Protocol68Datagram.Kind.CONNECTIONLESS) {
        printed.add(new String(packet, 4, packet.length - 4, StandardCharsets.ISO_8859_1));
        continue;
      }
      var received = session.receive(packet);
      for (var command : received.commands()) commands.add(command.text());
    }
  }

  /** Sends one message: queued reliable commands plus movement (or a keepalive). */
  void transmit(List<UserCmd> moves) {
    List<byte[]> canonical = new ArrayList<>();
    if (session.gameState().isPresent())
      for (UserCmd move : moves) canonical.add(move.toCanonical());
    session.queue(canonical);
    while (session.hasPendingPacket()) session.pollPacket().ifPresent(this::send);
  }

  /** One client frame: transmit a move, run the host, receive. */
  void step(int forward) {
    time += 50;
    transmit(List.of(new UserCmd(time, 0, 0, 0, 0, 0, forward, 0, 0)));
    frame();
    receive();
  }

  void stepIdle() {
    transmit(List.of());
    frame();
    receive();
  }
}
