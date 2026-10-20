package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class ConnectPacketCodecTest {
  private static final String USERINFO =
      "\"\\name\\Player\\rate\\25000\\snaps\\20\\model\\sarge\\headmodel\\sarge"
          + "\\protocol\\68\\qport\\27960\\challenge\\1234567\"";
  private static final String NATIVE_PACKET =
      "ffffffff636f6e6e6563742000684474b08b216cc79450001b1c4f1627ec8c4b58980ed68461"
          + "7406e795c2c17ee3c5b0074cb64ca317f39fc742eae75c33aeb2d66fa3f4bb612ceb0cb61"
          + "a8e6f38aa2e7e35c20ee044f53830a4c498fb618c2acc0d2cadef0f00";

  @Test
  void fullNativeOutboundPacketMatchesAndRoundTrips() {
    byte[] raw = raw(USERINFO.getBytes(StandardCharsets.ISO_8859_1));
    byte[] encoded = ConnectPacketCodec.compress(raw);
    assertEquals(NATIVE_PACKET, HexFormat.of().formatHex(encoded));
    assertArrayEquals(raw, ConnectPacketCodec.decompress(encoded));
    assertArrayEquals(raw, ConnectPacketCodec.decompress(HexFormat.of().parseHex(NATIVE_PACKET)));
  }

  @Test
  void byteAlphabetTerminatorsAndPacketOwnershipArePreserved() {
    byte[] input = new byte[4096];
    for (int index = 0; index < 512; index++) input[index] = (byte) index;
    byte[] raw = raw(input);
    byte[] encoded = ConnectPacketCodec.compress(raw);
    byte[] decoded = ConnectPacketCodec.decompress(encoded);
    assertArrayEquals(raw, decoded);
    decoded[12] = 99;
    assertEquals(0, raw[12]);
    assertArrayEquals(raw, ConnectPacketCodec.decompress(encoded));
    Arrays.fill(raw, (byte) 0);
    assertArrayEquals(input, Arrays.copyOfRange(ConnectPacketCodec.decompress(encoded), 12, 4108));
  }

  @Test
  void adaptiveStateStartsFreshAndAlignedPaddingIsDeterministic() {
    byte[] first = ConnectPacketCodec.compress(raw(new byte[] {'a'}));
    assertEquals("ffffffff636f6e6e6563742000018600", HexFormat.of().formatHex(first));
    ConnectPacketCodec.compress(raw(USERINFO.getBytes(StandardCharsets.ISO_8859_1)));
    assertArrayEquals(first, ConnectPacketCodec.compress(raw(new byte[] {'a'})));
    first[first.length - 1] = (byte) 0xa7;
    assertArrayEquals(raw(new byte[] {'a'}), ConnectPacketCodec.decompress(first));
    assertArrayEquals(
        raw(new byte[] {'a'}),
        ConnectPacketCodec.decompress(Arrays.copyOf(first, first.length - 1)));
  }

  @Test
  void nativeTransmitOverflowIsRejectedRatherThanSendingCorruptedUserinfo() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ConnectPacketCodec.compress(raw(new byte[] {'a', 'a'})));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ConnectPacketCodec.compress(
                raw("\"\\name\\Player\"".getBytes(StandardCharsets.ISO_8859_1))));
    assertEquals(
        "ffffffff636f6e6e6563742000048607",
        HexFormat.of()
            .formatHex(ConnectPacketCodec.compress(raw(new byte[] {'a', 'a', 'a', 'a'}))));
  }

  @Test
  void prefixLengthAndAdvertisedExpansionAreCheckedBeforeAllocation() {
    byte[] prefix = raw(new byte[0]);
    assertArrayEquals(prefix, ConnectPacketCodec.decompress(ConnectPacketCodec.compress(prefix)));
    assertNotSame(prefix, ConnectPacketCodec.compress(prefix));
    byte[] badPrefix = prefix.clone();
    badPrefix[4] = 'C';
    assertThrows(IllegalArgumentException.class, () -> ConnectPacketCodec.compress(badPrefix));
    assertThrows(IllegalArgumentException.class, () -> ConnectPacketCodec.decompress(badPrefix));
    assertThrows(IllegalArgumentException.class, () -> ConnectPacketCodec.decompress(new byte[11]));
    assertThrows(
        IllegalArgumentException.class, () -> ConnectPacketCodec.decompress(raw(new byte[] {0})));
    assertThrows(
        IllegalArgumentException.class,
        () -> ConnectPacketCodec.decompress(raw(new byte[] {-1, -1, 0})));
    assertThrows(
        IllegalArgumentException.class,
        () -> ConnectPacketCodec.compress(raw(new byte[Protocol68Channel.MAX_MESSAGE])));
  }

  @Test
  void truncationChecksConsumedBitsAndPermitsUnusedTail() {
    byte[] raw = raw(USERINFO.getBytes(StandardCharsets.ISO_8859_1));
    byte[] encoded = ConnectPacketCodec.compress(raw);
    for (int length = 14; length < encoded.length - 1; length++) {
      byte[] truncated = Arrays.copyOf(encoded, length);
      assertThrows(IllegalArgumentException.class, () -> ConnectPacketCodec.decompress(truncated));
    }
    byte[] trailing = Arrays.copyOf(encoded, encoded.length + 8);
    Arrays.fill(trailing, encoded.length, trailing.length, (byte) 0xff);
    assertArrayEquals(raw, ConnectPacketCodec.decompress(trailing));
    byte[] maximum = raw(new byte[Protocol68Channel.MAX_MESSAGE - 12]);
    assertArrayEquals(maximum, ConnectPacketCodec.decompress(ConnectPacketCodec.compress(maximum)));
  }

  @Test
  void malformedRandomStreamsStayWithinTheDeclaredOutputAndBitBounds() {
    Random random = new Random(68);
    for (int sample = 0; sample < 500; sample++) {
      byte[] payload = new byte[2 + random.nextInt(100)];
      random.nextBytes(payload);
      payload[0] = 0;
      try {
        byte[] decoded = ConnectPacketCodec.decompress(raw(payload));
        assertEquals(12 + (payload[1] & 255), decoded.length);
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("Truncated"));
      }
    }
  }

  private static byte[] raw(byte[] suffix) {
    byte[] payload = new byte[8 + suffix.length];
    System.arraycopy("connect ".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 8);
    System.arraycopy(suffix, 0, payload, 8, suffix.length);
    return ConnectionlessPacket.encode(payload);
  }
}
