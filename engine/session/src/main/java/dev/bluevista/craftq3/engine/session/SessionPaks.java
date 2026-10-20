package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.client.net.DownloadManager;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.fs.DownloadCache;
import dev.bluevista.craftq3.engine.fs.PureManifest;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The session's mounted packs: the installation's search path and its {@link PureManifest}, plus
 * the PK3s downloaded for (or found in the download cache for) the remote server being played.
 * Downloaded packs are mounted only while that game lasts; {@link #unmountDownloads()} returns to
 * the installation alone. The download cache ({@code <home>/.downloads}, outside every game's home
 * store) is opened on first use.
 *
 * <p>Replacing the search path leaves the old one open until {@link #releaseRetired()}, because the
 * client content still reads through its view until it is rebuilt.
 *
 * <p>Owned by the client thread.
 */
final class SessionPaks implements AutoCloseable {
  /** The download cache directory under the home root (not a valid game directory name). */
  static final String CACHE_DIRECTORY = ".downloads";

  private static final Log LOG = Log.get(SessionPaks.class);

  private final Path installRoot;
  private final String game;
  private final Path homeRoot;
  private final Map<String, DownloadCache.Entry> downloaded = new LinkedHashMap<>();
  private final List<SearchPath> retired = new ArrayList<>();
  private SearchPath searchPath;
  private PureManifest manifest;
  private DownloadCache cache;
  private boolean cacheTried;
  private boolean changed;

  /**
   * @param homeRoot the writable home root, or null (no download cache)
   * @throws IOException when the installation cannot be mounted
   */
  SessionPaks(Path installRoot, String game, Path homeRoot) throws IOException {
    this.installRoot = installRoot;
    this.game = game;
    this.homeRoot = homeRoot;
    searchPath = SearchPath.mount(installRoot, game);
    manifest = new PureManifest(searchPath);
  }

  /** Over an installation the host already mounted (owned from now on). */
  SessionPaks(SearchPath mounted, Path installRoot, String game, Path homeRoot) {
    this.installRoot = installRoot;
    this.game = game;
    this.homeRoot = homeRoot;
    searchPath = mounted;
    manifest = new PureManifest(mounted);
  }

  SearchPath searchPath() {
    return searchPath;
  }

  PureManifest manifest() {
    return manifest;
  }

  /** The packs mounted from the download cache for the current game. */
  List<DownloadCache.Entry> downloaded() {
    return List.copyOf(downloaded.values());
  }

  /**
   * The download cache, opened on first use; null without a home or when it cannot be opened (in
   * use by another session, unexpected files), which is reported once to {@code out}.
   */
  DownloadCache cache(Consumer<String> out) {
    if (cacheTried) return cache;
    cacheTried = true;
    if (homeRoot == null) return null;
    try {
      cache = DownloadCache.open(homeRoot.resolve(CACHE_DIRECTORY));
    } catch (IOException | RuntimeException unusable) {
      out.accept("Download cache unusable, PK3s cannot be downloaded: " + unusable.getMessage());
    }
    return cache;
  }

  /** What a connection's {@link DownloadManager} mounts its packs into. */
  DownloadManager.Content downloadContent() {
    return new DownloadManager.Content() {
      @Override
      public SearchPath searchPath() {
        return searchPath;
      }

      @Override
      public void mount(List<DownloadCache.Entry> archives) throws IOException {
        SessionPaks.this.mount(archives);
      }
    };
  }

  /** Adds cached archives to the search path (rebuilt; the old one is retired). */
  void mount(List<DownloadCache.Entry> archives) throws IOException {
    Map<String, DownloadCache.Entry> next = new LinkedHashMap<>(downloaded);
    for (DownloadCache.Entry entry : archives) next.put(entry.request().remoteName(), entry);
    remount(next);
  }

  /**
   * Returns to the installation's packs alone.
   *
   * @return true when downloaded packs were mounted (the content must be rebuilt)
   */
  boolean unmountDownloads() {
    if (downloaded.isEmpty()) return false;
    try {
      remount(Map.of());
    } catch (IOException failure) {
      LOG.warn("Couldn't remount the installation: " + failure.getMessage());
      return false;
    }
    return true;
  }

  private void remount(Map<String, DownloadCache.Entry> archives) throws IOException {
    SearchPath.Builder builder = SearchPath.builder(installRoot).game(game);
    for (DownloadCache.Entry entry : archives.values()) builder.cachedArchive(entry);
    SearchPath built = builder.build();
    retired.add(searchPath);
    searchPath = built;
    manifest = new PureManifest(built);
    downloaded.clear();
    downloaded.putAll(archives);
    changed = true;
  }

  /** True once after the search path was replaced: the client content must be rebuilt. */
  boolean takeChanged() {
    boolean was = changed;
    changed = false;
    return was;
  }

  /** Closes the search paths replaced since the content was last rebuilt. */
  void releaseRetired() {
    for (SearchPath old : retired) {
      try {
        old.close();
      } catch (IOException failure) {
        LOG.debug("Search path close failed");
      }
    }
    retired.clear();
  }

  @Override
  public void close() {
    releaseRetired();
    try {
      searchPath.close();
    } catch (IOException failure) {
      LOG.debug("Search path close failed");
    }
    if (cache != null) {
      try {
        cache.close();
      } catch (IOException failure) {
        LOG.debug("Download cache close failed");
      }
    }
  }
}
