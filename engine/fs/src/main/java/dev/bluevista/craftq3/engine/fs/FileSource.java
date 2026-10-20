package dev.bluevista.craftq3.engine.fs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Read access to game files by virtual path. Implementations say whether they are shareable. */
public interface FileSource {
  boolean exists(VirtualPath path);

  /**
   * Reads a whole file.
   *
   * @throws java.nio.file.NoSuchFileException if the file is not visible
   */
  byte[] read(VirtualPath path) throws IOException;

  /** Streams a file; the caller closes the stream. */
  default InputStream open(VirtualPath path) throws IOException {
    return new ByteArrayInputStream(read(path));
  }

  /** Reads a text file as bytes-to-chars (ISO-8859-1), as the original engine treats text. */
  default String readText(VirtualPath path) throws IOException {
    return new String(read(path), StandardCharsets.ISO_8859_1);
  }
}
