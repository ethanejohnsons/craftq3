package dev.bluevista.craftq3.engine.fs;

import dev.bluevista.craftq3.engine.common.io.Ranges;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * One PK3 (ZIP32) archive, indexed from its central directory and held open until {@link #close}.
 *
 * <p>Tolerance follows the original engine: an end-of-central-directory comment and data prepended
 * before the archive are accepted, entry names are raw bytes (ISO-8859-1; the UTF-8 flag is
 * ignored) and may use backslashes, and a later duplicate name wins. Unsafe input is refused with
 * the narrowest scope possible: an entry that is encrypted, uses a method other than stored(0) or
 * deflate(8), carries ZIP64 sizes, has an unsafe name or expands past {@link #MAX_FILE_BYTES} is
 * skipped and recorded in {@link #problems()}; only an archive whose directory cannot be located or
 * bounded (empty, truncated, multi-volume, ZIP64, oversized) fails with {@link Pk3FormatException}.
 *
 * <p>The pack checksum covers the CRC of every central-directory entry with a nonzero size,
 * including skipped ones, exactly as the original engine computes it.
 *
 * <p>Thread safety: immutable after {@link #open}; reads use positional channel I/O, so any number
 * of threads may read concurrently. If a reading thread is interrupted the JDK closes the channel;
 * the next read reopens the same file after checking it was not replaced.
 */
public final class Pk3Archive implements AutoCloseable {
  /** Largest archive file accepted. */
  public static final long MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;

  /** Largest central directory read into memory. */
  public static final int MAX_CENTRAL_DIRECTORY_BYTES = 32 * 1024 * 1024;

  /** Largest ZIP32 entry count; 65535 marks a ZIP64 archive. */
  public static final int MAX_ENTRIES = 65_534;

  /** Largest expanded entry for buffered reads and for every non-cinematic entry. */
  public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

  /** Largest streamed {@code .roq} cinematic. */
  public static final long MAX_STREAM_BYTES = 512L * 1024 * 1024;

  /** Largest total expanded size indexed from one archive. */
  public static final long MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024;

  /** Most problems kept per archive for diagnostics; {@link #problemCount()} counts them all. */
  public static final int MAX_RECORDED_PROBLEMS = 16;

  static final int STORED = 0;
  static final int DEFLATED = 8;
  private static final int EOCD_SIGNATURE = 0x06054b50;
  private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
  private static final int CENTRAL_SIGNATURE = 0x02014b50;
  private static final int LOCAL_SIGNATURE = 0x04034b50;
  private static final int EOCD_BYTES = 22;
  private static final int CENTRAL_BYTES = 46;
  private static final int LOCAL_BYTES = 30;
  private static final int MAX_COMMENT = 65535;
  private static final int FLAG_ENCRYPTED = 1;
  private static final int CHUNK = 64 * 1024;

  /**
   * One indexed member. {@code name} is the stored spelling; {@code path} is its normalised path.
   * Sizes are unsigned 32-bit values widened to long.
   */
  public record Entry(
      VirtualPath path,
      String name,
      int method,
      int crc,
      long compressedSize,
      long size,
      long localHeaderOffset) {}

  private final Path file;
  private final String fileName;
  private final long fileSize;
  private final Object fileKey;
  private final long modified;
  private final AtomicReference<FileChannel> channel;
  private final List<Entry> entries;
  private final Map<String, Entry> byKey;
  private final int[] crcs;
  private final int checksum;
  private final List<String> problems;
  private final int problemCount;
  private volatile boolean closed;

  private Pk3Archive(
      Path file,
      BasicFileAttributes attributes,
      FileChannel channel,
      List<Entry> entries,
      Map<String, Entry> byKey,
      int[] crcs,
      List<String> problems,
      int problemCount) {
    this.file = file;
    this.fileName = file.getFileName().toString();
    this.fileSize = attributes.size();
    this.fileKey = attributes.fileKey();
    this.modified = attributes.lastModifiedTime().toMillis();
    this.channel = new AtomicReference<>(channel);
    this.entries = entries;
    this.byKey = byKey;
    this.crcs = crcs;
    this.checksum = Pk3Checksums.normal(crcs);
    this.problems = problems;
    this.problemCount = problemCount;
  }

  /**
   * Opens and indexes {@code file}.
   *
   * @throws Pk3FormatException if the archive cannot be indexed safely
   * @throws IOException if the file cannot be read
   */
  public static Pk3Archive open(Path file) throws IOException {
    Path real = file.toRealPath();
    BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
    if (!attributes.isRegularFile()) throw new Pk3FormatException("Not a regular file: " + file);
    FileChannel opened = FileChannel.open(real, StandardOpenOption.READ);
    try {
      return index(real, attributes, opened);
    } catch (IOException | RuntimeException failure) {
      try {
        opened.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  private static Pk3Archive index(Path file, BasicFileAttributes attributes, FileChannel channel)
      throws IOException {
    long size = channel.size();
    if (size == 0) throw new Pk3FormatException("Empty PK3: " + file);
    if (size < EOCD_BYTES) throw new Pk3FormatException("Truncated PK3 (" + size + " bytes)");
    if (size > MAX_ARCHIVE_BYTES) throw new Pk3FormatException("PK3 exceeds 2 GiB: " + file);

    int tailSize = (int) Math.min(size, EOCD_BYTES + MAX_COMMENT);
    long tailStart = size - tailSize;
    ByteBuffer tail = readAt(channel, tailStart, tailSize);
    // The record whose comment length reaches exactly to the end of the file is authoritative;
    // a signature inside a comment or trailing junk is only used when no exact record exists.
    int at = -1;
    for (int i = tailSize - EOCD_BYTES; i >= 0; i--) {
      if (tail.getInt(i) != EOCD_SIGNATURE) continue;
      boolean exact = i + EOCD_BYTES + u16(tail, i + 20) == tailSize;
      String problem = endRecordProblem(tail, i, tailStart + i);
      if (problem == null) {
        if (at < 0 || exact) at = i;
        if (exact) break;
      } else if (exact) throw new Pk3FormatException(problem + ": " + file);
    }
    if (at < 0) throw new Pk3FormatException("No usable end-of-central-directory record: " + file);
    long eocd = tailStart + at;
    int count = u16(tail, at + 10);
    long directorySize = u32(tail, at + 12), directoryOffset = u32(tail, at + 16);
    // Anything before the archive proper (a self-extractor stub) shifts every offset.
    long bias = eocd - (directoryOffset + directorySize);
    if (eocd >= 20 && readAt(channel, eocd - 20, 4).getInt(0) == ZIP64_LOCATOR_SIGNATURE)
      throw new Pk3FormatException("ZIP64 PK3 is unsupported: " + file);
    if (directorySize > MAX_CENTRAL_DIRECTORY_BYTES)
      throw new Pk3FormatException("PK3 central directory exceeds 32 MiB: " + file);
    if (count > MAX_ENTRIES) throw new Pk3FormatException("PK3 has too many entries: " + file);
    if (directorySize < (long) count * CENTRAL_BYTES)
      throw new Pk3FormatException("PK3 central directory is too small for its entries: " + file);

    long directoryStart = directoryOffset + bias;
    ByteBuffer directory = readAt(channel, directoryStart, (int) directorySize);
    var entries = new ArrayList<Entry>(count);
    var byKey = new HashMap<String, Entry>(count * 2);
    int[] crcs = new int[count];
    int crcCount = 0;
    var problems = new ArrayList<String>();
    int problemCount = 0;
    long expanded = 0;
    int position = 0;
    for (int n = 0; n < count; n++) {
      if (!Ranges.contains(directorySize, position, CENTRAL_BYTES)
          || directory.getInt(position) != CENTRAL_SIGNATURE) {
        // The original engine stops at the first unreadable record and keeps the rest.
        problemCount = problem(problems, problemCount, "central directory ends at entry " + n);
        break;
      }
      int flags = u16(directory, position + 8);
      int method = u16(directory, position + 10);
      int crc = directory.getInt(position + 16);
      long compressed = u32(directory, position + 20);
      long length = u32(directory, position + 24);
      int nameLength = u16(directory, position + 28);
      int extraLength = u16(directory, position + 30);
      int commentLength = u16(directory, position + 32);
      long local = u32(directory, position + 42);
      int recordLength = CENTRAL_BYTES + nameLength + extraLength + commentLength;
      if (!Ranges.contains(directorySize, position, recordLength)) {
        problemCount = problem(problems, problemCount, "central directory ends at entry " + n);
        break;
      }
      byte[] nameBytes = new byte[nameLength];
      directory.get(position + CENTRAL_BYTES, nameBytes);
      String name = new String(nameBytes, StandardCharsets.ISO_8859_1);
      position += recordLength;
      if (length > 0) crcs[crcCount++] = crc;

      if (name.endsWith("/") || name.endsWith("\\")) continue;
      String reason = null;
      VirtualPath path = null;
      if ((flags & FLAG_ENCRYPTED) != 0) reason = "encrypted";
      else if (compressed == 0xffffffffL || length == 0xffffffffL || local == 0xffffffffL)
        reason = "ZIP64 sizes";
      else if (method != STORED && method != DEFLATED) reason = "compression method " + method;
      else if (method == STORED && compressed != length) reason = "stored size mismatch";
      else if (local + bias > directoryStart - LOCAL_BYTES) reason = "local header out of range";
      else {
        path = VirtualPath.parse(name).orElse(null);
        if (path == null) reason = "unsafe name";
        else if (length > limit(path)) reason = "expands to " + length + " bytes";
        else if (expanded + length > MAX_EXPANDED_BYTES) reason = "archive expands past 2 GiB";
      }
      if (reason != null) {
        problemCount = problem(problems, problemCount, name + ": " + reason);
        continue;
      }
      expanded += length;
      Entry entry = new Entry(path, name, method, crc, compressed, length, local + bias);
      Entry previous = byKey.put(path.key(), entry);
      if (previous != null) {
        entries.remove(previous);
        problemCount = problem(problems, problemCount, name + ": duplicate name, later wins");
      }
      entries.add(entry);
    }
    return new Pk3Archive(
        file,
        attributes,
        channel,
        Collections.unmodifiableList(entries),
        byKey,
        java.util.Arrays.copyOf(crcs, crcCount),
        List.copyOf(problems),
        problemCount);
  }

  /** Why the end record at {@code i} cannot describe this archive, or null when it can. */
  private static String endRecordProblem(ByteBuffer tail, int i, long eocd) {
    int disk = u16(tail, i + 4), directoryDisk = u16(tail, i + 6);
    int diskEntries = u16(tail, i + 8), total = u16(tail, i + 10);
    long bytes = u32(tail, i + 12), offset = u32(tail, i + 16);
    if (total == 0xffff || bytes == 0xffffffffL || offset == 0xffffffffL)
      return "ZIP64 PK3 is unsupported";
    if (disk != 0 || directoryDisk != 0 || diskEntries != total)
      return "Multi-volume PK3 is unsupported";
    if (offset + bytes > eocd) return "PK3 central directory reaches past its end record";
    return null;
  }

  private static int problem(List<String> problems, int count, String problem) {
    if (problems.size() < MAX_RECORDED_PROBLEMS) problems.add(problem);
    return count + 1;
  }

  /** The expansion limit for an entry: cinematics stream up to 512 MiB, all else 64 MiB. */
  static long limit(VirtualPath path) {
    return path.extension().equals("roq") ? MAX_STREAM_BYTES : MAX_FILE_BYTES;
  }

  /** The archive's real path. */
  public Path file() {
    return file;
  }

  /** The archive's file name, such as {@code pak0.pk3}. */
  public String fileName() {
    return fileName;
  }

  /** Archive size in bytes. */
  public long fileSize() {
    return fileSize;
  }

  /** Indexed entries in central-directory order, without directories or skipped entries. */
  public List<Entry> entries() {
    return entries;
  }

  public Optional<Entry> find(VirtualPath path) {
    return Optional.ofNullable(byKey.get(path.key()));
  }

  /** The CRCs that enter the pack checksum, in central-directory order. */
  public int[] checksumCrcs() {
    return crcs.clone();
  }

  /** The original engine's pack checksum ({@link Pk3Checksums#normal}). */
  public int checksum() {
    return checksum;
  }

  /** Up to {@link #MAX_RECORDED_PROBLEMS} skipped-entry and duplicate descriptions. */
  public List<String> problems() {
    return problems;
  }

  public int problemCount() {
    return problemCount;
  }

  /**
   * Reads and CRC-checks one entry.
   *
   * @throws IOException if the entry is corrupt, larger than {@link #MAX_FILE_BYTES} or the archive
   *     is closed
   */
  public byte[] read(Entry entry) throws IOException {
    if (entry.size() > MAX_FILE_BYTES)
      throw new IOException(
          "PK3 entry exceeds the 64 MiB buffered-read limit; stream it: " + entry.name());
    try (InputStream input = open(entry)) {
      byte[] bytes = input.readNBytes((int) entry.size());
      if (input.read() != -1) throw new IOException("PK3 entry size mismatch: " + entry.name());
      return bytes;
    }
  }

  /** Streams one entry; size and CRC are checked when the stream reaches its end. */
  public InputStream open(Entry entry) throws IOException {
    Objects.requireNonNull(entry);
    if (byKey.get(entry.path().key()) != entry)
      throw new IOException("Entry is not indexed in " + fileName + ": " + entry.name());
    ByteBuffer header = readAt(entry.localHeaderOffset(), LOCAL_BYTES);
    if (header.getInt(0) != LOCAL_SIGNATURE)
      throw new IOException("Bad PK3 local header for " + entry.name() + " in " + fileName);
    long data = entry.localHeaderOffset() + LOCAL_BYTES + u16(header, 26) + u16(header, 28);
    if (!Ranges.contains(fileSize, data, entry.compressedSize()))
      throw new IOException("PK3 entry data out of range: " + entry.name() + " in " + fileName);
    String what = fileName + ":" + entry.name();
    if (entry.method() == STORED)
      return new VerifiedStream(
          new Slice(data, entry.compressedSize(), false), entry.size(), entry.crc(), what);
    Inflater inflater = new Inflater(true);
    InputStream inflating =
        new InflaterInputStream(new Slice(data, entry.compressedSize(), true), inflater, 8192) {
          private boolean ended;

          @Override
          public void close() throws IOException {
            if (!ended) {
              ended = true;
              inflater.end();
            }
            super.close();
          }
        };
    return new VerifiedStream(inflating, entry.size(), entry.crc(), what);
  }

  /** Streams the whole archive file from the held channel, for serving downloads. */
  public InputStream openFile() throws IOException {
    checkOpen();
    return new Slice(0, fileSize, false);
  }

  private ByteBuffer readAt(long position, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    readFully(buffer, position);
    return buffer;
  }

  private static ByteBuffer readAt(FileChannel channel, long position, int length)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position + buffer.position());
      if (read < 0) throw new Pk3FormatException("Truncated PK3 at offset " + position);
      if (read == 0 && position + buffer.position() >= channel.size())
        throw new Pk3FormatException("Truncated PK3 at offset " + position);
    }
    return buffer;
  }

  private void readFully(ByteBuffer buffer, long position) throws IOException {
    boolean retried = false;
    int start = buffer.position();
    while (buffer.hasRemaining()) {
      FileChannel current = channel();
      try {
        int read = current.read(buffer, position + (buffer.position() - start));
        if (read < 0) throw new IOException("PK3 truncated since mount: " + fileName);
      } catch (ClosedByInterruptException interrupted) {
        throw interrupted;
      } catch (ClosedChannelException closedByOtherThread) {
        // Another reader was interrupted and the JDK closed the shared channel; reopen once.
        if (closed || retried) throw closedByOtherThread;
        retried = true;
      }
    }
  }

  private FileChannel channel() throws IOException {
    FileChannel current = channel.get();
    if (current.isOpen()) return current;
    checkOpen();
    FileChannel reopened = FileChannel.open(file, StandardOpenOption.READ);
    try {
      BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class);
      if (now.size() != fileSize
          || now.lastModifiedTime().toMillis() != modified
          || !Objects.equals(now.fileKey(), fileKey))
        throw new IOException("PK3 changed on disk since mount: " + file);
    } catch (IOException | RuntimeException failure) {
      reopened.close();
      throw failure;
    }
    if (!channel.compareAndSet(current, reopened)) {
      reopened.close();
      return channel.get();
    }
    if (closed) {
      reopened.close();
      checkOpen();
    }
    return reopened;
  }

  private void checkOpen() throws IOException {
    if (closed) throw new ClosedChannelException();
  }

  private static int u16(ByteBuffer buffer, int at) {
    return Short.toUnsignedInt(buffer.getShort(at));
  }

  private static long u32(ByteBuffer buffer, int at) {
    return Integer.toUnsignedLong(buffer.getInt(at));
  }

  @Override
  public void close() throws IOException {
    closed = true;
    channel.get().close();
  }

  @Override
  public String toString() {
    return file.toString();
  }

  /** A bounded window of the archive read with positional I/O. */
  private final class Slice extends InputStream {
    private final long end;
    private long position;
    private boolean padding;

    /** {@code pad} appends one zero byte, which a raw inflater may need to finish. */
    Slice(long start, long length, boolean pad) {
      this.position = start;
      this.end = start + length;
      this.padding = pad;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) return 0;
      if (position >= end) {
        if (!padding) return -1;
        padding = false;
        bytes[offset] = 0;
        return 1;
      }
      int count = (int) Math.min(Math.min(length, CHUNK), end - position);
      ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, count);
      readFully(buffer, position);
      position += count;
      return count;
    }
  }
}
