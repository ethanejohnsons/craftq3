package dev.bluevista.craftq3.core.hash;

import java.util.Objects;

/**
 * Independent byte-oriented MD4 implementation from RFC 1320 sections 3.1–3.5. Used for original
 * engine compatibility checksums, not authentication. No native engine implementation is used.
 */
public final class Md4 {
  private static final int[][] ROTATIONS = {{3, 7, 11, 19}, {3, 5, 9, 13}, {3, 9, 11, 15}};

  private Md4() {}

  /** Returns the owned sixteen digest bytes in the order specified by RFC 1320. */
  public static byte[] digest(byte[] input) {
    int[] words = digestWords(input);
    byte[] digest = new byte[16];
    for (int word = 0; word < 4; word++) {
      for (int octet = 0; octet < 4; octet++)
        digest[word * 4 + octet] = (byte) (words[word] >>> (octet * 8));
    }
    return digest;
  }

  /**
   * Native-observed Com_BlockChecksum. Nonempty inputs XOR the four little-endian MD4 words. The
   * native empty-buffer result is a distinct fixed compatibility value, not RFC MD4(empty).
   */
  public static int blockChecksum(byte[] input) {
    Objects.requireNonNull(input);
    if (input.length == 0) return 0x9868a6bf;
    int[] words = digestWords(input);
    return words[0] ^ words[1] ^ words[2] ^ words[3];
  }

  private static int[] digestWords(byte[] input) {
    Objects.requireNonNull(input);
    int[] state = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
    int[] message = new int[16];
    int offset = 0;
    while (offset <= input.length - 64) {
      block(state, message, input, offset);
      offset += 64;
    }
    int remaining = input.length - offset;
    byte[] tail = new byte[remaining < 56 ? 64 : 128];
    System.arraycopy(input, offset, tail, 0, remaining);
    tail[remaining] = (byte) 0x80;
    long bits = (long) input.length * 8;
    for (int i = 0; i < 8; i++) tail[tail.length - 8 + i] = (byte) (bits >>> (i * 8));
    for (int at = 0; at < tail.length; at += 64) block(state, message, tail, at);
    return state;
  }

  private static void block(int[] state, int[] message, byte[] bytes, int offset) {
    for (int word = 0; word < 16; word++) {
      int at = offset + word * 4;
      message[word] =
          (bytes[at] & 255)
              | (bytes[at + 1] & 255) << 8
              | (bytes[at + 2] & 255) << 16
              | (bytes[at + 3] & 255) << 24;
    }
    int[] work = state.clone();
    for (int round = 0; round < 3; round++) {
      for (int step = 0; step < 16; step++) {
        int target = -step & 3;
        int x = work[(target + 1) & 3], y = work[(target + 2) & 3], z = work[(target + 3) & 3];
        int function, word, constant;
        switch (round) {
          case 0 -> {
            function = (x & y) | (~x & z);
            word = step;
            constant = 0;
          }
          case 1 -> {
            function = (x & y) | (x & z) | (y & z);
            word = (step & 3) * 4 + (step >>> 2);
            constant = 0x5a827999;
          }
          default -> {
            function = x ^ y ^ z;
            word = Integer.reverse(step) >>> 28;
            constant = 0x6ed9eba1;
          }
        }
        work[target] =
            Integer.rotateLeft(
                work[target] + function + message[word] + constant, ROTATIONS[round][step & 3]);
      }
    }
    for (int i = 0; i < 4; i++) state[i] += work[i];
  }
}
