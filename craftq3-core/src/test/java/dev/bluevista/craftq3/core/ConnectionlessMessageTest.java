package dev.bluevista.craftq3.core;

import static dev.bluevista.craftq3.core.net.Protocol68Datagram.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ConnectionlessMessage;
import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import dev.bluevista.craftq3.core.net.Protocol68Channel;
import dev.bluevista.craftq3.core.net.Protocol68Datagram;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ConnectionlessMessageTest {
  @Test
  void statusEnvelopePreservesRawBodyWithoutExecutingItsContents() {
    byte[] body = {'\\', 'n', 'a', 'm', 'e', '\\', '%', (byte) 200, 0, '\n'};
    byte[] header = "statusResponse\n".getBytes(StandardCharsets.ISO_8859_1);
    byte[] payload = Arrays.copyOf(header, header.length + body.length);
    System.arraycopy(body, 0, payload, header.length, body.length);
    var parsed = ConnectionlessMessage.parse(ConnectionlessPacket.encode(payload));
    assertEquals("statusResponse", parsed.line());
    assertArrayEquals(body, parsed.body());
    assertEquals(19, parsed.readCount());
    assertEquals(152, parsed.bitPosition());
  }

  @Test
  void rawLineUsesNativeFilterAndRetainsCarriageReturn() {
    var parsed = ConnectionlessMessage.parse(HexFormat.of().parseHex("ffffffff257f80ff0d0a58"));
    assertEquals(".\u007f..\r", parsed.line());
    assertArrayEquals(new byte[] {'X'}, parsed.body());
    assertEquals(10, parsed.readCount());
    assertEquals(80, parsed.bitPosition());
  }

  @Test
  void nulAndNewlineConsumeOnlyTheirDelimiter() {
    for (byte delimiter : new byte[] {0, '\n'}) {
      var parsed =
          ConnectionlessMessage.parse(
              ConnectionlessPacket.encode(new byte[] {'a', delimiter, 'b', 0}));
      assertEquals("a", parsed.line());
      assertArrayEquals(new byte[] {'b', 0}, parsed.body());
      assertEquals(6, parsed.readCount());
      assertEquals(48, parsed.bitPosition());
    }
  }

  @Test
  void eofAdvancesOnlyNativeReadCountIncludingOnEmptyPayload() {
    for (String text : new String[] {"", "getchallenge 123", "a".repeat(1023)}) {
      var parsed = ConnectionlessMessage.parse(ConnectionlessPacket.text(text));
      assertEquals(text, parsed.line());
      assertEquals(text.length() + 5, parsed.readCount());
      assertEquals((text.length() + 4) * 8, parsed.bitPosition());
      assertArrayEquals(new byte[0], parsed.body());
    }
  }

  @Test
  void capacityConsumesOneDiscardedSymbolAndRetainsEverythingAfterIt() {
    for (byte discarded : new byte[] {0, '\n', 'x', '%', (byte) 255}) {
      byte[] payload = new byte[1027];
      Arrays.fill(payload, 0, 1023, (byte) 'a');
      payload[1023] = discarded;
      payload[1024] = 'B';
      payload[1025] = 0;
      payload[1026] = (byte) 255;
      var parsed = ConnectionlessMessage.parse(ConnectionlessPacket.encode(payload));
      assertEquals("a".repeat(1023), parsed.line());
      assertEquals(1028, parsed.readCount());
      assertEquals(8224, parsed.bitPosition());
      assertArrayEquals(new byte[] {'B', 0, (byte) 255}, parsed.body());
    }
  }

  @Test
  void sizePolicyRejectsMalformedInputsBeforeParsingAndDistinguishesPrefixes() {
    for (int length = 0; length < 4; length++) {
      byte[] shortPacket = new byte[length];
      Arrays.fill(shortPacket, (byte) 255);
      assertEquals(MALFORMED, Protocol68Datagram.classify(shortPacket));
      assertThrows(IllegalArgumentException.class, () -> ConnectionlessMessage.parse(shortPacket));
    }
    byte[] full = ConnectionlessPacket.encode(new byte[ConnectionlessPacket.MAX_PAYLOAD]);
    assertEquals(CONNECTIONLESS, Protocol68Datagram.classify(full));
    assertEquals(
        ConnectionlessPacket.MAX_PAYLOAD - 1, ConnectionlessMessage.parse(full).body().length);
    byte[] oversized = Arrays.copyOf(full, full.length + 1);
    assertEquals(MALFORMED, Protocol68Datagram.classify(oversized));
    assertThrows(IllegalArgumentException.class, () -> ConnectionlessMessage.parse(oversized));
    assertEquals(SEQUENCED, Protocol68Datagram.classify(new byte[4]));
    assertEquals(SEQUENCED, Protocol68Datagram.classify(new byte[Protocol68Channel.MAX_PACKET]));
    assertEquals(
        MALFORMED, Protocol68Datagram.classify(new byte[Protocol68Channel.MAX_PACKET + 1]));
    assertEquals(SEQUENCED, Protocol68Datagram.classify(HexFormat.of().parseHex("ffffff7f")));
    assertEquals(SEQUENCED, Protocol68Datagram.classify(HexFormat.of().parseHex("00000080")));
    assertThrows(IllegalArgumentException.class, () -> ConnectionlessMessage.parse(new byte[4]));
  }

  @Test
  void packetAndReturnedBodyMutationCannotChangeTheParsedEnvelope() {
    byte[] packet = ConnectionlessPacket.text("print\nbody");
    var parsed = ConnectionlessMessage.parse(packet);
    Arrays.fill(packet, (byte) 0);
    Arrays.fill(parsed.body(), (byte) 0);
    assertEquals("print", parsed.line());
    assertArrayEquals("body".getBytes(StandardCharsets.ISO_8859_1), parsed.body());
  }

  @Test
  void classifierLeavesFragmentAssemblyUnderTheChannelsControl() {
    var sender = new Protocol68Channel(Protocol68Channel.Endpoint.SERVER, 0);
    var receiver = new Protocol68Channel(Protocol68Channel.Endpoint.CLIENT, 0);
    sender.queue(new byte[1301]);
    assertEquals(
        Protocol68Channel.Status.PARTIAL,
        receiver.receive(sender.pollPacket().orElseThrow()).status());
    assertEquals(
        CONNECTIONLESS, Protocol68Datagram.classify(ConnectionlessPacket.text("print\ntext")));
    assertEquals(MALFORMED, Protocol68Datagram.classify(new byte[2000]));
    assertEquals(0, receiver.incomingSequence());
    assertEquals(
        Protocol68Channel.Status.COMPLETE,
        receiver.receive(sender.pollPacket().orElseThrow()).status());
  }
}
