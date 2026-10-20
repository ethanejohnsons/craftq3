package dev.bluevista.craftq3.core.fs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An isolated, descriptor-pinned per-game write root. The host owns and closes this store
 * separately from VM file handles. Hosts must provision nested directories before opening it: Java
 * has no secure directory-relative mkdir, so VM requests cannot create directories. Providers
 * without SecureDirectoryStream fail closed. Quotas cover the entire root and are checked before
 * writes. Atomic replacement can temporarily add one file and at most maxFileBytes beyond the
 * persistent quota. A store serializes its own operations; unrelated host writers must not share
 * this root.
 */
public final class GameFileStore implements WritableFiles, AutoCloseable {
  public record Limits(
      int maxFileBytes, long maxTotalBytes, int maxFiles, int maxDirectories, int maxDepth) {
    public Limits {
      if (maxFileBytes < 1
          || maxFileBytes > 16 * 1024 * 1024
          || maxTotalBytes < maxFileBytes
          || maxFiles < 1
          || maxFiles > 65536
          || maxDirectories < 0
          || maxDirectories > 4096
          || maxDepth < 0
          || maxDepth > 64) throw new IllegalArgumentException("Invalid saved-file limits");
    }
  }

  /**
   * Directory count excludes the write root itself; symlinks count as files without being followed.
   */
  public record Usage(long bytes, int files, int directories) {}

  public static final Limits DEFAULT_LIMITS =
      new Limits(16 * 1024 * 1024, 256L * 1024 * 1024, 4096, 256, 16);
  private final SecureDirectoryStream<Path> root;
  private final Limits limits;
  private boolean closed;

  public GameFileStore(Path root) throws IOException {
    this(root, DEFAULT_LIMITS);
  }

