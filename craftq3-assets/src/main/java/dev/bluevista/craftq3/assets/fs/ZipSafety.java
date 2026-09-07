package dev.bluevista.craftq3.assets.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Bound ZIP32 metadata before the JDK allocates its central-directory index. */
final class ZipSafety {
  private ZipSafety() {}

  static void preflight(Path path, int remainingEntries) throws IOException {
    try (var file = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = file.size();
      if (size < 22 || size > 2L * 1024 * 1024 * 1024)
        throw new IOException("Invalid PK3 size: " + path);
      int tailSize = (int) Math.min(size, 22 + 65535);
      ByteBuffer tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN);
      file.position(size - tailSize);
      while (tail.hasRemaining())
        if (file.read(tail) < 0) throw new IOException("Truncated PK3 footer");
      for (int i = tailSize - 22; i >= 0; i--) {
        if (tail.getInt(i) != 0x06054b50
            || i + 22 + Short.toUnsignedInt(tail.getShort(i + 20)) != tailSize) continue;
        int disk = Short.toUnsignedInt(tail.getShort(i + 4));
        int directoryDisk = Short.toUnsignedInt(tail.getShort(i + 6));
        int diskEntries = Short.toUnsignedInt(tail.getShort(i + 8));
        int entries = Short.toUnsignedInt(tail.getShort(i + 10));
        long bytes = Integer.toUnsignedLong(tail.getInt(i + 12));
        long offset = Integer.toUnsignedLong(tail.getInt(i + 16));
        if (disk != 0
            || directoryDisk != 0
            || entries == 65535
            || diskEntries != entries
            || bytes == 0xffffffffL
            || offset == 0xffffffffL)
          throw new IOException("Multi-volume/ZIP64 PK3 is unsupported: " + path);
        if (entries > remainingEntries
            || bytes > 32 * 1024 * 1024
            || offset + bytes != size - tailSize + i
            || bytes < entries * 46L)
          throw new IOException("Invalid or excessive PK3 central directory: " + path);
        return;
      }
      throw new IOException("Missing PK3 end-of-central-directory record: " + path);
    }
  }
}
