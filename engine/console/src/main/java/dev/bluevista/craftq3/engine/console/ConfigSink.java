package dev.bluevista.craftq3.engine.console;

import java.io.IOException;

/**
 * Receives files written by {@code writeconfig}. The host backs it with the writable game home and
 * encodes text as ISO-8859-1; the implementation should replace the file atomically and reject
 * unsafe paths.
 */
@FunctionalInterface
public interface ConfigSink {
  /** A sink that cannot write; {@code writeconfig} reports the failure. */
  ConfigSink NONE =
      (path, text) -> {
        throw new IOException("No writable game home");
      };

  void write(String path, String text) throws IOException;
}
