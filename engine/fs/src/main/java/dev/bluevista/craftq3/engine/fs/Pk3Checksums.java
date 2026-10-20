package dev.bluevista.craftq3.engine.fs;

import dev.bluevista.craftq3.engine.common.hash.Md4;
import java.util.Objects;

/**
 * Original pack checksums from central-directory metadata. The caller supplies the CRC32 of every
 * entry whose uncompressed size is nonzero, in central-directory order. Names, compression and
 * search order do not enter the checksum. A zero CRC is still a list entry.
 */
public final class Pk3Checksums {
  /** Bounds the work and allocation for one checksum. */
  public static final int MAX_ENTRIES = 400_000;

  private Pk3Checksums() {}

  /** All 32 checksum bits; {@link Integer#toString} gives the signed wire form. */
  public static int normal(int[] orderedCrcs) {
    return Md4.blockChecksum(bytes(orderedCrcs, false, 0));
  }

  /** The checksum feed is prepended as four little-endian bytes, including when it is zero. */
  public static int pure(int[] orderedCrcs, int checksumFeed) {
    return Md4.blockChecksum(bytes(orderedCrcs, true, checksumFeed));
  }

  private static byte[] bytes(int[] orderedCrcs, boolean pure, int feed) {
    Objects.requireNonNull(orderedCrcs);
    if (orderedCrcs.length > MAX_ENTRIES)
      throw new IllegalArgumentException("PK3 checksum entry budget exceeded");
    byte[] bytes = new byte[(orderedCrcs.length + (pure ? 1 : 0)) * 4];
    int offset = 0;
    if (pure) {
      put(bytes, 0, feed);
      offset = 4;
    }
    for (int crc : orderedCrcs) {
      put(bytes, offset, crc);
      offset += 4;
    }
    return bytes;
  }

  private static void put(byte[] bytes, int at, int value) {
    for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (i * 8));
  }
}
