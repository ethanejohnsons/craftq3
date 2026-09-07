package dev.bluevista.craftq3.assets.fs;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.net.DownloadSender;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Host-only, isolated PK3 cache. Server paths are encoded names; originals are never replaced. */
public final class DownloadCache implements AutoCloseable {
  public record Request(String remoteName, int checksum) {
    public Request {
      var path = new VirtualPath(remoteName);
      remoteName = path.value();
      String[] parts = remoteName.split("/", -1);
      if (parts.length != 2
          || remoteName.length() > 63
          || !parts[1].endsWith(".pk3")
          || parts[1].equals(".pk3")
          || remoteName.chars().anyMatch(c -> c < 33 || c > 126 || c == '"' || c == ';'))
        throw new IllegalArgumentException("Invalid server PK3 name");
      VirtualPath.gameDirectory(parts[0]);
      String stem = parts[1].substring(0, parts[1].length() - 4);
      if (parts[0].equals("baseq3") && stem.matches("pak[0-8]")
          || parts[0].equals("missionpack") && stem.matches("pak[0-3]"))
        throw new IllegalArgumentException("Official game PK3s must be installed locally");
    }

    public String game() {
      return remoteName.substring(0, remoteName.indexOf('/'));
    }

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

  public record Entry(Request request, Path path) {}

  private static final long TOTAL_LIMIT = 2L * 1024 * 1024 * 1024;
  private static final int FILE_LIMIT = 128;

  private record Usage(long bytes, int files) {}

  private final Path directory;
  private final SecureDirectoryStream<Path> root;
  private final Object directoryKey;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private Pending pending;
  private volatile boolean closed;

