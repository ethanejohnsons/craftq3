package dev.bluevista.craftq3.core.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Read-only engine contract. Write paths will require a separate, constrained capability. */
public interface VirtualFileSystem extends AutoCloseable {
  record Origin(String game, String container, boolean archive) {}

  Optional<Origin> which(VirtualPath path);

  List<VirtualPath> list(String directory);

  List<Origin> searchOrder();

  byte[] read(VirtualPath path) throws IOException;

  /** Caller owns the stream; closing the filesystem may invalidate it. */
  default java.io.InputStream open(VirtualPath path) throws IOException {
    return new java.io.ByteArrayInputStream(read(path));
  }

  default String readText(VirtualPath path) throws IOException {
    return new String(read(path), StandardCharsets.ISO_8859_1);
  }

  @Override
  void close() throws IOException;
}
