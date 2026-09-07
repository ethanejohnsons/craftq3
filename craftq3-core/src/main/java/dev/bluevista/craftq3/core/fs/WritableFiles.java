package dev.bluevista.craftq3.core.fs;

import java.io.IOException;
import java.util.Optional;

/**
 * Separate capability for game saves/configs/logs. Paths are always normalized Q3 virtual paths.
 */
public interface WritableFiles {
  Optional<byte[]> read(VirtualPath path) throws IOException;

  void write(VirtualPath path, byte[] data) throws IOException;
}
