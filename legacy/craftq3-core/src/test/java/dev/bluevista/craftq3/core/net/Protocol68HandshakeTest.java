package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.InfoString;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The transport must pin the peer before these source-agnostic packet exchanges are invoked. */
final class Protocol68HandshakeTest {
  private static final int NONCE = -135792468, QPORT = 65535, CHALLENGE = -2147483647;
  private static final Map<String, String> USERINFO =
      Map.of(
          "name", "CraftQ3 handshake QA", "rate", "25000", "snaps", "20", "model", "sarge/default");

  @Test
  void initialStateRequiresChallengeBeforeConnectAndOwnsChallengeRetries() {
    var exchange = create();
    assertEquals(Protocol68Handshake.State.CHALLENGING, exchange.state());
    assertEquals(QPORT, exchange.qport());
    assertEquals("", exchange.print());
    assertThrows(IllegalStateException.class, exchange::challenge);
    byte[] request = exchange.request();
    assertEquals("getchallenge " + NONCE + " Quake3Arena", payload(request));
    assertNotSame(request, exchange.request());
    assertArrayEquals(request, exchange.request());
    Arrays.fill(request, (byte) 0);
    assertEquals("getchallenge " + NONCE + " Quake3Arena", payload(exchange.request()));
    assertIgnoredUnchanged(exchange, "connectResponse", "connectResponse " + CHALLENGE);
  }

  @Test
  void completeExchangeChecksBothEchoesAndCannotBeReopened() {
    var exchange = create();
    assertEquals(
        Protocol68Handshake.Result.CHALLENGE_ACCEPTED,
        exchange.receive(packet("challengeResponse " + CHALLENGE + " " + NONCE + " 68")));
    assertEquals(CHALLENGE, exchange.challenge());
    assertEquals(Protocol68Handshake.State.CONNECTING, exchange.state());
    assertIgnoredUnchanged(
        exchange,
        "challengeResponse 100 " + NONCE + " 68",
        "connectResponse 1",
        "connectResponse " + CHALLENGE + " extra");
    assertEquals(
        Protocol68Handshake.Result.CONNECTED,
        exchange.receive(packet("connectResponse " + CHALLENGE)));
    assertEquals(Protocol68Handshake.State.CONNECTED, exchange.state());
    assertThrows(IllegalStateException.class, exchange::request);
    for (String text :
        List.of("challengeResponse 2", "connectResponse", "print\nlate server text")) {
      assertEquals(Protocol68Handshake.Result.IGNORED, exchange.receive(packet(text)));
    }
    assertEquals(CHALLENGE, exchange.challenge());
    assertEquals("", exchange.print());
  }

  @Test
  void suppliedNonceAndProtocolMustMatchWithoutChangingChallengeRetries() {
    var exchange = create();
    assertIgnoredUnchanged(
        exchange,
        "challengeResponse 17 1",
        "challengeResponse 17 " + NONCE + " 67",
        "challengeResponse 17 " + NONCE + " 69",
        "challengeResponse 17 1 68",
        "challengeResponse 17 " + NONCE + " 68 extra");
    assertThrows(IllegalStateException.class, exchange::challenge);
    assertEquals(
        Protocol68Handshake.Result.CHALLENGE_ACCEPTED,
        exchange.receive(packet("challengeResponse 17 " + NONCE + " 68")));
    assertEquals(17, exchange.challenge());
  }

  @Test
  void pinnedLegacyPeersMayOmitNonceProtocolAndConnectChallenge() {
    for (String response : List.of("challengeResponse 0", "challengeResponse 0 " + NONCE)) {
      var exchange = create();
      assertEquals(
          Protocol68Handshake.Result.CHALLENGE_ACCEPTED, exchange.receive(packet(response)));
      assertEquals(0, exchange.challenge());
      assertEquals(
          Protocol68Handshake.Result.CONNECTED, exchange.receive(packet("connectResponse")));
    }
  }

