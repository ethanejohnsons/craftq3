package dev.bluevista.craftq3.engine.fs;

import dev.bluevista.craftq3.engine.common.log.Log;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The read-only Quake III search path for one game: the mod directory (when not {@code baseq3}) and
 * then {@code baseq3}, each contributing its PK3s and its loose files in the order chosen by {@link
 * SearchOrder}. Within one directory PK3s are sorted by name with the original engine's comparison
 * and later names searched first.
 *
 * <p>Built once and immutable afterwards: archives are indexed and held open until {@link #close},
 * loose directories are indexed at build time. Empty, unreadable or malformed PK3s are skipped with
 * a one-time warning and listed in {@link #report()}; they never fail the build.
 *
 * <p>Thread safety: every read method may be called from any thread concurrently. {@link #close}
 * must not race reads that matter; a read after close fails.
 */
public final class SearchPath implements FileSource, AutoCloseable {
  /** The fallback game directory every search path ends with. */
  public static final String BASE_GAME = "baseq3";

  /** Most PK3s mounted in one search path. */
  public static final int MAX_ARCHIVES = 1024;

  /** Most files (PK3 members plus loose files) indexed in one search path. */
  public static final int MAX_INDEXED_FILES = 400_000;

  private static final Log LOG = Log.get(SearchPath.class);

  private final String game;
  private final SearchOrder order;
  private final List<Mount> mounts;
  private final List<PackMount> packs;
  private final Map<String, long[]> index;
  private final MountReport report;
  private final FileView view;
  private volatile boolean closed;

  private SearchPath(
      String game,
      SearchOrder order,
      List<Mount> mounts,
      Map<String, long[]> index,
      MountReport report) {
    this.game = game;
    this.order = order;
    this.mounts = List.copyOf(mounts);
    this.packs =
        this.mounts.stream()
            .filter(PackMount.class::isInstance)
            .map(PackMount.class::cast)
            .toList();
    this.index = index;
    this.report = report;
    int[] rank = new int[this.mounts.size()];
    for (int i = 0; i < rank.length; i++) rank[i] = i;
    this.view = new FileView(this, this.mounts, rank, false, null);
  }

  /** Mounts {@code game} (null or blank for {@code baseq3}) in {@link SearchOrder#ORIGINAL_132}. */
  public static SearchPath mount(Path installRoot, String game) throws IOException {
    return builder(installRoot).game(game).build();
  }

  public static SearchPath mount(Path installRoot, String game, SearchOrder order)
      throws IOException {
    return builder(installRoot).game(game).order(order).build();
  }

  public static Builder builder(Path installRoot) {
    return new Builder(installRoot);
  }

  /** Configures and builds a {@link SearchPath}. Not thread-safe; use once. */
  public static final class Builder {
    private final Path installRoot;
    private String game = BASE_GAME;
    private SearchOrder order = SearchOrder.ORIGINAL_132;
    private final Map<String, Cached> cached = new LinkedHashMap<>();

    private record Cached(String game, Path file, String name, int checksum) {}

    private Builder(Path installRoot) {
      this.installRoot = Objects.requireNonNull(installRoot);
    }

    /** The mod directory name as spelled on disk; null or blank means {@code baseq3}. */
    public Builder game(String game) {
      if (game == null || game.isBlank()) this.game = BASE_GAME;
      else {
        VirtualPath.gameDirectory(game);
        this.game = game;
      }
      return this;
    }

    public Builder order(SearchOrder order) {
      this.order = Objects.requireNonNull(order);
      return this;
    }

    /**
     * Adds a download-cache archive to {@code game} under the server's pack name. It sorts among
     * that directory's PK3s by {@code name}, replaces an installed PK3 of the same name, and is
     * skipped with a warning unless its checksum equals {@code checksum}. A later call for the same
     * game and name replaces an earlier one.
     */
    public Builder cachedArchive(String game, Path file, String name, int checksum) {
      String key = VirtualPath.gameDirectory(game);
      String checked = VirtualPath.of(name + ".pk3").key();
      if (checked.indexOf('/') >= 0) throw new IllegalArgumentException("Invalid PK3 name " + name);
      cached.put(key + '/' + VirtualPath.fold(name), new Cached(key, file, name, checksum));
      return this;
    }

    public Builder cachedArchive(DownloadCache.Entry entry) {
      return cachedArchive(
          entry.request().game(), entry.path(), entry.request().name(), entry.request().checksum());
    }

    /**
     * Opens every archive and indexes every directory.
     *
     * @throws NoSuchFileException if the game directory or {@code baseq3} is missing
     * @throws IOException if the installation cannot be listed or exceeds a resource limit
     */
    public SearchPath build() throws IOException {
      Path root = installRoot.toRealPath();
      String modKey = VirtualPath.gameDirectory(game);
      List<String> games = modKey.equals(BASE_GAME) ? List.of(BASE_GAME) : List.of(game, BASE_GAME);
      for (Cached entry : cached.values())
        if (!entry.game.equals(BASE_GAME) && !entry.game.equals(modKey))
          throw new IOException("Cached PK3 belongs to another game: " + entry.game);
      var mounts = new ArrayList<Mount>();
      var skipped = new ArrayList<MountReport.SkippedArchive>();
      int[] budget = {MAX_INDEXED_FILES};
      try {
        for (String spelling : games) {
          String key = VirtualPath.gameDirectory(spelling);
          Path directory = findGameDirectory(root, spelling);
          if (directory == null)
            throw new NoSuchFileException(root.resolve(spelling).toString(), null, "no game dir");
          addGame(key, directory, mounts, skipped, budget);
        }
      } catch (IOException | RuntimeException failure) {
        closeAll(mounts, failure);
        throw failure;
      }
      Map<String, long[]> index = new HashMap<>(Math.max(16, (MAX_INDEXED_FILES - budget[0]) * 2));
      int skippedEntries = 0;
      for (Mount mount : mounts) {
        if (mount instanceof PackMount pack) {
          List<Pk3Archive.Entry> entries = pack.archive().entries();
          for (int i = 0; i < entries.size(); i++)
            add(index, entries.get(i).path().key(), mount.index(), i);
          skippedEntries += pack.archive().problemCount();
        } else if (mount instanceof DirectoryMount directory) {
          List<String> names = directory.names();
          for (int i = 0; i < names.size(); i++)
            add(index, VirtualPath.fold(names.get(i)), mount.index(), i);
        }
      }
      return new SearchPath(modKey, order, mounts, index, new MountReport(skipped, skippedEntries));
    }

    private void addGame(
        String key,
        Path directory,
        List<Mount> mounts,
        List<MountReport.SkippedArchive> skipped,
        int[] budget)
        throws IOException {
      record Candidate(String fileName, String name, Path file, Cached cached) {}
      var candidates = new LinkedHashMap<String, Candidate>();
      try (var children = Files.list(directory)) {
        for (Path child : (Iterable<Path>) children.sorted()::iterator) {
          String fileName = child.getFileName().toString();
          if (fileName.length() <= 4
              || !VirtualPath.fold(fileName).endsWith(".pk3")
              || !Files.isRegularFile(child)) continue;
          String name = fileName.substring(0, fileName.length() - 4);
          candidates.putIfAbsent(fileName, new Candidate(fileName, name, child, null));
        }
      }
      for (Cached entry : cached.values()) {
        if (!entry.game.equals(key)) continue;
        candidates
            .values()
            .removeIf(c -> c.cached == null && VirtualPath.fold(c.name).equals(fold(entry.name)));
        candidates.put(
            "\u0000cached/" + entry.name,
            new Candidate(entry.name + ".pk3", entry.name, entry.file, entry));
      }
      var sorted = new ArrayList<>(candidates.values());
      sorted.sort(
          Comparator.comparing(Candidate::fileName, PathOrder.INSTANCE)
              .thenComparing(Candidate::fileName)
              .reversed());
      if (mounts.size() + sorted.size() > MAX_ARCHIVES)
        throw new IOException("Search path exceeds " + MAX_ARCHIVES + " PK3s");

      var packs = new ArrayList<Pk3Archive>();
      var names = new ArrayList<Candidate>();
      try {
        for (Candidate candidate : sorted) {
          Pk3Archive archive = null;
          String reason;
          try {
            archive = Pk3Archive.open(candidate.file);
            reason = null;
          } catch (Pk3FormatException malformed) {
            reason = malformed.getMessage();
          } catch (IOException unreadable) {
            reason = "unreadable: " + unreadable;
          }
          if (archive != null
              && candidate.cached != null
              && archive.checksum() != candidate.cached.checksum) {
            archive.close();
            archive = null;
            reason = "cached PK3 checksum changed";
          }
          if (archive == null) {
            skipped.add(new MountReport.SkippedArchive(key, candidate.file, reason));
            LOG.warnOnce(
                "skip:" + candidate.file, "Skipping PK3 " + candidate.file + ": " + reason);
            continue;
          }
          packs.add(archive);
          names.add(candidate);
          if (archive.entries().size() > budget[0])
            throw new IOException("Search path exceeds " + MAX_INDEXED_FILES + " files");
          budget[0] -= archive.entries().size();
          if (archive.problemCount() > 0)
            LOG.warnOnce(
                "entries:" + archive.file(),
                "Skipped "
                    + archive.problemCount()
                    + " entries of "
                    + archive.file()
                    + ", first: "
                    + archive.problems().getFirst());
        }

        Path real = directory.toRealPath();
        if (order == SearchOrder.LOOSE_FIRST) addDirectory(key, real, mounts, budget);
        for (int i = 0; i < packs.size(); i++)
          mounts.add(
              new PackMount(
                  mounts.size(),
                  key,
                  names.get(i).name,
                  packs.get(i),
                  names.get(i).cached != null));
        if (order == SearchOrder.ORIGINAL_132) addDirectory(key, real, mounts, budget);
      } catch (IOException | RuntimeException failure) {
        closeArchives(packs, failure);
        throw failure;
      }
    }

    private static void addDirectory(String key, Path real, List<Mount> mounts, int[] budget)
        throws IOException {
      DirectoryMount directory = DirectoryMount.scan(mounts.size(), key, real, budget[0]);
      budget[0] -= directory.names().size();
      mounts.add(directory);
    }

    private static String fold(String text) {
      return VirtualPath.fold(text);
    }
  }

  private static void add(Map<String, long[]> index, String key, int mount, int entry) {
    long candidate = (long) mount << 32 | (entry & 0xffffffffL);
    long[] existing = index.get(key);
    if (existing == null) {
      index.put(key, new long[] {candidate});
      return;
    }
    long[] grown = java.util.Arrays.copyOf(existing, existing.length + 1);
    grown[existing.length] = candidate;
    index.put(key, grown);
  }

  /** Finds a game directory by its spelling, falling back to a case-insensitive match. */
  private static Path findGameDirectory(Path root, String spelling) throws IOException {
    Path direct = root.resolve(spelling);
    if (Files.isDirectory(direct)) return direct;
    String key = VirtualPath.fold(spelling);
    try (var children = Files.list(root)) {
      return children
          .filter(child -> VirtualPath.fold(child.getFileName().toString()).equals(key))
          .filter(Files::isDirectory)
          .sorted()
          .findFirst()
          .orElse(null);
    }
  }

  private static void closeAll(List<Mount> mounts, Throwable failure) {
    for (Mount mount : mounts)
      if (mount instanceof PackMount pack)
        try {
          pack.archive().close();
        } catch (IOException close) {
          failure.addSuppressed(close);
        }
  }

  private static void closeArchives(List<Pk3Archive> archives, Throwable failure) {
    for (Pk3Archive archive : archives)
      try {
        archive.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
  }

  /** Candidates for a lookup key as {@code mount << 32 | entry}, in build order; null if none. */
  long[] candidates(String key) {
    checkOpen();
    return index.get(key);
  }

  void checkOpen() {
    if (closed) throw new IllegalStateException("Search path is closed");
  }

  /** The mounted game in lookup form ({@code baseq3} or the mod). */
  public String game() {
    return game;
  }

  public SearchOrder order() {
    return order;
  }

  /** Every mount in build (search) order. */
  public List<Mount> mounts() {
    return mounts;
  }

  /** Every mounted PK3 in build order. */
  public List<PackMount> packs() {
    return packs;
  }

  public MountReport report() {
    return report;
  }

  /** The unrestricted view in build order; references are not tracked. */
  public FileView view() {
    return view;
  }

  public Optional<FileRef> find(VirtualPath path) {
    return view.find(path);
  }

  @Override
  public boolean exists(VirtualPath path) {
    return view.exists(path);
  }

  @Override
  public byte[] read(VirtualPath path) throws IOException {
    return view.read(path);
  }

  @Override
  public InputStream open(VirtualPath path) throws IOException {
    return view.open(path);
  }

  /** See {@link FileView#list}. */
  public List<String> list(String directory, String extension) {
    return view.list(directory, extension);
  }

  /** Reads {@code path} from one specific mount of this search path, ignoring precedence. */
  public byte[] readFrom(Mount mount, VirtualPath path) throws IOException {
    if (closed) throw new IllegalStateException("Search path is closed");
    if (mount.index() < 0 || mount.index() >= mounts.size() || mounts.get(mount.index()) != mount)
      throw new IOException("Mount is not part of this search path: " + mount);
    if (mount instanceof PackMount pack) {
      var entry = pack.archive().find(path);
      if (entry.isEmpty()) throw new NoSuchFileException(path.key());
      return pack.archive().read(entry.get());
    }
    DirectoryMount directory = (DirectoryMount) mount;
    String spelling = directory.spelling(path);
    if (spelling == null) throw new NoSuchFileException(path.key());
    return directory.read(path, spelling);
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    for (PackMount pack : packs)
      try {
        pack.archive().close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    if (failure != null) throw failure;
  }
}
