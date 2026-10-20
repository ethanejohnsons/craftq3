package dev.bluevista.craftq3.engine.vm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The distinct QVM builds of an installation: each {@code vm/*.qvm} inside every PK3 plus every
 * loose {@code *.qvm}, deduplicated by content. Nothing read is printed beyond file names.
 */
final class RealQvmBuilds {
  record Build(String source, byte[] bytes) {}

  /** Builds by SHA-256, in file order; archives that cannot be read are listed in skipped. */
  final Map<String, Build> builds = new LinkedHashMap<>();

  final List<String> skippedArchives = new ArrayList<>();
  int occurrences;

  static RealQvmBuilds scan(Path root) throws IOException {
    RealQvmBuilds result = new RealQvmBuilds();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    for (Path file : files) {
      String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
      if (lower.endsWith(".qvm")) {
        result.occurrences++;
        result.add(root.relativize(file).toString(), Files.readAllBytes(file));
      } else if (lower.endsWith(".pk3")) {
        // Names are raw bytes in PK3s, hence ISO-8859-1 (UTF-8 rejects some archives).
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.ISO_8859_1)) {
          for (var entries = zip.entries(); entries.hasMoreElements(); ) {
            var entry = entries.nextElement();
            String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (entry.isDirectory() || !name.startsWith("vm/") || !name.endsWith(".qvm")) continue;
            result.occurrences++;
            try (InputStream in = zip.getInputStream(entry)) {
              result.add(root.relativize(file) + "!" + entry.getName(), in.readAllBytes());
            }
          }
        } catch (ZipException invalid) {
          // Q3 skips unreadable PK3s (e.g. zero-byte files) with a warning; so does this sweep.
          result.skippedArchives.add(root.relativize(file) + " (" + invalid.getMessage() + ")");
        }
      }
    }
    return result;
  }

  private void add(String source, byte[] bytes) {
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      builds.putIfAbsent(hash, new Build(source, bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
