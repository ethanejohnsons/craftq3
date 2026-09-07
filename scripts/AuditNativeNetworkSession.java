import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.platform.net.UdpTransport;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Authored Java client; only the private loopback server created by the companion script. */
public class AuditNativeNetworkSession {
  private static int droppedSend, droppedReceive;
  private static boolean injectLoss;
  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    Path output = Path.of(args[1]);
    var peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
    var info = new TreeMap<>(Map.of("name", "CraftQ3 wire QA", "rate", "25000", "snaps", "20",
        "model", "sarge/default", "headmodel", "sarge/default", "color1", "4", "color2", "5",
        "handicap", "100", "cg_predictItems", "1"));
    var handshake = new Protocol68Handshake(135792468, 32123, info);
    var capture = new ArrayList<String>();
    try (var socket = UdpTransport.open(peer)) {
      long deadline = System.nanoTime() + 8_000_000_000L, nextRequest = 0;
      while (handshake.state() != Protocol68Handshake.State.CONNECTED) {
        if (System.nanoTime() >= deadline) throw new AssertionError("Handshake timed out");
        if (System.nanoTime() >= nextRequest) {
          send(socket, handshake.request(), capture);
          nextRequest = System.nanoTime() + 500_000_000L;
        }
        byte[] received = receive(socket, capture);
        if (received == null) continue;
        var result = handshake.receive(received);
        if (result == Protocol68Handshake.Result.CHALLENGE_ACCEPTED) nextRequest = 0;
        if (result != Protocol68Handshake.Result.IGNORED) System.out.println("HANDSHAKE " + result);
        if (result == Protocol68Handshake.Result.PRINT) throw new AssertionError(handshake.print());
      }
      injectLoss = true;
      var session = new Protocol68ClientSession(handshake.challenge(), handshake.qport());
      int frames = 0, fragments = 0, commands = 0, serverTime = 0, lastSequence = 0;
      int deltaFrames = 0, fullFrames = 0, reliableSent = 0, gameStates = 0;
      float[] previous = null;
      double distance = 0;
      boolean game = false, requestedFull = false, recoveredFull = false;
      long nextSend = 0;
      deadline = System.nanoTime() + 12_000_000_000L;
      while (System.nanoTime() < deadline) {
        if (System.nanoTime() >= nextSend) {
          List<byte[]> movement = List.of();
          if (game) {
            byte[] user = new byte[24];
            ByteBuffer.wrap(user).order(ByteOrder.LITTLE_ENDIAN).putInt(serverTime);
            user[20] = 2;
            user[21] = (byte) ((frames / 40 & 1) == 0 ? 127 : -127);
            movement = List.of(user);
            if (reliableSent < 130) { session.command("score"); reliableSent++; }
          }
          session.queue(movement);
          while (session.hasPendingPacket()) send(socket, session.pollPacket().orElseThrow(), capture);
          nextSend = System.nanoTime() + 50_000_000L;
          serverTime += 50;
        }
        byte[] datagram = receive(socket, capture);
        if (datagram == null) continue;
        var received = session.receive(datagram);
        if (received.status() == Protocol68ClientSession.Status.REJECTED)
          throw new AssertionError("Rejected native packet: " + received.diagnostic());
        if (received.status() == Protocol68ClientSession.Status.PARTIAL) { fragments++; continue; }
        if (received.status() != Protocol68ClientSession.Status.ACCEPTED) continue;
        commands += received.commands().size();
        for (var op : received.message().operations()) {
          if (op instanceof ServerMessageCodec.GameState level) {
            game = true;
            gameStates++;
            previous = null;
            serverTime = 0;
            String expectedMap = gameStates == 1 ? "q3dm1" : "q3dm17";
            if (level.clientNumber() != 0 || !level.configstrings().get(0).contains("\\mapname\\" + expectedMap))
              throw new AssertionError("Wrong private game");
            System.out.println("GAMESTATE id=" + session.serverId() + " strings=" + level.configstrings().size()
                + " baselines=" + level.baselines().entities().size());
          } else if (op instanceof ServerMessageCodec.Frame frame) {
            var current = frame.current();
            if (frame.previous() == null) { fullFrames++; if (requestedFull && gameStates == 1) recoveredFull = true; }
            else deltaFrames++;
            frames++;
            if (current.sequence() <= lastSequence) throw new AssertionError("Snapshot reordered");
            lastSequence = current.sequence();
            serverTime = Math.max(serverTime, current.time());
            var player = ByteBuffer.wrap(current.player()).order(ByteOrder.LITTLE_ENDIAN);
            float[] xyz = {player.getFloat(20), player.getFloat(24), player.getFloat(28)};
            if (previous != null) distance += Math.sqrt(Math.pow(xyz[0] - previous[0], 2)
                + Math.pow(xyz[1] - previous[1], 2) + Math.pow(xyz[2] - previous[2], 2));
            previous = xyz;
            if (frames == 100) { session.requestFullSnapshot(); requestedFull = true; }
            if (frames == 150) System.out.println("CHANGE_LEVEL q3dm17");
            if (frames % 40 == 0) System.out.println("FRAME count=" + frames + " time=" + current.time()
                + " reliableAck=" + session.reliableAcknowledge() + " distance=" + distance);
          }
        }
      }
      if (!game || gameStates != 2 || droppedSend < 3 || droppedReceive < 3 || frames < 180 || fragments == 0 || distance < 1000 || deltaFrames < 100
          || session.reliableAcknowledge() != 130 || commands < 65 || !recoveredFull)
        throw new AssertionError("Incomplete native exchange: frames=" + frames + " distance=" + distance
            + " reliable=" + session.reliableAcknowledge() + " commands=" + commands + " delta=" + deltaFrames);
      int disconnect = session.command("disconnect");
      for (int i = 0; i < 3; i++) {
        session.queue(List.of());
        while (session.hasPendingPacket()) send(socket, session.pollPacket().orElseThrow(), capture);
      }
      String result = "{\"gameStates\":" + gameStates + ",\"droppedSend\":" + droppedSend
          + ",\"droppedReceive\":" + droppedReceive + ",\"frames\":" + frames + ",\"deltaFrames\":" + deltaFrames + ",\"fullFrames\":" + fullFrames
          + ",\"fragmentedMessages\":" + fragments + ",\"distance\":" + distance + ",\"clientReliableAck\":"
          + session.reliableAcknowledge() + ",\"serverCommands\":" + commands + ",\"recoveredFull\":"
          + recoveredFull + ",\"disconnectSequence\":" + disconnect + "}";
      Files.writeString(output.resolve("result.json"), result + "\n");
      Files.write(output.resolve("packets.txt"), capture);
      System.out.println("PASS " + result);
    }
  }
  private static void send(UdpTransport socket, byte[] bytes, List<String> capture) throws Exception {
    int sequence = bytes.length < 4 ? -1 : ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    if (injectLoss && sequence > 0 && sequence % 17 == 0) {
      droppedSend++;
      capture.add("droppedSend " + HexFormat.of().formatHex(bytes));
      return;
    }
    long deadline = System.nanoTime() + 500_000_000L;
    while (!socket.send(bytes)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Loopback write blocked");
      Thread.sleep(1);
    }
    capture.add("send " + HexFormat.of().formatHex(bytes));
  }
  private static byte[] receive(UdpTransport socket, List<String> capture) throws Exception {
    long deadline = System.nanoTime() + 25_000_000L;
    do {
      var packet = socket.poll();
      if (packet.status() == UdpTransport.Status.PACKET) {
        byte[] received = packet.payload();
        int sequence = received.length < 4 ? -1 : ByteBuffer.wrap(received).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (injectLoss && sequence > 0 && sequence % 37 == 0) {
          droppedReceive++;
          capture.add("droppedReceive " + HexFormat.of().formatHex(received));
          continue;
        }
        capture.add("receive " + HexFormat.of().formatHex(received));
        return received;
      }
      if (packet.status() != UdpTransport.Status.EMPTY)
        throw new AssertionError("Invalid native UDP envelope: " + packet.status());
      Thread.sleep(1);
    } while (System.nanoTime() < deadline);
    return null;
  }
}