  @Test
  void connectPacketOwnsUserinfoAndOverridesAllWireKeysDeterministically() {
    var info = new LinkedHashMap<>(USERINFO);
    info.put("protocol", "1");
    info.put("qport", "1");
    info.put("challenge", "spoofed");
    info.put("Protocol", "66");
    info.put("QPORT", "2");
    info.put("Challenge", "second spoofed value");
    info.put("cl_guid", "0123456789abcdef");
    info.put("empty", "");
    var exchange = new Protocol68Handshake(NONCE, QPORT, info);
    info.put("name", "mutated after construction");
    info.put("new", "field");
    acceptChallenge(exchange);
    byte[] first = exchange.request();
    var command = CommandParser.tokenize(payload(ConnectPacketCodec.decompress(first)));
    assertEquals("connect", command.argument(0));
    assertEquals(2, command.arguments().size());
    var values = InfoString.parse(command.argument(1), 1024);
    assertEquals(USERINFO.get("name"), values.get("name"));
    assertEquals("68", values.get("protocol"));
    assertEquals(Integer.toString(QPORT), values.get("qport"));
    assertEquals(Integer.toString(CHALLENGE), values.get("challenge"));
    assertEquals("0123456789abcdef", values.get("cl_guid"));
    assertFalse(values.containsKey("new"));
    assertFalse(values.containsKey("empty"));
    assertFalse(values.containsKey("Protocol"));
    assertFalse(values.containsKey("QPORT"));
    assertFalse(values.containsKey("Challenge"));
    assertEquals(USERINFO.size() + 4, values.size());
    var reversed = new LinkedHashMap<String, String>();
    info.remove("new");
    info.put("name", USERINFO.get("name"));
    info.sequencedEntrySet().reversed().forEach(e -> reversed.put(e.getKey(), e.getValue()));
    var second = new Protocol68Handshake(NONCE, QPORT, reversed);
    acceptChallenge(second);
    assertArrayEquals(first, second.request());
    first[first.length / 2] ^= 0x7f;
    assertArrayEquals(second.request(), exchange.request());
  }

  @Test
  void printBodyIsInertOwnedTextAndDoesNotChangeEitherOutstandingRequest() {
    var exchange = create();
    for (boolean connecting : List.of(false, true)) {
      if (connecting) acceptChallenge(exchange);
      byte[] before = exchange.request();
      byte[] message = packet("print\nset challenge 1; connectResponse\n\u00e9");
      assertEquals(Protocol68Handshake.Result.PRINT, exchange.receive(message));
      Arrays.fill(message, (byte) 0);
      assertEquals("set challenge 1; connectResponse\n\u00e9", exchange.print());
      assertArrayEquals(before, exchange.request());
      assertEquals(
          connecting ? Protocol68Handshake.State.CONNECTING : Protocol68Handshake.State.CHALLENGING,
          exchange.state());
      assertEquals(Protocol68Handshake.Result.PRINT, exchange.receive(packet("print\n")));
      assertEquals("", exchange.print());
    }
  }

  @Test
  void malformedNumbersAndWrongCommandArityNeverAdvanceEitherState() {
    var exchange = create();
    assertIgnoredUnchanged(
        exchange,
        "",
        "challengeResponse",
        "ChallengeResponse 17",
        "challengeResponse nope",
        "challengeResponse 2147483648",
        "challengeResponse -2147483649",
        "challengeResponse 17 not-a-nonce",
        "challengeResponse 17 " + NONCE + " NaN",
        "challengeResponse 17 " + NONCE + " 68; connectResponse");
    acceptChallenge(exchange);
    assertIgnoredUnchanged(
        exchange,
        "",
        "ConnectResponse",
        "connectResponse NaN",
        "connectResponse 2147483648",
        "connectResponse -2147483649",
        "connectResponse " + CHALLENGE + "; quit");
  }

