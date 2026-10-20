package dev.bluevista.craftq3.core.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Host-only, descriptor-pinned streaming demo storage. Its separate flat root must not be shared
 * with VM GameFileStore or unrelated writers. No directory creation follows a requested path.
 * Active recordings use one adjacent temporary file; commit atomically replaces the final name.
 * Closing without commit aborts. A process crash may leave a quota-counted temporary for manual
 * recovery; this is not a power-loss durability guarantee. No native storage compatibility is
 * claimed.
 */
public final class DemoFileStore implements AutoCloseable {
  public record Limits(long maxFileBytes, long maxTotalBytes, int maxFiles) {
    public Limits {
      if (maxFileBytes < 1
          || maxFileBytes > 512L * 1024 * 1024
          || maxTotalBytes < maxFileBytes
          || maxTotalBytes > 2L * 1024 * 1024 * 1024
          || maxFiles < 1
          || maxFiles > 128) throw new IllegalArgumentException("Invalid demo quotas");
    }
  }

  public static final Limits DEFAULT_LIMITS =
      new Limits(512L * 1024 * 1024, 2L * 1024 * 1024 * 1024, 128);

  private record Usage(long bytes, int files) {}

  private final SecureDirectoryStream<Path> root;
  private final Limits limits;
  private final Set<ReadStream> readers = new HashSet<>();
  private AtomicOutputStream writer;
  private boolean closed;

  public DemoFileStore(Path directory) throws IOException {
    this(directory, DEFAULT_LIMITS);
  }

  public DemoFileStore(Path directory, Limits limits) throws IOException {
    this.limits = Objects.requireNonNull(limits);
    Files.createDirectories(directory);
    DirectoryStream<Path> opened = Files.newDirectoryStream(directory.toRealPath());
    if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
      opened.close();
      throw new IOException("Demo storage requires secure directory streams");
    }
    root = secure;
    try {
      scan(null);
    } catch (IOException | RuntimeException failure) {
      try {
        root.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  public Limits limits() {
    return limits;
  }

  /** Bounded sorted canonical demo names; excludes pending temporary files. */
  public synchronized List<VirtualPath> list() throws IOException {
    checkOpen();
    var result = new ArrayList<VirtualPath>();
    scan(result);
    return List.copyOf(result);
  }

  /**
   * At most sixteen owned readers; each reads only the length observed on its pinned descriptor.
   */
  public synchronized Optional<InputStream> openRead(VirtualPath path) throws IOException {
    checkOpen();
    Path name = name(path);
    if (readers.size() == 16) throw new IOException("Too many open demo readers");
    try {
      BasicFileAttributes attributes = attributes(name);
      if (!attributes.isRegularFile() || attributes.size() > limits.maxFileBytes())
        throw new IOException("Invalid demo file");
      var channel =
          root.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
      try {
        long length = channel.size();
        if (length > limits.maxFileBytes()) throw new IOException("Demo exceeds file quota");
        var stream = new ReadStream(channel, length);
        readers.add(stream);
        return Optional.of(stream);
      } catch (IOException | RuntimeException failure) {
        try {
          channel.close();
        } catch (IOException close) {
          failure.addSuppressed(close);
        }
        throw failure;
      }
    } catch (NoSuchFileException missing) {
      return Optional.empty();
    }
  }

  /** One active recording per store. Quota is checked before creation and before every write. */
  public synchronized AtomicOutputStream openAtomicWrite(VirtualPath path) throws IOException {
    checkOpen();
    Path target = name(path);
    if (writer != null) throw new IOException("A demo recording is already open");
    BasicFileAttributes previous = existing(target);
    if (previous != null && !previous.isRegularFile())
      throw new IOException("Refusing non-file demo target");
    Usage usage = scan(null);
    if (usage.files + (previous == null ? 1 : 0) > limits.maxFiles())
      throw new IOException("Demo file quota exceeded");
    long allowance =
        Math.min(
            limits.maxFileBytes(),
            limits.maxTotalBytes() - usage.bytes + (previous == null ? 0 : previous.size()));
    Path temporary = Path.of(".craftq3-demo-" + UUID.randomUUID() + ".tmp");
    var channel =
        root.newByteChannel(
            temporary,
            Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS));
    writer = new AtomicOutputStream(target, temporary, previous, channel, allowance);
    return writer;
  }

  private static Path name(VirtualPath path) throws IOException {
    Objects.requireNonNull(path);
    String name = path.value();
    if (name.indexOf('/') >= 0
        || name.length() > 255
        || !name.endsWith(".dm_68")
        || name.equals(".dm_68"))
      throw new IOException("Expected a flat protocol-68 demo filename");
    return Path.of(name);
  }

  private BasicFileAttributes attributes(Path path) throws IOException {
    return root.getFileAttributeView(path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
        .readAttributes();
  }

  private BasicFileAttributes existing(Path path) throws IOException {
    try {
      return attributes(path);
    } catch (NoSuchFileException missing) {
      return null;
    }
  }

  private Usage scan(List<VirtualPath> names) throws IOException {
    long bytes = 0;
    int files = 0;
    try (var directory = root.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS)) {
      for (Path entry : directory) {
        Path path = entry.getFileName();
        if (writer != null && path.equals(writer.temporary)) continue;
        BasicFileAttributes attributes = attributes(path);
        if (!attributes.isRegularFile() || attributes.size() > limits.maxFileBytes())
          throw new IOException("Invalid file in demo root");
        bytes += attributes.size();
        if (++files > limits.maxFiles() || bytes > limits.maxTotalBytes())
          throw new IOException("Demo root quota exceeded");
        String text = path.toString();
        if (names != null && text.endsWith(".dm_68") && !text.equals(".dm_68")) {
          VirtualPath virtual = new VirtualPath(text);
          if (virtual.value().equals(text)) names.add(virtual);
        }
      }
    }
    if (names != null) names.sort(VirtualPath::compareTo);
    return new Usage(bytes, files);
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("Demo store closed");
  }

  private final class ReadStream extends InputStream {
    private final SeekableByteChannel channel;
    private final long length;
    private boolean ended;

    ReadStream(SeekableByteChannel channel, long length) {
      this.channel = channel;
      this.length = length;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int count) throws IOException {
      Objects.checkFromIndexSize(offset, count, bytes.length);
      synchronized (DemoFileStore.this) {
        checkOpen();
        if (ended) throw new IOException("Demo reader closed");
        if (count == 0) return 0;
        long remaining = length - channel.position();
        if (remaining <= 0) return -1;
        int read = channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(count, remaining)));
        if (read == 0) throw new IOException("Demo read made no progress");
        return read;
      }
    }

