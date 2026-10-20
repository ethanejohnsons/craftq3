package dev.bluevista.craftq3.engine.formats;

import java.util.List;
import java.util.Optional;

/**
 * The read-only file view the format layer needs to resolve names (shader scripts, image fallbacks,
 * skins). The filesystem module adapts its search path to this interface; tests use an in-memory
 * map. Paths are Quake III paths: relative, {@code /}-separated and compared case-insensitively.
 */
public interface AssetLookup {
  /** The highest-priority file named {@code path}, or empty if no search path entry has it. */
  Optional<byte[]> read(String path);

  /**
   * Full paths ({@code directory/name.extension}) of the files directly inside {@code directory}
   * whose extension is {@code extension} (without the dot), like the original {@code FS_ListFiles}:
   * each name once, in search order (highest-priority source first, then each source's own entry
   * order).
   */
  List<String> list(String directory, String extension);

  /** True when {@link #read} would find {@code path}. Implementations may answer more cheaply. */
  default boolean exists(String path) {
    return read(path).isPresent();
  }
}