  public GameFileStore(Path root, Limits limits) throws IOException {
    this.limits = java.util.Objects.requireNonNull(limits);
    // This setup path is host-chosen, never derived from a VM path. Pin it once, including on macOS
    // where /var itself is a symlink; all later traversal and mutation is descriptor-relative.
    Files.createDirectories(root);
    DirectoryStream<Path> stream = Files.newDirectoryStream(root.toRealPath());
    if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
      stream.close();
      throw new IOException("Saved files require a secure directory-stream provider");
    }
    this.root = secure;
    try {
      usage();
    } catch (IOException | RuntimeException invalid) {
      try {
        secure.close();
      } catch (IOException closeError) {
        invalid.addSuppressed(closeError);
      }
      throw invalid;
    }
  }

  @Override
  public synchronized Optional<byte[]> read(VirtualPath path) throws IOException {
    checkOpen();
    try (SecureDirectoryStream<Path> parent = parent(path)) {
      Path name = leaf(path);
      BasicFileAttributes attributes = attributes(parent, name);
      if (!attributes.isRegularFile() || attributes.size() > limits.maxFileBytes()) {
        throw new IOException("Invalid saved file: " + path.value());
      }
      try (var channel =
          parent.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        if (channel.size() > limits.maxFileBytes())
          throw new IOException("Saved file exceeds limit");
        ByteArrayOutputStream output =
            new ByteArrayOutputStream((int) Math.min(channel.size(), 8192));
        ByteBuffer chunk = ByteBuffer.allocate(8192);
        while (channel.read(chunk) != -1) {
          if ((long) output.size() + chunk.position() > limits.maxFileBytes())
            throw new IOException("Saved file exceeds limit");
          output.write(chunk.array(), 0, chunk.position());
          chunk.clear();
        }
        return Optional.of(output.toByteArray());
      }
    } catch (NoSuchFileException missing) {
      return Optional.empty();
    }
  }

  @Override
  public synchronized void write(VirtualPath path, byte[] data) throws IOException {
    checkOpen();
    if (data.length > limits.maxFileBytes()) throw new IOException("Saved file exceeds limit");
    // Executable/native/pack replacement is not part of the game-file capability.
    String name = path.value();
    if (name.endsWith(".pk3")
        || name.endsWith(".qvm")
        || name.endsWith(".dll")
        || name.endsWith(".so")
        || name.endsWith(".dylib")
        || name.endsWith(".jar")
        || name.endsWith(".class")) {
      throw new IOException("File type is not writable by a game VM: " + name);
    }
    try (SecureDirectoryStream<Path> parent = parent(path)) {
      Path file = leaf(path);
      BasicFileAttributes previous;
      try {
        previous = attributes(parent, file);
      } catch (NoSuchFileException missing) {
        previous = null;
      }
      if (previous != null && !previous.isRegularFile())
        throw new IOException("Refusing non-file write: " + name);
      Usage used = usage();
      if (used.files() + (previous == null ? 1 : 0) > limits.maxFiles()
          || used.bytes() - (previous == null ? 0 : previous.size()) + data.length
              > limits.maxTotalBytes()) {
        throw new IOException("Saved-file root quota exceeded");
      }
      Path temporary = Path.of(".craftq3-" + UUID.randomUUID() + ".tmp");
      boolean created = false;
      try {
        try (var channel =
            parent.newByteChannel(
                temporary,
                Set.of(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS))) {
          created = true;
          ByteBuffer bytes = ByteBuffer.wrap(data);
          while (bytes.hasRemaining()) channel.write(bytes);
        }
        // Atomic directory-entry replacement, never a symlink-target write. A provider that cannot
        // atomically replace an existing file rejects the operation; there is no unsafe fallback.
        parent.move(temporary, parent, file);
        created = false;
      } finally {
        if (created) parent.deleteFile(temporary);
      }
    } catch (NoSuchFileException missing) {
      throw new IOException("Saved-file parent must be provisioned by the host: " + name, missing);
    }
  }

  public synchronized Usage usage() throws IOException {
    checkOpen();
    long[] totals = new long[3];
    try (SecureDirectoryStream<Path> directory =
        root.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS)) {
      scan(directory, 0, totals);
    }
    return new Usage(totals[0], (int) totals[1], (int) totals[2]);
  }

  private void scan(SecureDirectoryStream<Path> directory, int depth, long[] totals)
      throws IOException {
    if (depth > limits.maxDepth()) throw new IOException("Saved-file directory depth exceeded");
    for (Path entry : directory) {
      Path name = entry.getFileName();
      BasicFileAttributes attributes = attributes(directory, name);
      if (attributes.isDirectory()) {
        if (++totals[2] > limits.maxDirectories())
          throw new IOException("Saved-file directory quota exceeded");
        try (SecureDirectoryStream<Path> child =
            directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
          scan(child, depth + 1, totals);
        }
      } else {
        if (!attributes.isRegularFile() && !attributes.isSymbolicLink())
          throw new IOException("Non-file in saved-file root");
        if (attributes.size() > limits.maxFileBytes())
          throw new IOException("Saved file exceeds limit");
        totals[0] += attributes.size();
        if (++totals[1] > limits.maxFiles() || totals[0] > limits.maxTotalBytes())
          throw new IOException("Saved-file root quota exceeded");
      }
    }
  }

  private SecureDirectoryStream<Path> parent(VirtualPath path) throws IOException {
    String[] parts = path.value().split("/");
    if (parts.length - 1 > limits.maxDepth())
      throw new IOException("Saved-file directory depth exceeded");
    SecureDirectoryStream<Path> current =
        root.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS);
    try {
      for (int i = 0; i < parts.length - 1; i++) {
        SecureDirectoryStream<Path> next =
            current.newDirectoryStream(Path.of(parts[i]), LinkOption.NOFOLLOW_LINKS);
        try {
          current.close();
        } catch (IOException failure) {
          next.close();
          throw failure;
        }
        current = next;
      }
      return current;
    } catch (IOException | RuntimeException failure) {
      try {
        current.close();
      } catch (IOException closeError) {
        failure.addSuppressed(closeError);
      }
      throw failure;
    }
  }

  private static Path leaf(VirtualPath path) {
    return Path.of(path.value().substring(path.value().lastIndexOf('/') + 1));
  }

  private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    return directory
        .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
        .readAttributes();
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("Saved-file store closed");
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    root.close();
  }
}
