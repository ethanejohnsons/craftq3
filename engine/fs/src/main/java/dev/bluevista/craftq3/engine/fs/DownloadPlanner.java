package dev.bluevista.craftq3.engine.fs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, from a server's {@code sv_referencedPaks} and {@code sv_referencedPakNames}, which packs
 * the client already has, which the download cache can supply, and which must be downloaded.
 * Download policy ({@code cl_allowDownload}) stays with the caller. Pure function; thread-safe.
 */
public final class DownloadPlanner {
  /** Most referenced packs a server may announce. */
  public static final int MAX_REFERENCED = SearchPath.MAX_ARCHIVES;

  /** Most packs one connection may download. */
  public static final int MAX_MISSING = DownloadCache.MAX_FILES;

  private DownloadPlanner() {}

  /**
   * The outcome: cached archives to add to the next search-path build ({@link
   * SearchPath.Builder#cachedArchive(DownloadCache.Entry)}), and downloads still needed.
   */
  public record Plan(List<DownloadCache.Entry> fromCache, List<DownloadCache.Request> downloads) {
    public Plan {
      fromCache = List.copyOf(fromCache);
      downloads = List.copyOf(downloads);
    }

    /** True when nothing needs downloading. */
    public boolean complete() {
      return downloads.isEmpty();
    }
  }

  /** Plans against the packs mounted in {@code searchPath}. */
  public static Plan plan(
      SearchPath searchPath,
      String referencedPaks,
      String referencedPakNames,
      List<DownloadCache.Entry> cached)
      throws IOException {
    var installed = new HashSet<Integer>();
    for (PackMount pack : searchPath.packs()) installed.add(pack.checksum());
    return plan(searchPath.game(), referencedPaks, referencedPakNames, installed, cached);
  }

  /**
   * @param game the client's mounted game directory
   * @param installed checksums of packs the client already mounts
   * @param cached the download cache's entries, oldest first
   * @throws IOException if the server's lists are malformed, too long, name another game, request
   *     an official pack, or omit names for missing packs
   */
  public static Plan plan(
      String game,
      String referencedPaks,
      String referencedPakNames,
      Collection<Integer> installed,
      List<DownloadCache.Entry> cached)
      throws IOException {
    String gameKey = VirtualPath.gameDirectory(game);
    String[] checksums = split(referencedPaks);
    String[] names = split(referencedPakNames);
    if (checksums.length > MAX_REFERENCED)
      throw new IOException("Too many referenced server PK3s: " + checksums.length);
    Set<Integer> have = new HashSet<>(installed);
    var fromCache = new ArrayList<DownloadCache.Entry>();
    Map<Integer, DownloadCache.Request> missing = new LinkedHashMap<>();
    for (int i = 0; i < checksums.length; i++) {
      int checksum;
      try {
        checksum = Integer.parseInt(checksums[i]);
      } catch (NumberFormatException malformed) {
        throw new IOException("Malformed server PK3 checksum: " + checksums[i], malformed);
      }
      if (have.contains(checksum)) continue;
      DownloadCache.Entry available = null;
      for (DownloadCache.Entry entry : cached) {
        String entryGame = entry.request().game();
        if (entry.request().checksum() == checksum
            && (entryGame.equals(gameKey) || entryGame.equals(SearchPath.BASE_GAME))) {
          available = entry;
          break;
        }
      }
      if (available != null) {
        fromCache.add(available);
        have.add(checksum);
        continue;
      }
      if (names.length != checksums.length)
        throw new IOException(
            "Missing server PK3 checksum " + checksum + "; server omitted matching names");
      if (missing.containsKey(checksum)) continue;
      DownloadCache.Request request;
      try {
        request = new DownloadCache.Request(names[i] + ".pk3", checksum);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Server requested an invalid PK3: " + names[i], invalid);
      }
      if (!request.game().equals(gameKey) && !request.game().equals(SearchPath.BASE_GAME))
        throw new IOException("Server requested a PK3 from another game directory: " + names[i]);
      missing.put(checksum, request);
    }
    if (missing.size() > MAX_MISSING)
      throw new IOException("Too many missing server PK3s: " + missing.size());
    return new Plan(fromCache, new ArrayList<>(missing.values()));
  }

  private static String[] split(String list) {
    String trimmed = list == null ? "" : list.trim();
    return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
  }
}
