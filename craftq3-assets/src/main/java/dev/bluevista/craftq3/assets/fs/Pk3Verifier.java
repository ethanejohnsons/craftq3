package dev.bluevista.craftq3.assets.fs;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/**
 * Full staged-download verification, including entry CRCs; original installation packs are
 * untouched.
 */
final class Pk3Verifier {
  private Pk3Verifier() {}

  static void verify(Path path, int expected, BooleanSupplier cancelled) throws IOException {
    ZipSafety.preflight(path, 65534);
    try (var zip = new ZipFile(path.toFile(), StandardCharsets.ISO_8859_1)) {
      var crcs = new ArrayList<Integer>();
      var names = new HashSet<VirtualPath>();
      long total = 0;
      byte[] buffer = new byte[65536];
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        if (cancelled.getAsBoolean()) throw new IOException("Download verification cancelled");
        var entry = entries.nextElement();
        String name = entry.getName();
        if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
        VirtualPath virtual;
        try {
          virtual = new VirtualPath(name);
        } catch (IllegalArgumentException bad) {
          throw new IOException("Unsafe downloaded PK3 member", bad);
        }
        if (!entry.isDirectory() && !names.add(virtual))
          throw new IOException("Ambiguous downloaded PK3 member");
        long size = entry.getSize();
        if (size < 0
            || size > Pk3FileSystem.MAX_FILE_BYTES
            || (total += size) > 2L * 1024 * 1024 * 1024)
          throw new IOException("Downloaded PK3 exceeds unpacked limits");
        if (size > 0) crcs.add((int) entry.getCrc());
        var crc = new CRC32();
        long count = 0;
        try (var input = zip.getInputStream(entry)) {
          for (int read; (read = input.read(buffer)) != -1; ) {
            if (cancelled.getAsBoolean()) throw new IOException("Download verification cancelled");
            count += read;
            if (count > size) throw new IOException("Downloaded PK3 member exceeds declared size");
            crc.update(buffer, 0, read);
          }
        }
        if (count != size || crc.getValue() != entry.getCrc())
          throw new IOException("Downloaded PK3 member CRC/length mismatch");
      }
      if (names.isEmpty()
          || Pk3Checksums.normal(crcs.stream().mapToInt(Integer::intValue).toArray()) != expected)
        throw new IOException("Downloaded PK3 checksum does not match server");
    }
  }
}
