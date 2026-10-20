package dev.bluevista.craftq3.engine.console;

import java.util.Optional;

/**
 * Supplies script files to {@code exec}. The host backs it with the game filesystem (home directory
 * first, then the search path) and decodes bytes as ISO-8859-1. Paths are game paths such as {@code
 * q3config.cfg}; the implementation rejects unsafe ones.
 */
@FunctionalInterface
public interface ConfigSource {
  /** A source with no files; every {@code exec} reports that it could not exec. */
  ConfigSource NONE = path -> Optional.empty();

  /** The file's text, or empty when it does not exist or cannot be read. */
  Optional<String> read(String path);
}
