package dev.bluevista.craftq3.engine.vm;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
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
import org.junit.jupiter.api.Test;

/**
 * Parses every distinct QVM build in the user's installation (each {@code vm/*.qvm} inside every
 * PK3, plus every loose {@code *.qvm}) and runs it up to its first syscall. Nothing read here is
 * committed or printed beyond file names and pass/fail reasons.
 */
@RealData
final class RealQvmCorpusTest {
  /** vmMain commands tried on a fresh VM: 0 is *_INIT or UI_GETAPIVERSION; 1 and 2 are not. */
  private static final int[] COMMANDS = {0, 1, 2, 9999};

  private static final class FirstSyscall extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FirstSyscall(int number) {
      super("first syscall " + number, null, false, false);
    }
  }

  private record Build(String source, byte[] bytes) {}

  @Test
  void everyDistinctQvmParsesValidatesAndRunsToItsFirstSyscall() throws Exception {
    Path root = RealDataRoot.require();
    Map<String, Build> builds = new LinkedHashMap<>();
    List<String> skippedArchives = new ArrayList<>();
    int occurrences = 0;
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    for (Path file : files) {
      String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
      if (lower.endsWith(".qvm")) {
        occurrences++;
        add(builds, root.relativize(file).toString(), Files.readAllBytes(file));
      } else if (lower.endsWith(".pk3")) {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.ISO_8859_1)) {
          for (var entries = zip.entries(); entries.hasMoreElements(); ) {
            var entry = entries.nextElement();
            String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (entry.isDirectory() || !name.startsWith("vm/") || !name.endsWith(".qvm")) continue;
            occurrences++;
            try (InputStream in = zip.getInputStream(entry)) {
              add(builds, root.relativize(file) + "!" + entry.getName(), in.readAllBytes());
            }
          }
        } catch (ZipException invalid) {
          // Names are raw bytes in PK3s, hence ISO-8859-1 above (UTF-8 rejects some archives).
          // Q3 skips unreadable PK3s (e.g. zero-byte files) with a warning; so does this sweep.
          skippedArchives.add(root.relativize(file) + " (" + invalid.getMessage() + ")");
        }
      }
    }

    List<String> rejected = new ArrayList<>();
    int ran = 0, reachedSyscall = 0, returned = 0;
    long executed = 0;
    for (Build build : builds.values()) {
      QvmImage image;
      try {
        image = QvmImage.parse(build.source(), build.bytes());
      } catch (QvmFormatException failure) {
        rejected.add(failure.getMessage());
        continue;
      }
      var vm =
          new QvmInstance(
              image,
              (self, number, args) -> {
                throw new FirstSyscall(number);
              });
      for (int command : COMMANDS) {
        vm.reset();
        try {
          vm.vmMain(command);
          returned++;
        } catch (QvmException failure) {
          if (failure.getCause() instanceof FirstSyscall) reachedSyscall++;
          else rejected.add("vmMain(" + command + ") " + failure.getMessage());
        }
        executed += vm.stats().instructions();
      }
      ran++;
    }

    System.out.printf(
        "QVM corpus: %d files, %d distinct builds, %d ran (%d calls reached a syscall, %d returned,"
            + " %d instructions), %d skipped archives%n",
        occurrences,
        builds.size(),
        ran,
        reachedSyscall,
        returned,
        executed,
        skippedArchives.size());
    skippedArchives.forEach(name -> System.out.println("  skipped " + name));
    rejected.forEach(reason -> System.out.println("  REJECTED " + reason));
    assertFalse(builds.isEmpty(), "no QVMs found under " + root);
    assertEquals(List.of(), rejected);
  }

  private static void add(Map<String, Build> builds, String source, byte[] bytes)
      throws NoSuchAlgorithmException {
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    builds.putIfAbsent(hash, new Build(source, bytes));
  }
}
