package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Host-only cache of PK3s downloaded from servers, kept apart from the installation, which is never
 * written. Each archive is stored as {@code <checksum>-<base64url(game/name.pk3)>.pk3}, so server
 * text never becomes a host path. A download is staged in a temporary file, fully verified ({@link
 * Pk3Verifier}) and then moved into place atomically. A lock file gives one process exclusive use.
 *
 * <p>Portable: the root is canonicalised once, entries are checked with {@code NOFOLLOW_LINKS}, and
 * the root must still resolve to the same directory before every commit.
 *
 * <p>Thread safety: methods are synchronized because verification runs on a worker thread while the
 * connection thread may write or cancel.
 */
public final class DownloadCache implements AutoCloseable {
  /** Largest single download, matching the download protocol's limit. */
  public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

  public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
  public static final int MAX_FILES = 128;

  private static final String LOCK = ".lock";
  private static final Pattern TEMPORARY = Pattern.compile("\\.craftq3-download-[0-9a-f-]+\\.tmp");
  private static final Pattern OFFICIAL_BASE = Pattern.compile("pak[0-8]");
  private static final Pattern OFFICIAL_TEAM = Pattern.compile("pak[0-3]");

  /** A server pack to fetch: {@code game/name.pk3} and its announced checksum. */
  public record Request(String remoteName, int checksum) {
    public Request {
      remoteName = VirtualPath.of(remoteName).key();
      String[] parts = remoteName.split("/", -1);
      if (parts.length != 2
          || remoteName.length() > 63
          || !parts[1].endsWith(".pk3")
          || parts[1].equals(".pk3")
          || remoteName.chars().anyMatch(c -> c < 33 || c > 126 || c == '"' || c == ';'))
        throw new IllegalArgumentException("Invalid server PK3 name: " + remoteName);
      VirtualPath.gameDirectory(parts[0]);
      String stem = parts[1].substring(0, parts[1].length() - 4);
      if (parts[0].equals("baseq3") && OFFICIAL_BASE.matcher(stem).matches()
          || parts[0].equals("missionpack") && OFFICIAL_TEAM.matcher(stem).matches())
        throw new IllegalArgumentException("Official game PK3s must be installed locally");
    }

    public String game() {
      return remoteName.substring(0, remoteName.indexOf('/'));
    }

    /** Pack base name without {@code .pk3}. */
    public String name() {
      return remoteName.substring(remoteName.indexOf('/') + 1, remoteName.length() - 4);
    }

    String filename() {
      return String.format(Locale.ROOT, "%08x-", checksum)
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(remoteName.getBytes(StandardCharsets.US_ASCII))
          + ".pk3";
    }
  }

  /** A verified cached archive. */
  public record Entry(Request request, Path path) {}

  private record Usage(long bytes, int files) {}

  private final Path directory;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private Pending pending;
  private volatile boolean closed;

  private DownloadCache(Path directory, FileChannel lockChannel, FileLock lock) {
    this.directory = directory;
    this.lockChannel = lockChannel;
    this.lock = lock;
  }