  @Test
  void invalidDatagramFramingAndOversizedPacketsAreIgnoredBeforeStateMutation() {
    var exchange = create();
    byte[] tooLarge = new byte[Protocol68Channel.MAX_MESSAGE + 1];
    Arrays.fill(tooLarge, 0, 4, (byte) -1);
    byte[] sequenced = packet("challengeResponse 1");
    sequenced[0] = 1;
    for (boolean connecting : List.of(false, true)) {
      if (connecting) acceptChallenge(exchange);
      byte[] before = exchange.request();
      for (byte[] malformed :
          List.of(
              new byte[0],
              new byte[] {-1, -1, -1},
              new byte[] {-1, -1, -1, -1},
              sequenced,
              tooLarge)) {
        assertEquals(Protocol68Handshake.Result.IGNORED, exchange.receive(malformed));
        assertArrayEquals(before, exchange.request());
      }
    }
  }

  @Test
  void acceptedReplyCanBeReusedOrMutatedWithoutChangingSavedChallengeOrConnectPacket() {
    var exchange = create();
    byte[] reply = packet("challengeResponse " + CHALLENGE + " " + NONCE + " 68\n");
    assertEquals(Protocol68Handshake.Result.CHALLENGE_ACCEPTED, exchange.receive(reply));
    byte[] request = exchange.request();
    assertEquals(Protocol68Handshake.Result.IGNORED, exchange.receive(reply));
    Arrays.fill(reply, (byte) 0);
    assertEquals(CHALLENGE, exchange.challenge());
    assertArrayEquals(request, exchange.request());
  }

  @Test
  void qportAndUserinfoRejectUnsafeOrUnrepresentableLocalInputs() {
    assertThrows(IllegalArgumentException.class, () -> new Protocol68Handshake(0, -1, USERINFO));
    assertThrows(IllegalArgumentException.class, () -> new Protocol68Handshake(0, 65536, USERINFO));
    assertEquals(0, new Protocol68Handshake(Integer.MIN_VALUE, 0, USERINFO).qport());
    for (String bad : List.of("x;y", "x\"y", "x\\y", "x\ny", "x\0y", "x\u007fy", "\u0100")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new Protocol68Handshake(0, QPORT, Map.of("name", bad)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new Protocol68Handshake(0, QPORT, Map.of(bad, "value")));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new Protocol68Handshake(0, QPORT, Map.of("", "empty key")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Protocol68Handshake(0, QPORT, Map.of("name", "a".repeat(1024))));
  }

  @Test
  void constructorReservesRoomForMandatoryConnectFieldsBeforeAnyExchange() {
    assertTrue(InfoString.encode(Map.of("name", "a".repeat(1000)), 1024).length() < 1024);
    assertThrows(
        IllegalArgumentException.class,
        () -> new Protocol68Handshake(NONCE, QPORT, Map.of("name", "a".repeat(1000))));
  }

  private static Protocol68Handshake create() {
    return new Protocol68Handshake(NONCE, QPORT, USERINFO);
  }

  private static void acceptChallenge(Protocol68Handshake exchange) {
    assertEquals(
        Protocol68Handshake.Result.CHALLENGE_ACCEPTED,
        exchange.receive(packet("challengeResponse " + CHALLENGE + " " + NONCE + " 68")));
  }

  private static void assertIgnoredUnchanged(Protocol68Handshake exchange, String... replies) {
    byte[] before = exchange.request();
    var state = exchange.state();
    for (String reply : replies) {
      assertEquals(Protocol68Handshake.Result.IGNORED, exchange.receive(packet(reply)), reply);
      assertEquals(state, exchange.state(), reply);
      assertArrayEquals(before, exchange.request(), reply);
    }
  }

  private static byte[] packet(String text) {
    return ConnectionlessPacket.text(text);
  }

  private static String payload(byte[] packet) {
    return new String(ConnectionlessPacket.payload(packet), StandardCharsets.ISO_8859_1);
  }
}
