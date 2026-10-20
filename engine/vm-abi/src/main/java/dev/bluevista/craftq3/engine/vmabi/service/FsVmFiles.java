package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.GameFileHandles;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link VmFiles} over {@code engine.fs}: a {@link GameFileHandles} table reading the home store
 * first and then a {@link FileView} (writes go to the home store), listings that merge the home
 * store and the view, and an optional {@link ModList} for {@code $modlist}. A guest path that
 * {@link VirtualPath} refuses (absolute, {@code ..}, drive letters) behaves as a missing file.
 */
public final class FsVmFiles implements VmFiles {
  private final FileView view;
  private final HomeStore home;
  private final ModList mods;
  private final GameFileHandles handles;

  /**
   * @param home the writable store, or null for a VM that may not write
   * @param mods the {@code $modlist} source, or null to list no mods
   */
  public FsVmFiles(FileView view, HomeStore home, ModList mods) {
    this.view = Objects.requireNonNull(view);
    this.home = home;
    this.mods = mods;
    this.handles = new GameFileHandles(view, home);
  }

  /**
   * The usual wiring: the search path's unrestricted view, the home store (null for read-only VMs)
   * and, when {@code installRoot} is given, {@code $modlist} over its game directories. A host
   * whose home path also holds game directories builds a {@link ModList} with both roots.
   */
  public static FsVmFiles of(SearchPath searchPath, HomeStore home, Path installRoot) {
    return new FsVmFiles(
        searchPath.view(), home, installRoot == null ? null : new ModList(List.of(installRoot)));
  }

  /** {@code FS_FOpenFileRead}: "qpaths are not supposed to have a leading slash" (one is cut). */
  private static String qpath(String path) {
    return path != null && !path.isEmpty() && (path.charAt(0) == '/' || path.charAt(0) == '\\')
        ? path.substring(1)
        : path;
  }

  private static VirtualPath path(String path) throws NoSuchFileException {
    Optional<VirtualPath> parsed = VirtualPath.parse(qpath(path));
    if (parsed.isEmpty()) throw new NoSuchFileException(path, null, "refused path");
    return parsed.get();
  }

  @Override
  public int length(String path) throws IOException {
    Optional<VirtualPath> parsed = VirtualPath.parse(qpath(path));
    if (parsed.isEmpty()) return -1;
    if (home != null) {
      Optional<byte[]> saved = home.readIfPresent(parsed.get());
      if (saved.isPresent()) return saved.get().length;
    }
    long size = view.size(parsed.get());
    return size < 0 ? -1 : (int) Math.min(size, Integer.MAX_VALUE);
  }

  @Override
  public Opened open(String path, Mode mode) throws IOException {
    GameFileHandles.Opened opened =
        handles.open(path(path), GameFileHandles.Mode.values()[mode.ordinal()]);
    return new Opened(opened.handle(), opened.length());
  }

  @Override
  public byte[] read(int handle, int length) throws IOException {
    return handles.read(handle, length);
  }

  @Override
  public int write(int handle, byte[] data) throws IOException {
    return handles.write(handle, data);
  }

  @Override
  public boolean seek(int handle, int offset, Origin origin) throws IOException {
    GameFileHandles.Seek seek =
        switch (origin) {
          case CURRENT -> GameFileHandles.Seek.CURRENT;
          case END -> GameFileHandles.Seek.END;
          case START -> GameFileHandles.Seek.START;
        };
    try {
      handles.seek(handle, offset, seek);
      return true;
    } catch (IOException outside) {
      handles.position(handle); // rethrows for an invalid handle
      return false;
    }
  }

  @Override
  public void close(int handle) throws IOException {
    handles.close(handle);
  }

  @Override
  public List<String> list(String directory, String extension) throws IOException {
    Set<String> keys = new LinkedHashSet<>();
    List<String> names = new ArrayList<>();
    try {
      if (home != null && !"/".equals(extension))
        for (String name : home.list(directory, extension))
          if (keys.add(name.toLowerCase(Locale.ROOT))) names.add(name);
      for (String name : view.list(directory, extension))
        if (keys.add(name.toLowerCase(Locale.ROOT))) names.add(name);
    } catch (IllegalArgumentException refused) {
      return List.of();
    }
    return List.copyOf(names);
  }

  @Override
  public List<ModInfo> mods() throws IOException {
    return mods == null ? List.of() : mods.scan();
  }

  @Override
  public void closeAll() throws IOException {
    handles.close();
  }

  /** Open handles, for leak checks. */
  public int openCount() {
    return handles.openCount();
  }
}