  /**
   * Opens (creating if needed) the cache at {@code directory}, which must not be a symbolic link.
   *
   * @throws java.nio.channels.OverlappingFileLockException if this process already holds it
   * @throws IOException if another process holds it or it contains unexpected files
   */
  public static DownloadCache open(Path directory) throws IOException {
    if (directory.getFileSystem() != FileSystems.getDefault())
      throw new IOException("Download cache must be on the default file system");
    if (Files.isSymbolicLink(directory))
      throw new IOException("Download cache cannot be a symbolic link: " + directory);
    Files.createDirectories(directory);
    Path root = directory.toRealPath();
    FileChannel channel =
        FileChannel.open(
            root.resolve(LOCK),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    FileLock held = null;
    try {
      held = channel.tryLock();
      if (held == null) throw new IOException("Download cache is in use: " + root);
      var cache = new DownloadCache(root, channel, held);
      cache.scan();
      return cache;
    } catch (IOException | RuntimeException failure) {
      try {
        if (held != null) held.close();
        channel.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  /** The canonical cache directory. */
  public Path directory() {
    return directory;
  }

  /** Verified archives, oldest first. */
  public synchronized List<Entry> entries() throws IOException {
    checkOpen();
    scan();
    var result = new ArrayList<Entry>();
    var modified = new HashMap<Path, Long>();
    for (Path file : listing()) {
      String name = file.getFileName().toString();
      if (!name.endsWith(".pk3")) continue;
      Request request;
      try {
        if (name.length() < 14 || name.charAt(8) != '-') throw new IllegalArgumentException(name);
        int checksum = (int) Long.parseLong(name.substring(0, 8), 16);
        String remote =
            new String(
                Base64.getUrlDecoder().decode(name.substring(9, name.length() - 4)),
                StandardCharsets.US_ASCII);
        request = new Request(remote, checksum);
        if (!request.filename().equals(name)) throw new IllegalArgumentException(name);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Invalid cached PK3 identity: " + name, invalid);
      }
      result.add(new Entry(request, file));
      modified.put(file, attributes(file).lastModifiedTime().toMillis());
    }
    result.sort(
        Comparator.comparingLong((Entry e) -> modified.get(e.path()))
            .thenComparing(e -> e.path().toString()));
    return List.copyOf(result);
  }

  /** Starts staging one download. Only one download may be pending at a time. */
  public synchronized Pending begin(Request request) throws IOException {
    checkOpen();
    if (pending != null) throw new IOException("A download is already pending");
    Usage usage = scan();
    if (usage.files() >= MAX_FILES) throw new IOException("Download cache file quota exceeded");
    if (Files.exists(directory.resolve(request.filename()), LinkOption.NOFOLLOW_LINKS))
      throw new IOException("PK3 already exists in download cache");
    Path temporary = directory.resolve(".craftq3-download-" + UUID.randomUUID() + ".tmp");
    FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    pending =
        new Pending(
            request, temporary, channel, Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - usage.bytes()));
    return pending;
  }

  private List<Path> listing() throws IOException {
    try (var children = Files.list(directory)) {
      return children.sorted().toList();
    }
  }

  private static BasicFileAttributes attributes(Path file) throws IOException {
    return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private Usage scan() throws IOException {
    long bytes = 0;
    int files = 0;
    for (Path file : listing()) {
      String name = file.getFileName().toString();
      if (name.equals(LOCK)) continue;
      BasicFileAttributes attributes = attributes(file);
      if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES)
        throw new IOException("Invalid file in download cache: " + name);
      if (!name.endsWith(".pk3") && !TEMPORARY.matcher(name).matches())
        throw new IOException("Unexpected file in download cache: " + name);
      bytes += attributes.size();
      files++;
      if (bytes > MAX_TOTAL_BYTES || files > MAX_FILES)
        throw new IOException("Download cache quota exceeded");
    }
    return new Usage(bytes, files);
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("Download cache closed");
  }

  /** Fails if the cache directory or staged file were replaced since staging began. */
  private void guard(Path file, Object fileKey, long size) throws IOException {
    checkOpen();
    if (!directory.toRealPath().equals(directory))
      throw new IOException("Download cache directory moved");
    BasicFileAttributes attributes = attributes(file);
    if (!attributes.isRegularFile()
        || !Objects.equals(fileKey, attributes.fileKey())
        || attributes.size() != size) throw new IOException("Download staging file changed");
  }

  /** A download being staged; write the bytes, then {@link #verifyAndCommit}. */
  public final class Pending extends OutputStream {
    private final Request request;
    private final Path temporary;
    private final FileChannel channel;
    private final long limit;
    private long bytes;
    private boolean verifying, committed;
    private volatile boolean cancelled;

    private Pending(Request request, Path temporary, FileChannel channel, long limit) {
      this.request = request;
      this.temporary = temporary;
      this.channel = channel;
      this.limit = limit;
    }

    public Request request() {
      return request;
    }

    public long bytesWritten() {
      synchronized (DownloadCache.this) {
        return bytes;
      }
    }

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] data, int offset, int count) throws IOException {
      Objects.checkFromIndexSize(offset, count, data.length);
      synchronized (DownloadCache.this) {
        checkOpen();
        if (cancelled || committed || verifying)
          throw new IOException("Download staging writer is closed");
        if (count > limit - bytes) throw new IOException("Download cache byte quota exceeded");
        var buffer = ByteBuffer.wrap(data, offset, count);
        while (buffer.hasRemaining()) {
          int written = channel.write(buffer);
          if (written <= 0) throw new IOException("Download write made no progress");
          bytes += written;
        }
      }
    }

    @Override
    public void flush() throws IOException {
      synchronized (DownloadCache.this) {
        if (channel.isOpen()) channel.force(false);
      }
    }

    /** Verifies and commits; run on a worker. The cache lock is not held while verifying. */
    public Entry verifyAndCommit() throws IOException {
      Object key;
      long size;
      try {
        synchronized (DownloadCache.this) {
          checkOpen();
          if (cancelled || committed || verifying)
            throw new IOException("Download no longer pending");
          verifying = true;
          channel.force(true);
          channel.close();
          key = attributes(temporary).fileKey();
          size = bytes;
        }
        guard(temporary, key, size);
        Pk3Verifier.verify(
            temporary,
            request.checksum(),
            () -> cancelled || closed || Thread.currentThread().isInterrupted());
        synchronized (DownloadCache.this) {
          guard(temporary, key, size);
          if (cancelled || pending != this) throw new IOException("Download cancelled");
          scan();
          Path target = directory.resolve(request.filename());
          if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Download target already exists");
          try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
          } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target);
          }
          committed = true;
          pending = null;
          return new Entry(request, target);
        }
      } catch (IOException | RuntimeException failure) {
        try {
          close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    /** Cancels a download that was not committed and removes its staging file. */
    @Override
    public void close() throws IOException {
      synchronized (DownloadCache.this) {
        if (cancelled || committed) return;
        cancelled = true;
        try {
          channel.close();
        } finally {
          try {
            Files.deleteIfExists(temporary);
          } finally {
            if (pending == this) pending = null;
          }
        }
      }
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    try {
      if (pending != null) pending.close();
    } finally {
      closed = true;
      try {
        lock.close();
      } finally {
        lockChannel.close();
      }
    }
  }
}