  public DownloadCache(Path directory) throws IOException {
    Files.createDirectories(directory);
    if (Files.isSymbolicLink(directory))
      throw new IOException("Download cache cannot be a symlink");
    this.directory = directory.toRealPath();
    var stream = Files.newDirectoryStream(this.directory);
    if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
      stream.close();
      throw new IOException("Download cache requires secure directory streams");
    }
    root = secure;
    directoryKey =
        root.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
    SeekableByteChannel opened = null;
    FileLock held = null;
    try {
      opened =
          root.newByteChannel(
              Path.of(".lock"),
              Set.of(
                  StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
      if (!(opened instanceof FileChannel file))
        throw new IOException("Download cache requires file locking");
      held = file.tryLock();
      if (held == null) throw new IOException("Download cache is in use");
      scan();
      lockChannel = file;
      lock = held;
    } catch (IOException | RuntimeException failure) {
      if (held != null) held.close();
      if (opened != null) opened.close();
      root.close();
      throw failure;
    }
  }

  public synchronized List<Entry> entries() throws IOException {
    checkOpen();
    scan();
    var result = new ArrayList<Entry>();
    var modified = new HashMap<Path, Long>();
    try (var listing = root.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS)) {
      for (Path file : listing) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".pk3")) continue;
        try {
          if (name.length() < 14 || name.charAt(8) != '-') throw new IllegalArgumentException();
          int checksum = (int) Long.parseLong(name.substring(0, 8), 16);
          String remote =
              new String(
                  Base64.getUrlDecoder().decode(name.substring(9, name.length() - 4)),
                  StandardCharsets.US_ASCII);
          var request = new Request(remote, checksum);
          if (!request.filename().equals(name)) throw new IllegalArgumentException();
          Path path = directory.resolve(name);
          result.add(new Entry(request, path));
          modified.put(path, attributes(file.getFileName()).lastModifiedTime().toMillis());
        } catch (IllegalArgumentException invalid) {
          throw new IOException("Invalid cached PK3 identity", invalid);
        }
      }
    }
    result.sort(
        Comparator.comparingLong((Entry e) -> modified.get(e.path()))
            .thenComparing(e -> e.path().toString()));
    return List.copyOf(result);
  }

  public synchronized void mountCached(Pk3FileSystem fs) throws IOException {
    for (var entry : entries())
      if (entry.request().game().equals("baseq3")
          || entry.request().game().equals(fs.gameDirectory()))
        if (fs.packs().stream().noneMatch(p -> p.checksum() == entry.request().checksum()))
          fs.attachArchive(
              entry.path(),
              entry.request().game(),
              entry.request().name(),
              entry.request().checksum());
  }

  public synchronized Pending begin(Request request) throws IOException {
    checkOpen();
    if (pending != null) throw new IOException("A download is already pending");
    Usage usage = scan();
    if (usage.files() >= FILE_LIMIT) throw new IOException("Download cache file quota exceeded");
    if (exists(Path.of(request.filename())))
      throw new IOException("PK3 already exists in download cache");
    Path temporary = Path.of(".craftq3-download-" + UUID.randomUUID() + ".tmp");
    var channel =
        root.newByteChannel(
            temporary,
            Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS));
    pending =
        new Pending(
            request,
            temporary,
            channel,
            Math.min(DownloadSender.MAX_FILE_BYTES, TOTAL_LIMIT - usage.bytes()));
    return pending;
  }

  private BasicFileAttributes attributes(Path name) throws IOException {
    return root.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
        .readAttributes();
  }

  private boolean exists(Path name) throws IOException {
    try {
      attributes(name);
      return true;
    } catch (NoSuchFileException missing) {
      return false;
    }
  }

  private Usage scan() throws IOException {
    long bytes = 0;
    int files = 0;
    try (var listing = root.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS)) {
      for (Path file : listing) {
        Path name = file.getFileName();
        if (name.toString().equals(".lock")) continue;
        var attributes = attributes(name);
        if (!attributes.isRegularFile() || attributes.size() > DownloadSender.MAX_FILE_BYTES)
          throw new IOException("Invalid file in download cache");
        if (!name.toString().endsWith(".pk3")
            && !name.toString().matches("\\.craftq3-download-[0-9a-f-]+\\.tmp"))
          throw new IOException("Unexpected file in download cache");
        bytes += attributes.size();
        files++;
        if (bytes > TOTAL_LIMIT || files > FILE_LIMIT)
          throw new IOException("Download cache quota exceeded");
      }
    }
    return new Usage(bytes, files);
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("Download cache closed");
  }

  private void guardPath(Path name, Object fileKey, long size) throws IOException {
    checkOpen();
    var dir = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    var file =
        Files.readAttributes(
            directory.resolve(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!dir.isDirectory()
        || !Objects.equals(directoryKey, dir.fileKey())
        || !file.isRegularFile()
        || !Objects.equals(fileKey, file.fileKey())
        || file.size() != size) throw new IOException("Download staging path changed");
  }

  public final class Pending extends OutputStream {
    private final Request request;
    private final Path temporary;
    private final SeekableByteChannel channel;
    private final long limit;
    private long bytes;
    private boolean verifying, committed;
    private volatile boolean cancelled;

    Pending(Request request, Path temporary, SeekableByteChannel channel, long limit) {
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
          int n = channel.write(buffer);
          if (n <= 0) throw new IOException("Download write made no progress");
          bytes += n;
        }
      }
    }

    @Override
    public void flush() throws IOException {
      synchronized (DownloadCache.this) {
        if (channel instanceof FileChannel file && channel.isOpen()) file.force(false);
      }
    }

    /** Run on a worker; no cache lock is held while validating compressed members. */
    public Entry verifyAndCommit() throws IOException {
      Object key;
      long size;
      try {
        synchronized (DownloadCache.this) {
          checkOpen();
          if (cancelled || committed || verifying)
            throw new IOException("Download no longer pending");
          verifying = true;
          flush();
          channel.close();
          var attrs = attributes(temporary);
          key = attrs.fileKey();
          size = bytes;
        }
        guardPath(temporary, key, size);
        Pk3Verifier.verify(
            directory.resolve(temporary),
            request.checksum(),
            () -> cancelled || closed || Thread.currentThread().isInterrupted());
        synchronized (DownloadCache.this) {
          guardPath(temporary, key, size);
          if (cancelled || pending != this) throw new IOException("Download cancelled");
          scan();
          Path target = Path.of(request.filename());
          if (exists(target)) throw new IOException("Download target already exists");
          root.move(temporary, root, target);
          committed = true;
          pending = null;
          return new Entry(request, directory.resolve(target));
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

    @Override
    public void close() throws IOException {
      synchronized (DownloadCache.this) {
        if (cancelled || committed) return;
        cancelled = true;
        try {
          channel.close();
        } finally {
          try {
            root.deleteFile(temporary);
          } catch (NoSuchFileException ignored) {
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
        try {
          lockChannel.close();
        } finally {
          root.close();
        }
      }
    }
  }
}
