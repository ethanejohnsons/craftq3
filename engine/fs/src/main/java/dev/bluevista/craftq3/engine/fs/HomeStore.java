package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one writable store for a game: configs, logs, screenshots, demos and other VM writes. It
 * replaces the legacy descriptor-pinned stores, which needed {@code SecureDirectoryStream} and so
 * failed on Windows.
 *
 * <p>Safety is path-based and portable:
 *
 * <ul>
 *   <li>The root is canonicalised ({@code toRealPath}) once at open. Every operation walks the
 *       parent components with {@link LinkOption#NOFOLLOW_LINKS}, refuses any that is not a plain
 *       directory, and checks that the parent's real path is still inside the root, so a link or a
 *       moved root cannot redirect a write outside it.
 *   <li>Writes go to a temporary file in the target's directory and are moved over the target with
 *       {@code ATOMIC_MOVE} (a plain replace where the file system cannot move atomically). A link
 *       at the target name is replaced, never followed.
 *   <li>Missing parent directories are created, within the depth and directory quotas.
 *   <li>Names that are not portable (Windows device names, trailing dots or spaces, {@code
 *       <>"|?*}), and executable or pack types ({@code .pk3 .qvm .exe .dll .so .dylib .jar .class})
 *       are refused.
 * </ul>
 *
 * <p>New files are named in lookup form (lower case); existing files and directories are found
 * case-insensitively, so a user's {@code Autoexec.cfg} is read and replaced in place.
 *
 * <p>Usage is measured at open and then tracked for this store's own writes; files the store did
 * not write count once seen. A home that is already over quota opens, but writes that would grow it
 * fail.
 *
 * <p>Thread safety: methods are synchronized because the qagame (sim thread) and the cgame/ui
 * (render thread) handle tables share one store and its quota. Streams returned by {@link
 * #openRead} and {@link #openAtomicWrite} belong to the thread that opened them.
 */
public final class HomeStore implements FileSource, AutoCloseable {
  /** Quotas for one store. */
  public record Limits(
      long maxFileBytes, long maxTotalBytes, int maxFiles, int maxDirectories, int maxDepth) {
    /** Hard caps that no configuration may exceed. */
    public static final long MAX_FILE_CAP = 512L * 1024 * 1024;

    public static final long MAX_TOTAL_CAP = 4L * 1024 * 1024 * 1024;

    public Limits {
      if (maxFileBytes < 1
          || maxFileBytes > MAX_FILE_CAP
          || maxTotalBytes < maxFileBytes
          || maxTotalBytes > MAX_TOTAL_CAP
          || maxFiles < 1
          || maxFiles > 65536
          || maxDirectories < 0
          || maxDirectories > 4096
          || maxDepth < 0
          || maxDepth > 64) throw new IllegalArgumentException("Invalid home store limits");
    }
  }

  /** Bytes and files counted for the quota; directories exclude the root. */
  public record Usage(long bytes, int files, int directories) {}

  /** 16 MiB per file, 256 MiB in total, 4096 files, 256 directories, 16 levels. */
  public static final Limits DEFAULT_LIMITS =
      new Limits(16L * 1024 * 1024, 256L * 1024 * 1024, 4096, 256, 16);

  /** Extensions a game may never write. */
  public static final Set<String> FORBIDDEN_EXTENSIONS =
      Set.of("pk3", "qvm", "exe", "dll", "so", "dylib", "jar", "class");

  /** Most streaming readers open at once. */
  public static final int MAX_READERS = 16;

  private static final String TEMPORARY_PREFIX = ".craftq3-";
  private static final Pattern DEVICE_NAME =
      Pattern.compile("(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");
  private static final String NOT_PORTABLE = "<>\"|?*";
  private static final int SCAN_DEPTH = 64;

  private final Path root;
  private final Limits limits;
  private final Set<Reader> readers = new HashSet<>();
  private long bytes;
  private int files;
  private int directories;
  private AtomicOutput writer;
  private boolean closed;

  private HomeStore(Path root, Limits limits) {
    this.root = root;
    this.limits = limits;
  }

  public static HomeStore open(Path root) throws IOException {
    return open(root, DEFAULT_LIMITS);
  }

  /**
   * Opens (creating if needed) the store at the host-chosen {@code root}.
   *
   * @throws IOException if the root is not a directory on the default file system
   */
  public static HomeStore open(Path root, Limits limits) throws IOException {
    Objects.requireNonNull(limits);
    if (root.getFileSystem() != FileSystems.getDefault())
      throw new IOException("Home store must be on the default file system: " + root);
    Files.createDirectories(root);
    Path real = root.toRealPath();
    if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Home store root is not a directory: " + root);
    var store = new HomeStore(real, limits);
    store.measure();
    return store;
  }

  private void measure() throws IOException {
    long[] totals = new long[3];
    Files.walkFileTree(
        root,
        EnumSet.noneOf(java.nio.file.FileVisitOption.class),
        SCAN_DEPTH,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            if (!dir.equals(root)) totals[2]++;
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isDirectory()) totals[2]++;
            else {
              totals[1]++;
              if (attributes.isRegularFile()) totals[0] += attributes.size();
            }
            return FileVisitResult.CONTINUE;
          }
        });
    bytes = totals[0];
    files = (int) Math.min(Integer.MAX_VALUE, totals[1]);
    directories = (int) Math.min(Integer.MAX_VALUE, totals[2]);
  }

  /** The canonical root captured at open. */
  public Path root() {
    return root;
  }

  public Limits limits() {
    return limits;
  }

  public synchronized Usage usage() throws IOException {
    checkOpen();
    return new Usage(bytes, files, directories);
  }

  @Override
  public synchronized boolean exists(VirtualPath path) {
    if (closed) return false;
    try {
      Path file = locate(path);
      return file != null && regular(file) != null;
    } catch (IOException unsafe) {
      return false;
    }
  }

  @Override
  public byte[] read(VirtualPath path) throws IOException {
    return readIfPresent(path).orElseThrow(() -> new NoSuchFileException(path.key()));
  }

  /** Reads a whole file, or empty when it does not exist. */
  public Optional<byte[]> readIfPresent(VirtualPath path) throws IOException {
    Optional<InputStream> stream = openStream(path, false);
    if (stream.isEmpty()) return Optional.empty();
    try (InputStream input = stream.get()) {
      return Optional.of(input.readAllBytes());
    }
  }

  /**
   * Streams a file bounded to its size when opened, or empty when it does not exist. At most {@link
   * #MAX_READERS} are open at once; closing the store closes them.
   */
  public Optional<InputStream> openRead(VirtualPath path) throws IOException {
    return openStream(path, true);
  }

  private synchronized Optional<InputStream> openStream(VirtualPath path, boolean tracked)
      throws IOException {
    checkOpen();
    if (tracked && readers.size() >= MAX_READERS)
      throw new IOException("Too many open home store readers");
    Path file = locate(path);
    if (file == null) return Optional.empty();
    BasicFileAttributes attributes = attributes(file);
    if (attributes == null) return Optional.empty();
    if (!attributes.isRegularFile())
      throw new IOException("Not a regular home file: " + path.key());
    if (attributes.size() > limits.maxFileBytes())
      throw new IOException("Home file exceeds " + limits.maxFileBytes() + " bytes: " + path.key());
    // Read a snapshot and release the OS handle at once: Windows refuses to replace a file that
    // any handle has open, and a later atomic save of the same file must still succeed.
    byte[] data;
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long length = channel.size();
      if (length > limits.maxFileBytes())
        throw new IOException("Home file exceeds its limit: " + path.key());
      data = new byte[(int) length];
      ByteBuffer buffer = ByteBuffer.wrap(data);
      while (buffer.hasRemaining())
        if (channel.read(buffer) < 0) throw new IOException("Home file shrank while reading");
    }
    Reader reader = new Reader(data, tracked);
    if (tracked) readers.add(reader);
    return Optional.of(reader);
  }

  /** Atomically creates or replaces a file. */
  public synchronized void write(VirtualPath path, byte[] data) throws IOException {
    checkOpen();
    checkWritable(path);
    if (data.length > limits.maxFileBytes())
      throw new IOException("Home file exceeds " + limits.maxFileBytes() + " bytes: " + path.key());
    Path target = prepare(path);
    BasicFileAttributes previous = existingFile(target, path);
    long previousSize = previous == null ? 0 : previous.size();
    if (files + (previous == null ? 1 : 0) > limits.maxFiles()
        || bytes - previousSize + data.length > limits.maxTotalBytes())
      throw new IOException("Home store quota exceeded writing " + path.key());
    Path temporary = temporaryFor(target);
    try {
      try (FileChannel channel = createTemporary(temporary)) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(false);
      }
      replace(temporary, target);
    } finally {
      Files.deleteIfExists(temporary);
    }
    if (previous == null) files++;
    bytes += data.length - previousSize;
  }

  /**
   * Starts a streaming atomic write, for demos and other large files: bytes go to a temporary file
   * and replace the target only on {@link AtomicOutput#commit}; closing without commit aborts. One
   * streaming write may be open at a time.
   */
  public synchronized AtomicOutput openAtomicWrite(VirtualPath path) throws IOException {
    checkOpen();
    checkWritable(path);
    if (writer != null) throw new IOException("A streaming home write is already open");
    Path target = prepare(path);
    BasicFileAttributes previous = existingFile(target, path);
    long previousSize = previous == null ? 0 : previous.size();
    if (files + (previous == null ? 1 : 0) > limits.maxFiles())
      throw new IOException("Home store file quota exceeded writing " + path.key());
    long allowance =
        Math.max(0, Math.min(limits.maxFileBytes(), limits.maxTotalBytes() - bytes + previousSize));
    Path temporary = temporaryFor(target);
    FileChannel channel = createTemporary(temporary);
    writer = new AtomicOutput(path, target, temporary, previous, channel, allowance);
    return writer;
  }

  /**
   * Names directly inside {@code directory} whose name ends with {@code extension} (compared
   * case-insensitively), sorted, as spelled on disk. Missing directories list nothing.
   */
  public synchronized List<String> list(String directory, String extension) throws IOException {
    checkOpen();
    String prefix = VirtualPath.directoryKey(directory);
    Path dir = root;
    if (!prefix.isEmpty()) {
      dir = locate(VirtualPath.of(prefix.substring(0, prefix.length() - 1)));
      if (dir == null) return List.of();
      BasicFileAttributes attributes = attributes(dir);
      if (attributes == null || !attributes.isDirectory()) return List.of();
    }
    String suffix = extension == null ? "" : VirtualPath.fold(extension);
    var result = new ArrayList<String>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
      for (Path child : children) {
        String name = child.getFileName().toString();
        if (name.startsWith(TEMPORARY_PREFIX) || !VirtualPath.fold(name).endsWith(suffix)) continue;
        BasicFileAttributes attributes = attributes(child);
        if (attributes != null && attributes.isRegularFile()) result.add(name);
      }
    }
    result.sort(null);
    return List.copyOf(result);
  }

  // ---- path resolution ----

  /** Resolves an existing path case-insensitively without creating anything; null if missing. */
  private Path locate(VirtualPath path) throws IOException {
    String[] parts = components(path);
    Path current = root;
    for (int i = 0; i < parts.length; i++) {
      Path next = child(current, parts[i]);
      if (next == null) return null;
      if (i < parts.length - 1) {
        BasicFileAttributes attributes = attributes(next);
        if (attributes == null) return null;
        if (!attributes.isDirectory())
          throw new IOException("Home path component is not a directory: " + path.key());
      }
      current = next;
    }
    checkInside(current.getParent(), path.key());
    return current;
  }

  /** Resolves a write target, creating missing parent directories within quota. */
  private Path prepare(VirtualPath path) throws IOException {
    String[] parts = components(path);
    Path current = root;
    for (int i = 0; i < parts.length - 1; i++) {
      Path next = child(current, parts[i]);
      if (next == null) {
        if (directories + 1 > limits.maxDirectories())
          throw new IOException("Home store directory quota exceeded writing " + path.key());
        next = current.resolve(parts[i]);
        try {
          Files.createDirectory(next);
          directories++;
        } catch (FileAlreadyExistsException raced) {
          // Checked below like any existing component.
        }
      }
      BasicFileAttributes attributes = attributes(next);
      if (attributes == null || !attributes.isDirectory())
        throw new IOException("Home path component is not a plain directory: " + path.key());
      current = next;
    }
    checkInside(current, path.key());
    Path leaf = child(current, parts[parts.length - 1]);
    return leaf != null ? leaf : current.resolve(parts[parts.length - 1]);
  }

  private String[] components(VirtualPath path) throws IOException {
    String[] parts = path.key().split("/");
    if (parts.length - 1 > limits.maxDepth())
      throw new IOException("Home path is deeper than " + limits.maxDepth() + ": " + path.key());
    for (String part : parts) {
      if (DEVICE_NAME.matcher(part).matches()
          || part.endsWith(".")
          || part.endsWith(" ")
          || part.startsWith(TEMPORARY_PREFIX)
          || part.chars().anyMatch(c -> NOT_PORTABLE.indexOf(c) >= 0))
        throw new IOException("Home path is not portable: " + path.spelling());
    }
    return parts;
  }

  /** The entry of {@code dir} named {@code key}, matched case-insensitively; null if none. */
  private static Path child(Path dir, String key) throws IOException {
    Path exact = dir.resolve(key);
    if (attributes(exact) != null) return exact;
    Path match = null;
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
      for (Path child : children) {
        String name = child.getFileName().toString();
        if (VirtualPath.fold(name).equals(key)
            && (match == null || name.compareTo(match.getFileName().toString()) < 0)) match = child;
      }
    } catch (NoSuchFileException missing) {
      return null;
    }
    return match;
  }

  /** Fails unless {@code directory}'s real path is the root or inside it. */
  private void checkInside(Path directory, String what) throws IOException {
    Path real;
    try {
      real = directory.toRealPath();
    } catch (NoSuchFileException moved) {
      throw new IOException("Home store directory disappeared: " + what, moved);
    }
    if (!real.startsWith(root)) throw new IOException("Home path escapes the store root: " + what);
  }

  private static BasicFileAttributes attributes(Path file) throws IOException {
    try {
      return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException missing) {
      return null;
    }
  }

  private static BasicFileAttributes regular(Path file) throws IOException {
    BasicFileAttributes attributes = attributes(file);
    return attributes != null && attributes.isRegularFile() ? attributes : null;
  }

  private static BasicFileAttributes existingFile(Path target, VirtualPath path)
      throws IOException {
    BasicFileAttributes previous = attributes(target);
    if (previous != null && !previous.isRegularFile())
      throw new IOException("Refusing to replace a non-file: " + path.key());
    return previous;
  }

  /** Fails for file types a game may never write. */
  static void checkWritable(VirtualPath path) throws IOException {
    if (FORBIDDEN_EXTENSIONS.contains(path.extension()))
      throw new IOException("File type is not writable by a game: " + path.key());
  }

  private static Path temporaryFor(Path target) {
    return target.resolveSibling(TEMPORARY_PREFIX + UUID.randomUUID() + ".tmp");
  }

  private static FileChannel createTemporary(Path temporary) throws IOException {
    return FileChannel.open(
        temporary,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
  }

  private void replace(Path temporary, Path target) throws IOException {
    checkInside(target.getParent(), target.getFileName().toString());
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("Home store closed");
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    IOException failure = null;
    if (writer != null)
      try {
        writer.abort();
      } catch (IOException problem) {
        failure = problem;
      }
    for (Reader reader : List.copyOf(readers)) reader.close();
    closed = true;
    if (failure != null) throw failure;
  }

  /** A reader over a snapshot taken when the file was opened. */
  private final class Reader extends InputStream {
    private final byte[] data;
    private final boolean tracked;
    private int position;
    private boolean ended;

    Reader(byte[] data, boolean tracked) {
      this.data = data;
      this.tracked = tracked;
    }

    @Override
    public int read() throws IOException {
      if (ended) throw new IOException("Home store reader closed");
      return position < data.length ? data[position++] & 255 : -1;
    }

    @Override
    public int read(byte[] bytes, int offset, int count) throws IOException {
      Objects.checkFromIndexSize(offset, count, bytes.length);
      if (ended) throw new IOException("Home store reader closed");
      if (count == 0) return 0;
      if (position >= data.length) return -1;
      int read = Math.min(count, data.length - position);
      System.arraycopy(data, position, bytes, offset, read);
      position += read;
      return read;
    }

    @Override
    public void close() {
      if (ended) return;
      ended = true;
      if (tracked)
        synchronized (HomeStore.this) {
          readers.remove(this);
        }
    }
  }

  /** A streaming write; {@link #commit} publishes it, {@link #close} without commit aborts. */
  public final class AtomicOutput extends OutputStream {
    private final VirtualPath path;
    private final Path target;
    private final Path temporary;
    private final BasicFileAttributes previous;
    private final FileChannel channel;
    private final long allowance;
    private long written;
    private boolean ended, failed, committed;

    private AtomicOutput(
        VirtualPath path,
        Path target,
        Path temporary,
        BasicFileAttributes previous,
        FileChannel channel,
        long allowance) {
      this.path = path;
      this.target = target;
      this.temporary = temporary;
      this.previous = previous;
      this.channel = channel;
      this.allowance = allowance;
    }

    /** Most bytes this write may hold, from the file and total quotas at open. */
    public long byteLimit() {
      return allowance;
    }

    public long bytesWritten() {
      return written;
    }

    public boolean committed() {
      return committed;
    }

    private void writable() throws IOException {
      checkOpen();
      if (ended || failed) throw new IOException("Home store writer closed or failed");
    }

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, data.length);
      synchronized (HomeStore.this) {
        writable();
        try {
          if (length > allowance - written)
            throw new IOException("Home store quota exceeded writing " + path.key());
          ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
          while (buffer.hasRemaining()) {
            int count = channel.write(buffer);
            if (count <= 0) throw new IOException("Home store write made no progress");
            written += count;
          }
        } catch (IOException failure) {
          failed = true;
          throw failure;
        }
      }
    }

    @Override
    public void flush() throws IOException {
      synchronized (HomeStore.this) {
        writable();
        channel.force(false);
      }
    }

    /** Publishes the bytes written so far over the target. Idempotent once committed. */
    public void commit() throws IOException {
      synchronized (HomeStore.this) {
        if (committed) return;
        writable();
        try {
          BasicFileAttributes current = attributes(target);
          if ((previous == null) != (current == null)
              || current != null
                  && (!current.isRegularFile()
                      || !Objects.equals(previous.fileKey(), current.fileKey())
                      || previous.size() != current.size()
                      || !previous.lastModifiedTime().equals(current.lastModifiedTime())))
            throw new IOException("Home file changed while it was being written: " + path.key());
          long currentSize = current == null ? 0 : current.size();
          if (files + (current == null ? 1 : 0) > limits.maxFiles()
              || bytes - currentSize + written > limits.maxTotalBytes())
            throw new IOException("Home store quota exceeded writing " + path.key());
          channel.force(true);
          channel.close();
          replace(temporary, target);
          committed = true;
          ended = true;
          writer = null;
          if (current == null) files++;
          bytes += written - currentSize;
        } catch (IOException failure) {
          failed = true;
          try {
            abort();
          } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
          }
          throw failure;
        }
      }
    }

    /** Discards the write and its temporary file. */
    public void abort() throws IOException {
      synchronized (HomeStore.this) {
        if (ended) return;
        ended = true;
        if (writer == this) writer = null;
        try {
          channel.close();
        } finally {
          Files.deleteIfExists(temporary);
        }
      }
    }

    @Override
    public void close() throws IOException {
      abort();
    }
  }
}
