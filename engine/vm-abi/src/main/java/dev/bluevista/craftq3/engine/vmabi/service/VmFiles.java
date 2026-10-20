package dev.bluevista.craftq3.engine.vmabi.service;

import java.io.IOException;
import java.util.List;

/**
 * The file-handle services one VM sees ({@code trap_FS_*}). {@link FsVmFiles} implements it over
 * {@code engine.fs}; tests and hosts without files can stub it. Paths are the guest's spelling;
 * implementations validate them. A missing or refused file is an {@link IOException} (the trap then
 * returns its "not found" value), never a degradation.
 *
 * <p>Owned by the thread that runs the VM.
 */
public interface VmFiles {
  /** {@code fsMode_t}, in ABI order. */
  enum Mode {
    READ,
    WRITE,
    APPEND,
    APPEND_SYNC
  }

  /**
   * {@code fsOrigin_t}, in ABI order ({@code FS_SEEK_CUR}, {@code FS_SEEK_END}, {@code
   * FS_SEEK_SET}).
   */
  enum Origin {
    CURRENT,
    END,
    START
  }

  /** An opened file: a positive guest handle and the length at open (0 for new writes). */
  record Opened(int handle, int length) {}

  /** A {@code $modlist} entry: the game directory and its description. */
  record ModInfo(String directory, String description) {}

  /** Size of a readable file, or -1 when it does not exist. */
  int length(String path) throws IOException;

  /**
   * Opens a file.
   *
   * @throws IOException when the file is missing, the path is refused or the mode is not allowed
   */
  Opened open(String path, Mode mode) throws IOException;

  /** Reads up to {@code length} bytes from the current position. */
  byte[] read(int handle, int length) throws IOException;

  /** Writes all of {@code data}; returns the count written. */
  int write(int handle, byte[] data) throws IOException;

  /** Moves the position; false when the target lies outside the file. */
  boolean seek(int handle, int offset, Origin origin) throws IOException;

  void close(int handle) throws IOException;

  /**
   * Names directly inside {@code directory} ending in {@code extension} (case-insensitive), or the
   * subdirectory names when {@code extension} is {@code "/"}; in search order, without duplicates.
   */
  List<String> list(String directory, String extension) throws IOException;

  /** Game directories for the ui's {@code $modlist} query; none by default. */
  default List<ModInfo> mods() throws IOException {
    return List.of();
  }

  /** Closes every handle (the VM is shutting down). */
  default void closeAll() throws IOException {}
}
