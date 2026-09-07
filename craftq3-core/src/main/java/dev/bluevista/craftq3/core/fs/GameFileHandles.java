package dev.bluevista.craftq3.core.fs;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Session-owned opaque file handles. No VM can supply a host descriptor or unbounded allocation.
 */
public final class GameFileHandles implements AutoCloseable {
  public enum Mode {
    READ,
    WRITE,
    APPEND,
    APPEND_SYNC
  }

  public enum Seek {
    CURRENT,
    END,
    START
  }

  public record Opened(int handle, int length) {}

  private static final int MAX_HANDLES = 64,
      MAX_FILE = 16 * 1024 * 1024,
      MAX_RESIDENT = 64 * 1024 * 1024;
  private final VirtualFileSystem fs;
  private final WritableFiles writes;
  private final Map<Integer, File> files = new HashMap<>();
  private int sequence, resident;
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

  public GameFileHandles(VirtualFileSystem fs, WritableFiles writes) {
    this.fs = fs;
    this.writes = writes;
  }

  public Opened open(VirtualPath path, Mode mode) throws IOException {
    if (closed) throw new IOException("File table closed");
    if (files.size() >= MAX_HANDLES) throw new IOException("Too many open game files");
    byte[] data = new byte[0];
    if (mode != Mode.WRITE) {
      Optional<byte[]> saved = writes == null ? Optional.empty() : writes.read(path);
      if (saved.isPresent()) data = saved.orElseThrow();
      else if (fs.which(path).isPresent()) data = fs.read(path);
      else if (mode == Mode.READ) throw new NoSuchFileException(path.value());
    }
    if (mode != Mode.READ && writes == null) throw new IOException("No game write capability");
    if (data.length > MAX_FILE || (long) resident + data.length > MAX_RESIDENT)
      throw new IOException("Game file memory limit exceeded");
    if (sequence == Integer.MAX_VALUE) throw new IOException("Game file handle space exhausted");
    int handle = ++sequence;
    File file = new File(path, mode, data);
    if (mode != Mode.READ && data.length == 0) file.dirty = true;
    files.put(handle, file);
    resident += data.length;
    return new Opened(handle, data.length);
  }

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
    // Append-mode writes remain at EOF after a seek, matching a Q3 append file's stream semantics.
    if (file.mode == Mode.APPEND || file.mode == Mode.APPEND_SYNC)
      file.position = file.bytes.length;
    long required = (long) file.position + data.length;
    if (required > MAX_FILE) throw new IOException("Game file size limit exceeded");
    int size = (int) Math.max(file.bytes.length, required);
    if ((long) resident + size - file.bytes.length > MAX_RESIDENT)
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

  public void close(int handle) throws IOException {
    File file = file(handle);
    flush(file);
    files.remove(handle);
    resident -= file.bytes.length;
  }

  private File file(int handle) throws IOException {
    File file = files.get(handle);
    if (closed || file == null) throw new IOException("Invalid game file handle: " + handle);
    return file;
  }

  private void flush(File file) throws IOException {
    if (file.dirty) {
      writes.write(file.path, file.bytes);
      file.dirty = false;
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    IOException failure = null;
    for (File file : files.values())
      try {
        flush(file);
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    files.clear();
    resident = 0;
    closed = true;
    if (failure != null) throw failure;
  }
}
