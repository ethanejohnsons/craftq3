package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Full verification of a downloaded PK3 before it enters the cache: unlike an installed archive, a
 * download with any skipped entry, duplicate name, or member whose bytes do not match its CRC is
 * rejected, and its pack checksum must equal what the server announced.
 */
final class Pk3Verifier {
  private Pk3Verifier() {}

  static void verify(Path path, int expected, BooleanSupplier cancelled) throws IOException {
    try (Pk3Archive archive = Pk3Archive.open(path)) {
      if (archive.problemCount() != 0)
        throw new IOException(
            "Downloaded PK3 has unsafe or ambiguous members: " + archive.problems());
      if (archive.entries().isEmpty()) throw new IOException("Downloaded PK3 has no files");
      byte[] buffer = new byte[65536];
      for (Pk3Archive.Entry entry : archive.entries()) {
        if (cancelled.getAsBoolean()) throw new IOException("Download verification cancelled");
        try (InputStream input = archive.open(entry)) {
          while (input.read(buffer) != -1)
            if (cancelled.getAsBoolean()) throw new IOException("Download verification cancelled");
        }
      }
      if (archive.checksum() != expected)
        throw new IOException("Downloaded PK3 checksum does not match server");
    } catch (Pk3FormatException malformed) {
      throw new IOException("Downloaded PK3 is malformed: " + malformed.getMessage(), malformed);
    }
  }
}