    @Override
    public void close() throws IOException {
      synchronized (DemoFileStore.this) {
        if (!ended) {
          ended = true;
          readers.remove(this);
          channel.close();
        }
      }
    }
  }

  /**
   * Explicit commit after the recorder successfully writes its end marker; close otherwise aborts.
   */
  public final class AtomicOutputStream extends OutputStream {
    private final Path target, temporary;
    private final BasicFileAttributes previous;
    private final SeekableByteChannel channel;
    private final long allowance;
    private long bytes;
    private boolean ended, failed, committed;

    private AtomicOutputStream(
        Path target,
        Path temporary,
        BasicFileAttributes previous,
        SeekableByteChannel channel,
        long allowance) {
      this.target = target;
      this.temporary = temporary;
      this.previous = previous;
      this.channel = channel;
      this.allowance = allowance;
    }

    public boolean committed() {
      synchronized (DemoFileStore.this) {
        return committed;
      }
    }

    public long byteLimit() {
      return allowance;
    }

    public long bytesWritten() {
      synchronized (DemoFileStore.this) {
        return bytes;
      }
    }

    private void writable() throws IOException {
      checkOpen();
      if (ended || failed) throw new IOException("Demo writer closed or failed");
    }

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, data.length);
      synchronized (DemoFileStore.this) {
        writable();
        try {
          if (length > allowance - bytes) throw new IOException("Demo recording quota exceeded");
          ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
          while (buffer.hasRemaining()) {
            int wrote = channel.write(buffer);
            if (wrote <= 0) throw new IOException("Demo write made no progress");
            bytes += wrote;
          }
        } catch (IOException failure) {
          failed = true;
          throw failure;
        }
      }
    }

    @Override
    public void flush() throws IOException {
      synchronized (DemoFileStore.this) {
        writable();
        try {
          if (channel instanceof FileChannel file) file.force(false);
        } catch (IOException failure) {
          failed = true;
          throw failure;
        }
      }
    }

    public void commit() throws IOException {
      synchronized (DemoFileStore.this) {
        if (committed) return;
        writable();
        try {
          BasicFileAttributes current = existing(target);
          if ((previous == null) != (current == null)
              || (current != null
                  && (!current.isRegularFile()
                      || !Objects.equals(previous.fileKey(), current.fileKey())
                      || previous.size() != current.size()
                      || !previous.lastModifiedTime().equals(current.lastModifiedTime()))))
            throw new IOException("Demo target changed while recording");
          Usage usage = scan(null);
          if (usage.files + (current == null ? 1 : 0) > limits.maxFiles()
              || usage.bytes - (current == null ? 0 : current.size()) + bytes
                  > limits.maxTotalBytes()) throw new IOException("Demo root quota exceeded");
          if (channel instanceof FileChannel file) file.force(true);
          channel.close();
          root.move(temporary, root, target);
          committed = true;
          ended = true;
          writer = null;
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

    public void abort() throws IOException {
      synchronized (DemoFileStore.this) {
        if (ended) return;
        ended = true;
        IOException failure = null;
        try {
          channel.close();
        } catch (IOException close) {
          failure = close;
        }
        try {
          root.deleteFile(temporary);
        } catch (IOException remove) {
          if (failure == null) failure = remove;
          else failure.addSuppressed(remove);
        }
        writer = null;
        if (failure != null) throw failure;
      }
    }

    @Override
    public void close() throws IOException {
      abort();
    }
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
    for (ReadStream reader : List.copyOf(readers))
      try {
        reader.close();
      } catch (IOException problem) {
        if (failure == null) failure = problem;
        else failure.addSuppressed(problem);
      }
    closed = true;
    try {
      root.close();
    } catch (IOException problem) {
      if (failure == null) failure = problem;
      else failure.addSuppressed(problem);
    }
    if (failure != null) throw failure;
  }
}
