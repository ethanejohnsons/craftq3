package dev.bluevista.craftq3.engine.formats;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapts a filesystem {@link FileView} (plain or pure-restricted) to {@link AssetLookup}. Names the
 * filesystem rejects as unsafe and files that fail to read are reported as absent, as the original
 * engine treats unreadable files.
 */
public final class FileViewAssets implements AssetLookup {
  private static final Log LOG = Log.get(FileViewAssets.class);

  private final FileView view;

  public FileViewAssets(FileView view) {
    this.view = Objects.requireNonNull(view);
  }

  @Override
  public Optional<byte[]> read(String path) {
    Optional<VirtualPath> parsed = parse(path);
    if (parsed.isEmpty() || !view.exists(parsed.get())) return Optional.empty();
    try {
      return Optional.of(view.read(parsed.get()));
    } catch (NoSuchFileException missing) {
      return Optional.empty();
    } catch (IOException e) {
      LOG.warnOnce("read:" + path, "Cannot read " + path + ": " + e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public List<String> list(String directory, String extension) {
    // The filesystem lists names relative to the directory, as FS_ListFiles does.
    String prefix = directory.replace('\\', '/');
    while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
    String base = prefix.isEmpty() ? "" : prefix + "/";
    return view.list(directory, extension).stream().map(name -> base + name).toList();
  }

  @Override
  public boolean exists(String path) {
    return parse(path).map(view::exists).orElse(false);
  }

  private static Optional<VirtualPath> parse(String path) {
    try {
      return Optional.of(VirtualPath.of(path));
    } catch (IllegalArgumentException unsafe) {
      return Optional.empty();
    }
  }
}
