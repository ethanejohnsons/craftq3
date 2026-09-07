import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.platform.net.UdpTransport;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Authored interactive protocol client restricted to the private loopback behavior oracle. */
class PureWireProbe {
  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.ISO_8859_1));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("PureWireProbe <loopback-port>");
    var actions = new ConcurrentLinkedQueue<String>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var input = new BufferedReader(new InputStreamReader(System.in))) {
                for (String line; (line = input.readLine()) != null; ) actions.add(line);
              } catch (IOException failure) {
                actions.add("QUIT");
              }
            });
    var peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Integer.parseInt(args[0]));
    var info =
        new TreeMap<>(
            Map.of(
                "name",
                "CraftQ3 pure QA",
                "rate",
                "25000",
                "snaps",
                "20",
                "model",
                "sarge/default",
                "headmodel",
                "sarge/default",
                "handicap",
                "100"));
    var handshake = new Protocol68Handshake(135792468, 32123, info);
    try (var socket = UdpTransport.open(peer)) {
      long deadline = System.nanoTime() + 8_000_000_000L, nextRequest = 0;
      while (handshake.state() != Protocol68Handshake.State.CONNECTED) {
        if (System.nanoTime() >= deadline) throw new AssertionError("Private handshake timed out");
        if (System.nanoTime() >= nextRequest) {
          send(socket, handshake.request());
          nextRequest = System.nanoTime() + 500_000_000L;
        }
        var packet = socket.poll();
        if (packet.status() != UdpTransport.Status.PACKET) {
          Thread.sleep(1);
          continue;
        }
        var result = handshake.receive(packet.payload());
        if (result == Protocol68Handshake.Result.CHALLENGE_ACCEPTED) nextRequest = 0;
        if (result == Protocol68Handshake.Result.PRINT) throw new AssertionError(handshake.print());
      }
      System.out.println("CONNECTED challenge=" + handshake.challenge());
      var session = new Protocol68ClientSession(handshake.challenge(), handshake.qport());
      boolean movement = false, running = true;
      int serverTime = 0, frames = 0, games = 0;
      long nextSend = 0;
      deadline = System.nanoTime() + 60_000_000_000L;
      while (running && System.nanoTime() < deadline) {
        for (String action; (action = actions.poll()) != null; ) {
          if (action.equals("BEGIN")) movement = true;
          else if (action.equals("PAUSE")) movement = false;
          else if (action.equals("QUIT")) running = false;
          else if (action.startsWith("BATCH ")) {
            String text =
                new String(
                    Base64.getDecoder().decode(action.substring(6)), StandardCharsets.ISO_8859_1);
            for (String command : text.split("\n")) {
              int sequence = session.command(command);
              System.out.println("SENT_COMMAND " + sequence + " " + encoded(command));
            }
            nextSend = 0;
          } else if (action.startsWith("CMD ")) {
            String command = action.substring(4);
            int sequence = session.command(command);
            System.out.println("SENT_COMMAND " + sequence + " " + encoded(command));
            nextSend = 0;
          } else throw new IllegalArgumentException("Unknown authored control action");
        }
        if (System.nanoTime() >= nextSend) {
          List<byte[]> commands = List.of();
          if (movement && session.gameState().isPresent()) {
            var user = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            user.putInt(0, serverTime);
            user.put(20, (byte) 2);
            user.put(21, (byte) 64);
            commands = List.of(user.array());
          }
          session.queue(commands);
          while (session.hasPendingPacket()) send(socket, session.pollPacket().orElseThrow());
          nextSend = System.nanoTime() + 50_000_000L;
          serverTime += 50;
        }
        var packet = socket.poll();
        if (packet.status() != UdpTransport.Status.PACKET) {
          Thread.sleep(1);
          continue;
        }
        var received = session.receive(packet.payload());
        if (received.status() == Protocol68ClientSession.Status.REJECTED) {
          System.out.println(
              "REJECTED "
                  + encoded(received.diagnostic())
                  + " "
                  + HexFormat.of().formatHex(packet.payload()));
          continue;
        }
        if (received.status() != Protocol68ClientSession.Status.ACCEPTED) continue;
        for (var command : received.commands())
          System.out.println(
              "SERVER_COMMAND " + command.sequence() + " " + encoded(command.text()));
        for (var operation : received.message().operations()) {
          if (operation instanceof ServerMessageCodec.GameState game) {
            games++;
            serverTime = 0;
            System.out.println(
                "GAMESTATE "
                    + games
                    + " "
                    + session.serverId()
                    + " "
                    + game.checksumFeed()
                    + " "
                    + encoded(game.configstrings().getOrDefault(0, ""))
                    + " "
                    + encoded(game.configstrings().getOrDefault(1, "")));
          } else if (operation instanceof ServerMessageCodec.Frame frame) {
            frames++;
            serverTime = Math.max(serverTime, frame.current().time());
            var player = ByteBuffer.wrap(frame.current().player()).order(ByteOrder.LITTLE_ENDIAN);
            System.out.println(
                "FRAME "
                    + frames
                    + " "
                    + frame.current().time()
                    + " "
                    + session.serverId()
                    + " "
                    + session.reliableAcknowledge()
                    + " "
                    + player.getInt(0)
                    + " "
                    + player.getFloat(20)
                    + " "
                    + player.getFloat(24)
                    + " "
                    + player.getFloat(28));
          }
        }
      }
      System.out.println(
          "END games=" + games + " frames=" + frames + " ack=" + session.reliableAcknowledge());
    }
  }

  private static void send(UdpTransport socket, byte[] bytes) throws Exception {
    long deadline = System.nanoTime() + 500_000_000L;
    while (!socket.send(bytes)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Loopback write blocked");
      Thread.sleep(1);
    }
  }
}
