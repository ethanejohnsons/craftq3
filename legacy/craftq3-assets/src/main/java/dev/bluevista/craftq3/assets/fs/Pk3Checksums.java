package dev.bluevista.craftq3.assets.fs;

import dev.bluevista.craftq3.core.hash.Md4;
import java.util.Objects;

/**
 * Original pack checksums from central-directory metadata. The caller supplies CRC32 values for
 * entries whose uncompressed size is nonzero, in central-directory order. Names, compression and
 * mounted-path precedence do not enter the checksum. A zero CRC value is still a valid list entry.
 */
public final class Pk3Checksums {
  /** Matches the bounded archive-entry budget of the asset mount. */
  public static final int MAX_ENTRIES = 400_000;

  private Pk3Checksums() {}

  /** Returns all32 checksum bits; Integer.toString gives the native signed wire representation. */
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
