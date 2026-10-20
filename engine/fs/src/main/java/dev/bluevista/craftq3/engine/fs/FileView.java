package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A search order over a {@link SearchPath}: which mounts are visible and in what priority. The
 * search path's own view shows every mount in build order; a {@link PureManifest} publishes views
 * restricted and reordered for a pure server, which also record PK3 references.
 *
 * <p>Immutable and safe to share between threads; reference recording is atomic.
 */
public final class FileView implements FileSource {
  /** Receives the reference flags of each PK3 read through a view. Must be thread-safe. */
  interface ReferenceSink {
    void referenced(PackMount pack, int flags);
  }

  private static final int EXCLUDED = Integer.MAX_VALUE;

  private final SearchPath searchPath;
  private final List<Mount> order;
  private final int[] rank;
  private final boolean pure;
  private final ReferenceSink references;

  /**
   * @param order visible mounts, highest priority first
   * @param rank priority by mount index; {@code Integer.MAX_VALUE} hides the mount
   * @param pure restricts loose files to those a pure server allows
   */
  FileView(SearchPath searchPath, List<Mount> order, int[] rank, boolean pure, ReferenceSink sink) {
    this.searchPath = searchPath;
    this.order = List.copyOf(order);
    this.rank = rank;
    this.pure = pure;
    this.references = sink;
  }

  static FileView of(
      SearchPath searchPath,
      List<Mount> order,
      Set<Mount> hidden,
      boolean pure,
      ReferenceSink sink) {
    int[] rank = new int[searchPath.mounts().size()];
    java.util.Arrays.fill(rank, EXCLUDED);
    var visible = new ArrayList<Mount>();
    for (Mount mount : order) {
      if (hidden.contains(mount)) continue;
      rank[mount.index()] = visible.size();
      visible.add(mount);
    }
    return new FileView(searchPath, visible, rank, pure, sink);
  }

  public SearchPath searchPath() {
    return searchPath;
  }

  /** Visible mounts, highest priority first. */
  public List<Mount> mounts() {
    return order;
  }

  /** True when loose files are limited to the pure-server allow-list. */
  public boolean pure() {
    return pure;
  }

  private long resolve(VirtualPath path) {
    long[] candidates = searchPath.candidates(path.key());
    if (candidates == null) return -1;
    long best = -1;
    int bestRank = EXCLUDED;
    for (long candidate : candidates) {
      int mount = (int) (candidate >>> 32);
      int r = rank[mount];
      if (r >= bestRank) continue;
      if (pure
          && searchPath.mounts().get(mount) instanceof DirectoryMount
          && !PureManifest.looseAllowed(path.key())) continue;
      best = candidate;
      bestRank = r;
    }
    return best;
  }

  public Optional<FileRef> find(VirtualPath path) {
    long candidate = resolve(path);
    if (candidate < 0) return Optional.empty();
    Mount mount = searchPath.mounts().get((int) (candidate >>> 32));
    int entry = (int) candidate;
    String stored =
        mount instanceof PackMount pack
            ? pack.archive().entries().get(entry).name()
            : ((DirectoryMount) mount).names().get(entry);
    return Optional.of(new FileRef(path, mount, stored));
  }

  @Override
  public boolean exists(VirtualPath path) {
    return resolve(path) >= 0;
  }

  /** Expanded size of a visible file, or -1 when it is not visible. */
  public long size(VirtualPath path) throws IOException {
    long candidate = resolve(path);
    if (candidate < 0) return -1;
    Mount mount = searchPath.mounts().get((int) (candidate >>> 32));
    if (mount instanceof PackMount pack)
      return pack.archive().entries().get((int) candidate).size();
    DirectoryMount directory = (DirectoryMount) mount;
    return directory.size(path, directory.names().get((int) candidate));
  }

  @Override
  public byte[] read(VirtualPath path) throws IOException {
    long candidate = resolve(path);
    if (candidate < 0) throw new NoSuchFileException(path.key());
    Mount mount = searchPath.mounts().get((int) (candidate >>> 32));
    if (mount instanceof PackMount pack) {
      byte[] bytes = pack.archive().read(pack.archive().entries().get((int) candidate));
      reference(pack, path);
      return bytes;
    }
    DirectoryMount directory = (DirectoryMount) mount;
    return directory.read(path, directory.names().get((int) candidate));
  }

  /**
   * Streams a visible file. Cinematics ({@code .roq}) may stream up to {@link
   * Pk3Archive#MAX_STREAM_BYTES}; size and CRC are checked at end of stream.
   */
  @Override
  public InputStream open(VirtualPath path) throws IOException {
    long candidate = resolve(path);
    if (candidate < 0) throw new NoSuchFileException(path.key());
    Mount mount = searchPath.mounts().get((int) (candidate >>> 32));
    if (mount instanceof PackMount pack) {
      InputStream stream = pack.archive().open(pack.archive().entries().get((int) candidate));
      reference(pack, path);
      return stream;
    }
    DirectoryMount directory = (DirectoryMount) mount;
    return directory.open(path, directory.names().get((int) candidate), Pk3Archive.limit(path));
  }

  private void reference(PackMount pack, VirtualPath path) {
    if (references != null)
      references.referenced(pack, PureManifest.referenceFlags(path.spelling()));
  }

  /**
   * Names directly inside {@code directory} (relative to it) whose name ends with {@code extension}
   * (compared case-insensitively; {@code ""} matches everything), in search order without
   * duplicates. An extension of {@code "/"} lists subdirectory names instead. PK3 names are
   * returned in lookup form, loose names as spelled on disk. A pure view lists no loose files.
   */
  public List<String> list(String directory, String extension) {
    searchPath.checkOpen();
    String prefix = VirtualPath.directoryKey(directory);
    boolean directories = "/".equals(extension);
    String suffix = directories || extension == null ? "" : VirtualPath.fold(extension);
    var seen = new HashSet<String>();
    var result = new ArrayList<String>();
    for (Mount mount : order) {
      if (mount instanceof PackMount pack) {
        for (Pk3Archive.Entry entry : pack.archive().entries())
          collect(
              entry.path().key(), entry.path().key(), prefix, suffix, directories, seen, result);
      } else if (!pure) {
        for (String name : ((DirectoryMount) mount).names())
          collect(VirtualPath.fold(name), name, prefix, suffix, directories, seen, result);
      }
    }
    return List.copyOf(result);
  }

  private static void collect(
      String key,
      String spelled,
      String prefix,
      String suffix,
      boolean directories,
      Set<String> seen,
      List<String> result) {
    if (!key.startsWith(prefix)) return;
    int slash = key.indexOf('/', prefix.length());
    String name;
    if (directories) {
      if (slash < 0) return;
      name = spelled.substring(prefix.length(), slash);
    } else {
      if (slash >= 0 || !key.endsWith(suffix)) return;
      name = spelled.substring(prefix.length());
    }
    if (seen.add(VirtualPath.fold(name))) result.add(name);
  }
}
