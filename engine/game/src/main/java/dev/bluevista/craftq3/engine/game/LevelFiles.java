package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.vmabi.service.VmFiles;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The server's {@link VmFiles} as one game module instance sees it: every handle the module opens
 * is remembered, so ending the instance ({@code SV_ShutdownGameProgs}, a {@code map_restart})
 * closes exactly those handles ({@link #closeOpen}) while the underlying files stay usable for the
 * next instance, as the original engine's {@code FS_FCloseFile} of the VM's handles does. Only
 * {@link #closeAll} (the server closing) retires the underlying table. Owned by the simulation
 * thread.
 */
final class LevelFiles implements VmFiles {
  private final VmFiles files;
  private int[] open = new int[8];
  private int count;

  LevelFiles(VmFiles files) {
    this.files = Objects.requireNonNull(files);
  }

  @Override
  public int length(String path) throws IOException {
    return files.length(path);
  }

  @Override
  public Opened open(String path, Mode mode) throws IOException {
    Opened opened = files.open(path, mode);
    if (count == open.length) open = Arrays.copyOf(open, count * 2);
    open[count++] = opened.handle();
    return opened;
  }

  @Override
  public byte[] read(int handle, int length) throws IOException {
    return files.read(handle, length);
  }

  @Override
  public int write(int handle, byte[] data) throws IOException {
    return files.write(handle, data);
  }

  @Override
  public boolean seek(int handle, int offset, Origin origin) throws IOException {
    return files.seek(handle, offset, origin);
  }

  @Override
  public void close(int handle) throws IOException {
    files.close(handle);
    forget(handle);
  }

  private void forget(int handle) {
    for (int i = 0; i < count; i++)
      if (open[i] == handle) {
        open[i] = open[--count];
        return;
      }
  }

  @Override
  public List<String> list(String directory, String extension) throws IOException {
    return files.list(directory, extension);
  }

  @Override
  public List<ModInfo> mods() throws IOException {
    return files.mods();
  }

  /** Handles the current module instance still holds. */
  int openCount() {
    return count;
  }

  /**
   * Closes (and flushes) every handle the module instance left open; the first failure is thrown
   * after all were tried.
   */
  void closeOpen() throws IOException {
    IOException failure = null;
    while (count > 0) {
      int handle = open[--count];
      try {
        files.close(handle);
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    }
    if (failure != null) throw failure;
  }

  /** The server is closing: closes what is open and retires the underlying files. */
  @Override
  public void closeAll() throws IOException {
    try {
      closeOpen();
    } finally {
      files.closeAll();
    }
  }
}
