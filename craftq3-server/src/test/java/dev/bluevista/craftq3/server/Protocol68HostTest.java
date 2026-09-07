package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.server.net.Protocol68Host;
import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Protocol68HostTest {
  @TempDir Path directory;

  @Test
  void challengeRetriesRemainStableAndCannotAuthorizeAnotherPortOrExpiredConnect()
      throws Exception {
    try (var fixture = fixture()) {
      var host = fixture.host;
      var peer = peer(30001);
      host.receive(peer, ConnectionlessPacket.text("getchallenge 42"), 0);
      String reply = reply(host);
      int challenge = Integer.parseInt(reply.split(" ")[1]);
      assertTrue(reply.endsWith(" 42 68"));
      host.receive(peer, ConnectionlessPacket.text("getchallenge 42"), 50);
      assertEquals(reply, reply(host));
      var info = new java.util.LinkedHashMap<String, String>();
      info.put("protocol", "68");
      info.put("qport", "100");
      info.put("challenge", Integer.toString(challenge));
      info.put("name", "Test player");
      info.put("model", "sarge/default");
      info.put("headmodel", "sarge/default");
      info.put("rate", "25000");
      info.put("snaps", "20");
      info.put("handicap", "100");
      info.put("teamtask", "0");
      info.put("color1", "4");
      info.put("color2", "5");
      String text = dev.bluevista.craftq3.core.cvar.InfoString.encode(info, 1024);
      byte[] connect =
          ConnectPacketCodec.compress(
              ConnectionlessPacket.text("connect " + (char) 34 + text + (char) 34));
      host.receive(peer(30002), connect, 100);
      assertTrue(reply(host).startsWith("print"));
      host.receive(peer, connect, 30001);
      assertTrue(reply(host).startsWith("print"));
      assertTrue(host.clients().isEmpty());
      assertFalse(fixture.game.isConnected(0));
    }
  }

  @Test
  void discoveryRateLimitRecoversWithoutAdmittingPlayers() throws Exception {
    try (var fixture = fixture()) {
      var host = fixture.host;
      var peer = peer(30001);
      for (int i = 0; i < 10; i++) {
        host.receive(peer, ConnectionlessPacket.text("getinfo nonce"), 0);
        var packet = host.peekPacket().orElseThrow();
        assertEquals(peer, packet.peer());
        var message = ConnectionlessMessage.parse(packet.payload());
        assertEquals("infoResponse", message.line());
        assertTrue(
            new String(message.body(), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("\\challenge\\nonce"));
        host.packetSent();
      }
      host.receive(peer, ConnectionlessPacket.text("getinfo nonce"), 999);
      assertTrue(host.peekPacket().isEmpty());
      host.receive(peer, ConnectionlessPacket.text("getstatus nonce"), 1000);
      assertEquals("statusResponse", reply(host));
      assertFalse(host.hasClients());
    }
  }

  @Test
  void malformedAndUnsupportedPacketsCannotReachGameAdmission() throws Exception {
    try (var fixture = fixture()) {
      var host = fixture.host;
      var peer = peer(30001);
      for (byte[] bytes :
          new byte[][] {
            new byte[0],
            new byte[] {1, 2, 3},
            ConnectionlessPacket.text("getchallenge bogus"),
            ConnectionlessPacket.text("connect "),
            ConnectionlessPacket.text("rcon password map map")
          }) assertDoesNotThrow(() -> host.receive(peer, bytes, 0));
      assertTrue(host.clients().isEmpty());
      assertFalse(fixture.game.isConnected(0));
      assertTrue(host.peekPacket().isEmpty());
      host.close();
      host.receive(peer, ConnectionlessPacket.text("getchallenge 1"), 2000);
      assertTrue(host.peekPacket().isEmpty());
    }
  }

  private Fixture fixture() throws Exception {
    Path vm = directory.resolve("baseq3/vm");
    Files.createDirectories(vm);
    var bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
    bytes
        .putInt(QvmReader.MAGIC)
        .putInt(3)
        .putInt(32)
        .putInt(15)
        .putInt(48)
        .putInt(0)
        .putInt(0)
        .putInt(65536);
    bytes
        .put((byte) Opcode.ENTER.ordinal())
        .putInt(8)
        .put((byte) Opcode.CONST.ordinal())
        .putInt(0)
        .put((byte) Opcode.LEAVE.ordinal())
        .putInt(8);
    Files.write(vm.resolve("qagame.qvm"), bytes.array());
    var fs = Pk3FileSystem.mount(directory, "baseq3");
    var cvars = new CvarSystem();
    cvars.register("sv_cheats", "0", CvarSystem.ROM | CvarSystem.SYSTEMINFO);
    cvars.register("bot_enable", "0", 0);
    cvars.register("sv_pure", "0", CvarSystem.SYSTEMINFO);
    var game =
        new Q3Server(
            fs,
            "fixture",
            BspReader.read(BspFixture.map(false)),
            null,
            s -> {},
            Clock.systemUTC(),
            null,
            cvars);
    game.initialize(1000, 42);
    return new Fixture(fs, game, new Protocol68Host(game, fs, 123, 0, s -> {}));
  }

  private record Fixture(Pk3FileSystem fs, Q3Server game, Protocol68Host host)
      implements AutoCloseable {
    public void close() throws IOException {
      try {
        host.close();
      } finally {
        try {
          game.close();
        } finally {
          fs.close();
        }
      }
    }
  }

  private static InetSocketAddress peer(int port) throws Exception {
    return new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port);
  }

  private static String reply(Protocol68Host host) {
    var packet = host.peekPacket().orElseThrow();
    host.packetSent();
    return ConnectionlessMessage.parse(packet.payload()).line();
  }
}
