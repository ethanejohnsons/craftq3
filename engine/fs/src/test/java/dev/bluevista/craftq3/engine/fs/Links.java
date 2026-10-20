package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/** Symbolic links for tests; skips the calling test where the OS or account cannot make them. */
final class Links {
  private Links() {}

  /** Creates {@code link -> target}, or aborts the test as skipped. Always returns true. */
  static boolean create(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (UnsupportedOperationException | FileSystemException unsupported) {
      // Windows without developer mode or the create-symlink privilege.
      Assumptions.abort("Symbolic links are not available: " + unsupported);
      return false;
    }
  }
}
