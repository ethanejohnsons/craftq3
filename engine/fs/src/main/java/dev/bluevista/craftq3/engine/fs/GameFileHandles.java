package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * One VM's file handle table. Handles are opaque positive integers, never host descriptors, and
 * never reused within a table, so a stale handle fails instead of reaching another file. Files are
 * buffered in memory within fixed bounds; reads look in the {@link HomeStore} first and then the
 * read-only files, and writes go to the home store when the handle is closed (or on every write for
 * {@link Mode#APPEND_SYNC}).
 *
 * <p>Thread ownership: a table belongs to the thread that runs its VM and is not synchronized.
 */
public final class GameFileHandles implements AutoCloseable {
  public enum Mode {
    READ,
    WRITE,
    APPEND,
    APPEND_SYNC
  }

  /** Seek origins, in the VM ABI's order. */
  public enum Seek {
    CURRENT,
    END,
    START
  }

  /** An opened handle and the file's length at open. */
  public record Opened(int handle, int length) {}

  /** Most handles open at once in one table. */
  public static final int MAX_HANDLES = 64;

  /** Largest file one handle may buffer. */
  public static final int MAX_FILE = 16 * 1024 * 1024;

  /** Largest total buffered across the table. */
  public static final int MAX_RESIDENT = 64 * 1024 * 1024;

  private final FileSource files;
  private final HomeStore home;
  private final Map<Integer, File> open = new HashMap<>();
  private int sequence;
  private long resident;
  private boolean closed;

  private static final class File {
    final VirtualPath path;
    final Mode mode;
    byte[] bytes;
    int position;
    boolean dirty;

    File(VirtualPath path, Mode mode, byte[] bytes) {
      this.path = path;
      this.mode = mode;
      this.bytes = bytes;
      position = mode == Mode.APPEND || mode == Mode.APPEND_SYNC ? bytes.length : 0;
      dirty = mode == Mode.WRITE;
    }
  }

  /**
   * @param files read-only game files (a {@link FileView} or {@link SearchPath})
   * @param home the writable store, or null for a VM that may not write
   */
  public GameFileHandles(FileSource files, HomeStore home) {
    this.files = files;
    this.home = home;
  }

  public Opened open(VirtualPath path, Mode mode) throws IOException {
    if (closed) throw new IOException("File table closed");
    if (open.size() >= MAX_HANDLES) throw new IOException("Too many open game files");
    if (mode != Mode.READ) {
      if (home == null) throw new IOException("No game write capability");
      HomeStore.checkWritable(path);
    }
    byte[] data = new byte[0];
    if (mode != Mode.WRITE) {
      var saved = home == null ? java.util.Optional.<byte[]>empty() : home.readIfPresent(path);
      if (saved.isPresent()) data = saved.get();
      else if (files.exists(path)) data = files.read(path);
      else if (mode == Mode.READ) throw new NoSuchFileException(path.key());
    }
    if (data.length > MAX_FILE || resident + data.length > MAX_RESIDENT)
      throw new IOException("Game file memory limit exceeded");
    if (sequence == Integer.MAX_VALUE) throw new IOException("Game file handle space exhausted");
    int handle = ++sequence;
    File file = new File(path, mode, data);
    if (mode != Mode.READ && data.length == 0) file.dirty = true;
    open.put(handle, file);
    resident += data.length;
    return new Opened(handle, data.length);
  }

  /** Reads up to {@code length} bytes from the current position. */
  public byte[] read(int handle, int length) throws IOException {
    File file = file(handle);
    if (file.mode != Mode.READ || length < 0 || length > MAX_FILE)
      throw new IOException("Invalid game file read");
    int count = Math.min(length, file.bytes.length - file.position);
    byte[] result = Arrays.copyOfRange(file.bytes, file.position, file.position + count);
    file.position += count;
    return result;
  }

  public int write(int handle, byte[] data) throws IOException {
    File file = file(handle);
    if (file.mode == Mode.READ) throw new IOException("Game file is read-only");
    // Append writes stay at the end after a seek, as an append-mode stream does.
    if (file.mode == Mode.APPEND || file.mode == Mode.APPEND_SYNC)
      file.position = file.bytes.length;
    long required = (long) file.position + data.length;
    if (required > MAX_FILE) throw new IOException("Game file size limit exceeded");
    int size = (int) Math.max(file.bytes.length, required);
    if (resident + size - file.bytes.length > MAX_RESIDENT)
      throw new IOException("Game file memory limit exceeded");
    if (size != file.bytes.length) {
      resident += size - file.bytes.length;
      file.bytes = Arrays.copyOf(file.bytes, size);
    }
    System.arraycopy(data, 0, file.bytes, file.position, data.length);
    file.position += data.length;
    file.dirty = true;
    if (file.mode == Mode.APPEND_SYNC) flush(file);
    return data.length;
  }

  public void seek(int handle, int offset, Seek origin) throws IOException {
    File file = file(handle);
    long next =
        (long) offset
            + switch (origin) {
              case START -> 0;
              case CURRENT -> file.position;
              case END -> file.bytes.length;
            };
    if (next < 0 || next > file.bytes.length) throw new IOException("Game file seek outside data");
    file.position = (int) next;
  }

  public int position(int handle) throws IOException {
    return file(handle).position;
  }

  /** Closes a handle, writing it to the home store if it changed. */
  public void close(int handle) throws IOException {
    File file = file(handle);
    flush(file);
    open.remove(handle);
    resident -= file.bytes.length;
  }

  /** Number of open handles. */
  public int openCount() {
    return open.size();
  }

  private File file(int handle) throws IOException {
    File file = open.get(handle);
    if (closed || file == null) throw new IOException("Invalid game file handle: " + handle);
    return file;
  }

  private void flush(File file) throws IOException {
    if (file.dirty) {
      home.write(file.path, file.bytes);
      file.dirty = false;
    }
  }

  /** Flushes and closes every handle; the first failure is thrown after all were tried. */
  @Override
  public void close() throws IOException {
    if (closed) return;
    IOException failure = null;
    for (File file : open.values())
      try {
        flush(file);
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    open.clear();
    resident = 0;
    closed = true;
    if (failure != null) throw failure;
  }
}
