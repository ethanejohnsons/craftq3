package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LegacyPayloadXorTest {
  @Test
  void nativeClientAndServerCiphertextCaptures() {
    var hex = HexFormat.of();
    var client = hex.parseHex("aaaaaa5028140a8542a15028140a8542a11100");
    assertEquals(
        "aaaaaa5028140a8542a150285f85696ced9beb",
        hex.formatHex(LegacyPayloadXor.client(client, 42, 0, 0, "abc")));
    var server = hex.parseHex("aa5028140a8542a15028140a8542a115");
    assertEquals(
        "aa5028144803a78615abf62cc0c54436",
        hex.formatHex(LegacyPayloadXor.server(server, 42, 9, "abc")));
  }

  @Test
  void bothDirectionsPreserveTheirClearPrefixAndAreSelfInverse() {
    var random = new Random(68);
    for (int length : new int[] {0, 1, 3, 4, 5, 11, 12, 13, 256, 1300, 16384}) {
      byte[] plain = new byte[length];
      random.nextBytes(plain);
      var client = LegacyPayloadXor.client(plain, -123, 456, -789, "odd");
      assertArrayEquals(
          Arrays.copyOf(plain, Math.min(length, 12)), Arrays.copyOf(client, Math.min(length, 12)));
      assertArrayEquals(plain, LegacyPayloadXor.client(client, -123, 456, -789, "odd"));
      var server = LegacyPayloadXor.server(plain, -123, 456, "even");
      assertArrayEquals(
          Arrays.copyOf(plain, Math.min(length, 4)), Arrays.copyOf(server, Math.min(length, 4)));
      assertArrayEquals(plain, LegacyPayloadXor.server(server, -123, 456, "even"));
    }
  }

  @Test
  void keyTextStopsAtNulFiltersHighBytesAndHandlesEmptyKeys() {
    byte[] data = new byte[64];
    assertArrayEquals(
        LegacyPayloadXor.server(data, 42, 9, "..."),
        LegacyPayloadXor.server(data, 42, 9, "%\u0080\u00ff"));
    assertArrayEquals(
        LegacyPayloadXor.server(data, 42, 9, "a"),
        LegacyPayloadXor.server(data, 42, 9, "a\0\u2603"));
    assertArrayEquals(
        LegacyPayloadXor.server(data, 42, 9, ""), LegacyPayloadXor.server(data, 42, 9, "\0unused"));
    var empty = LegacyPayloadXor.server(data, 42, 9, "");
    for (int i = 4; i < empty.length; i++) assertEquals((byte) 35, empty[i]);
    assertDoesNotThrow(() -> LegacyPayloadXor.client(data, 1, 2, 3, "x".repeat(1023)));
  }

  @Test
  void outputOwnsBytesAndInvalidBoundsNeverModifyInputs() {
    byte[] data = new byte[30];
    new Random(25).nextBytes(data);
    byte[] expected = data.clone();
    var output = LegacyPayloadXor.server(data, 5, 9, "key");
    output[0] = 99;
    assertArrayEquals(expected, data);
    assertThrows(
        IllegalArgumentException.class, () -> LegacyPayloadXor.server(data, 5, 9, "\u2603"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LegacyPayloadXor.server(data, 5, 9, "x".repeat(1024)));
    assertArrayEquals(expected, data);
    assertThrows(
        IllegalArgumentException.class, () -> LegacyPayloadXor.server(new byte[16385], 5, 9, ""));
    byte[] shortPayload = {1, 2, 3};
    assertArrayEquals(shortPayload, LegacyPayloadXor.server(shortPayload, 5, 9, null));
    assertNotSame(shortPayload, LegacyPayloadXor.server(shortPayload, 5, 9, null));
  }
}
