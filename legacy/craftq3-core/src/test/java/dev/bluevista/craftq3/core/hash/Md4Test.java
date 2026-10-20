package dev.bluevista.craftq3.core.hash;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class Md4Test {
  @Test
  void matchesAllSevenRfc1320DigestVectors() {
    String[] text = {
      "",
      "a",
      "abc",
      "message digest",
      "abcdefghijklmnopqrstuvwxyz",
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
      "1234567890".repeat(8)
    };
    String[] expected = {
      "31d6cfe0d16ae931b73c59d7e0c089c0",
      "bde52cb31de33e46245e05fbdbd6fb24",
      "a448017aaf21d8525fc10ae87aa6729d",
      "d9130a8164549fe818874806e1c7014b",
      "d79e1c308aa5bbcdeea8ed63df412da9",
      "043f8582f241db351ce627e153e7f0e4",
      "e33b4ddc9c38f2199c3e7b164fcc0536"
    };
    for (int i = 0; i < text.length; i++)
      assertEquals(
          expected[i],
          HexFormat.of().formatHex(Md4.digest(text[i].getBytes(StandardCharsets.US_ASCII))));
  }

  @Test
  void foldedChecksumMatchesNativePaddingBoundariesAndSignedBits() {
    int[] lengths = {0, 55, 56, 63, 64, 65, 119, 120, 127, 128};
    int[] expected = {
      0x9868a6bf,
      0xb1870e27,
      0x45b61262,
      0x05a9dd8a,
      0x044a5a4d,
      0x67e645dc,
      0xe0b37079,
      0x48fe8205,
      0x01126864,
      0xb8ccacbb
    };
    for (int i = 0; i < lengths.length; i++) {
      byte[] value = new byte[lengths[i]];
      for (int at = 0; at < value.length; at++) value[at] = (byte) at;
      assertEquals(expected[i], Md4.blockChecksum(value), "Length " + value.length);
    }
    assertEquals(0x5da10e2e, Md4.blockChecksum("abc".getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  void leavesInputUntouchedAndReturnsIndependentDigestStorage() {
    byte[] input = new byte[257];
    for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 37);
    byte[] copy = input.clone();
    byte[] first = Md4.digest(input), second = Md4.digest(input);
    assertArrayEquals(first, second);
    first[0] ^= 1;
    assertArrayEquals(second, Md4.digest(input));
    Md4.blockChecksum(input);
    assertArrayEquals(copy, input);
    assertThrows(NullPointerException.class, () -> Md4.digest(null));
  }
}
