package dev.bluevista.craftq3.assets.fs;

import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Indexed read-only installation. Rebuild the mount to observe loose-file additions. */
public final class Pk3FileSystem implements VirtualFileSystem {
  public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
  public static final int GENERAL_REFERENCE = 1, UI_REFERENCE = 2, CGAME_REFERENCE = 4;
  private static final long MAX_VIDEO_BYTES = 512L * 1024 * 1024;
  private static final int MAX_ENTRIES = 400_000;
  private static final int MAX_ARCHIVES = 1024;
  private final String game;
  private final List<ZipFile> archives = new ArrayList<>();
  private final List<Origin> order = new ArrayList<>();
  private final List<Origin> defaultOrder = new ArrayList<>();
  private final java.util.Set<Origin> downloaded = new java.util.HashSet<>();
  private final Map<Origin, Map<VirtualPath, Entry>> sources = new LinkedHashMap<>();
  private final Map<Origin, PackState> packs = new LinkedHashMap<>();
  private final Map<VirtualPath, Entry> files = new TreeMap<>();
  private List<Integer> serverPaks = List.of();
  private boolean reordered;
  private int indexedEntries, checksumFeed;
  private boolean closed;

  private record Entry(Origin origin, Path root, Path loose, ZipFile zip, ZipEntry member) {}

  /** Pack identity uses archive metadata, so names and ZIP compression do not alter checksums. */
  public record Pack(Origin origin, String name, int checksum, int pureChecksum, int references) {}

  private static final class PackState {
    final Origin origin;
    String name;
    final int[] crcs;
    final int checksum;
    int references;

    PackState(Origin origin, Path path, List<Integer> crcs) {
      this.origin = origin;
      String filename = path.getFileName().toString();
      name = filename.substring(0, filename.length() - 4);
      this.crcs = crcs.stream().mapToInt(Integer::intValue).toArray();
      checksum = Pk3Checksums.normal(this.crcs);
    }

    Pack snapshot(int feed) {
      return new Pack(origin, name, checksum, Pk3Checksums.pure(crcs, feed), references);
    }
  }

  private Pk3FileSystem(String game) {
    this.game = game;
  }

  /** The selected installation directory, independent of a server's pack ordering. */
  public String gameDirectory() {
    return game;
  }

  public static Pk3FileSystem mount(Path installRoot, String mod) throws IOException {
    String game = mod == null || mod.isBlank() ? "baseq3" : VirtualPath.gameDirectory(mod);
    Path root = installRoot.toRealPath();
    Pk3FileSystem fs = new Pk3FileSystem(game);
    try {
      if (!game.equals("baseq3")) fs.mountGame(root, game, true);
      fs.mountGame(root, "baseq3", true);
      fs.defaultOrder.addAll(fs.order);
      fs.rebuildFiles();
      return fs;
    } catch (IOException | RuntimeException error) {
      try {
        fs.close();
      } catch (IOException closeError) {
        error.addSuppressed(closeError);
      }
      throw error;
    }
  }

  private void mountGame(Path root, String game, boolean required) throws IOException {
    int searchStart = order.size();
    Path directory = root.resolve(game);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      if (required)
        throw new IOException("Missing game directory (symlinks are not allowed): " + directory);
      return;
    }
    Path real = directory.toRealPath();
    if (!real.startsWith(root)) throw new IOException("Game directory escapes installation root");
    List<Path> paks;
    try (var children = Files.list(real)) {
      paks =
          children
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .filter(
                  path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3"))
              .limit(MAX_ARCHIVES + 1L)
              .sorted(
                  Comparator.comparing(
                          (Path path) -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                      .thenComparing(Path::toString)
                      .reversed())
              .toList();
    }
    if (paks.size() + archives.size() > MAX_ARCHIVES)
      throw new IOException("Too many PK3 archives");
    String previous = null;
    for (Path pak : paks) {
      String name = pak.getFileName().toString().toLowerCase(Locale.ROOT);
      if (name.equals(previous)) throw new IOException("Ambiguous PK3 names: " + name);
      previous = name;
      mountArchive(game, pak);
    }
    Origin origin = new Origin(game, real.toString(), false);
    order.add(searchStart, origin);
    Map<VirtualPath, Entry> loose = new LinkedHashMap<>();
    try (var walk = Files.walk(real, 32)) {
      var iterator = walk.iterator();
      while (iterator.hasNext()) {
        Path path = iterator.next();
        countEntry();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
        VirtualPath virtual;
        try {
          virtual = new VirtualPath(real.relativize(path).toString());
        } catch (IllegalArgumentException e) {
          throw new IOException("Unsafe loose path: " + path, e);
        }
        if (loose.putIfAbsent(virtual, new Entry(origin, real, path, null, null)) != null) {
          throw new IOException(
              "Ambiguous loose path after case normalization: " + virtual.value());
        }
      }
    }
    sources.put(origin, loose);
    loose.forEach(files::putIfAbsent);
  }

  private void mountArchive(String game, Path path) throws IOException {
    if (Files.size(path) > 2L * 1024 * 1024 * 1024)
      throw new IOException("PK3 exceeds 2 GiB limit: " + path);
    ZipSafety.preflight(path, MAX_ENTRIES - indexedEntries);
    ZipFile zip;
    try {
      // Legacy minizip names are bytes, like guest VM paths. The ZIP UTF-8 flag still takes
      // priority.
      zip = new ZipFile(path.toFile(), StandardCharsets.ISO_8859_1);
    } catch (IOException malformed) {
      throw new IOException("Cannot open PK3 archive: " + path, malformed);
    }
    if (zip.size() == 0) {
      zip.close();
      return;
    }
    archives.add(zip);
    Origin origin = new Origin(game, path.toString(), true);
    order.add(origin);
    Map<VirtualPath, Entry> members = new LinkedHashMap<>();
    List<Integer> crcs = new ArrayList<>();
    var entries = zip.entries();
    while (entries.hasMoreElements()) {
      ZipEntry member = entries.nextElement();
      countEntry();
      if (member.getSize() > 0) crcs.add((int) member.getCrc());
      String name = member.getName();
      if (member.isDirectory()) name = name.substring(0, name.length() - 1);
      VirtualPath virtual;
      try {
        virtual = new VirtualPath(name);
      } catch (IllegalArgumentException e) {
        throw new IOException("Unsafe PK3 member in " + path + ": " + name, e);
      }
      if (member.isDirectory()) continue;
      if (member.getSize() < 0 || member.getSize() > streamLimit(virtual)) {
        throw new IOException("PK3 member exceeds file limit: " + name);
      }
      if (members.putIfAbsent(virtual, new Entry(origin, null, null, zip, member)) != null) {
        throw new IOException("Ambiguous duplicate PK3 member: " + virtual.value());
      }
    }
    sources.put(origin, members);
    packs.put(origin, new PackState(origin, path, crcs));
    members.forEach(files::putIfAbsent);
  }

  private void countEntry() throws IOException {
    if (++indexedEntries > MAX_ENTRIES) throw new IOException("VFS entry limit exceeded");
  }

  @Override
  public synchronized Optional<Origin> which(VirtualPath path) {
    ensureOpen();
    return Optional.ofNullable(files.get(path)).map(Entry::origin);
  }

  @Override
  public synchronized List<VirtualPath> list(String directory) {
    ensureOpen();
    String prefix =
        directory == null || directory.isEmpty()
            ? ""
            : new VirtualPath(
                        directory.endsWith("/")
                            ? directory.substring(0, directory.length() - 1)
                            : directory)
                    .value()
                + "/";
    if (serverPaks.isEmpty())
      return files.keySet().stream().filter(path -> path.value().startsWith(prefix)).toList();
    var allowed = new java.util.HashSet<>(serverPaks);
    return order.stream()
        .filter(Origin::archive)
        .filter(origin -> allowed.contains(packs.get(origin).checksum))
        .flatMap(origin -> sources.get(origin).keySet().stream())
        .filter(path -> path.value().startsWith(prefix))
        .distinct()
        .sorted()
        .toList();
  }

  @Override
  public synchronized List<Origin> searchOrder() {
    ensureOpen();
    return List.copyOf(order);
  }

  /** Includes all mounted archives, in their current search order. */
  public synchronized List<Pack> packs() {
    ensureOpen();
    return order.stream()
        .filter(Origin::archive)
        .map(origin -> packs.get(origin).snapshot(checksumFeed))
        .toList();
  }

  /** Adopt a validated cache archive transactionally, retaining its server-visible logical name. */
  public synchronized void attachArchive(
      Path path, String archiveGame, String logicalName, int checksum) throws IOException {
    ensureOpen();
    if (!archiveGame.equals("baseq3") && !archiveGame.equals(game))
      throw new IOException("Cached PK3 belongs to another game");
    String checked = new VirtualPath(logicalName + ".pk3").value();
    if (checked.contains("/") || !checked.equals(logicalName + ".pk3"))
      throw new IOException("Invalid cached PK3 name");
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Cached PK3 is not a regular file");
    path = path.toRealPath();
    Origin origin = new Origin(archiveGame, path.toString(), true);
    PackState existing = packs.get(origin);
    if (existing == null) {
      if (archives.size() >= MAX_ARCHIVES) throw new IOException("Too many mounted PK3 archives");
      try (var candidate = new Pk3FileSystem(game)) {
        candidate.mountArchive(archiveGame, path);
        var pack = candidate.packs.get(origin);
        if (pack == null
            || pack.checksum != checksum
            || candidate.indexedEntries > MAX_ENTRIES - indexedEntries)
          throw new IOException("Cached PK3 checksum or entry budget mismatch");
        pack.name = logicalName;
        archives.addAll(candidate.archives);
        candidate.archives.clear();
        sources.putAll(candidate.sources);
        packs.put(origin, pack);
        indexedEntries += candidate.indexedEntries;
      }
    } else if (existing.checksum != checksum || !existing.name.equals(logicalName))
      throw new IOException("Cached PK3 identity changed");
    downloaded.add(origin);
    defaultOrder.removeIf(
        o ->
            downloaded.contains(o)
                && o.game().equals(archiveGame)
                && packs.get(o).name.equals(logicalName));
    int index = 0;
    while (index < defaultOrder.size()) {
      Origin old = defaultOrder.get(index);
      if (!old.game().equals(archiveGame)) {
        if (archiveGame.equals("baseq3") && !old.game().equals("baseq3")) {
          index++;
          continue;
        }
        break;
      }
      if (downloaded.contains(old) && packs.get(old).name.compareTo(logicalName) > 0) {
        index++;
        continue;
      }
      break;
    }
    defaultOrder.add(index, origin);
    restartView(checksumFeed);
  }

  public record Archive(InputStream input, long size) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      input.close();
    }
  }

  /** Opens only an already-indexed archive; server text never becomes an arbitrary host path. */
  public synchronized Archive openArchive(Pack pack) throws IOException {
    ensureOpen();
    PackState indexed = packs.get(pack.origin());
    if (indexed == null || indexed.checksum != pack.checksum())
      throw new IOException("Archive is not in this installation");
    Path path = Path.of(indexed.origin.container());
    long size = Files.size(path);
    InputStream stream =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    return new Archive(stream, size);
  }

  /** Changes read eligibility immediately. Pack order changes when the view restarts. */
  public synchronized void pureServerPaks(List<Integer> checksums) {
    ensureOpen();
    List<Integer> next = List.copyOf(checksums);
    if (next.size() > MAX_ARCHIVES)
      throw new IllegalArgumentException("Too many server PK3 checksums");
    serverPaks = next;
    if (next.isEmpty() && reordered) {
      order.clear();
      order.addAll(defaultOrder);
      clearReferences(0);
      reordered = false;
    }
    rebuildFiles();
  }

  /**
   * Reorders the indexed view and resets references with a new checksum feed. It does not rescan
   * the installation or observe archive replacements; those require a new mount.
   */
  public synchronized void restartView(int feed) {
    ensureOpen();
    checksumFeed = feed;
    clearReferences(0);
    var remaining = new ArrayList<>(defaultOrder);
    order.clear();
    reordered = false;
    for (int checksum : serverPaks) {
      for (int index = 0; index < remaining.size(); index++) {
        Origin origin = remaining.get(index);
        if (origin.archive() && packs.get(origin).checksum == checksum) {
          order.add(remaining.remove(index));
          reordered = true;
          break;
        }
      }
    }
    order.addAll(remaining);
    rebuildFiles();
  }

  public synchronized void clearReferences(int flags) {
    ensureOpen();
    for (PackState pack : packs.values()) pack.references &= flags == 0 ? 0 : ~flags;
  }

  /** Native pure response fields; the connection supplies the command and server-id prefix. */
  public synchronized String referencedPureChecksums() {
    ensureOpen();
    var result = new StringBuilder();
    List<Pack> current = packs();
    for (int flag : new int[] {CGAME_REFERENCE, UI_REFERENCE}) {
      for (Pack pack : current) {
        if ((pack.references() & flag) != 0) {
          result.append(pack.pureChecksum()).append(' ');
          break;
        }
      }
    }
    result.append("@ ");
    int combined = checksumFeed, count = 0;
    for (Pack pack : current) {
      if ((pack.references() & GENERAL_REFERENCE) != 0) {
        result.append(pack.pureChecksum()).append(' ');
        combined ^= pack.pureChecksum();
        count++;
      }
    }
    result.append(combined ^ count).append(' ');
    if (result.length() >= 8192)
      throw new IllegalStateException("Referenced PK3 checksums exceed the engine string budget");
    return result.toString();
  }

  private void rebuildFiles() {
    files.clear();
    var allowed = new java.util.HashSet<>(serverPaks);
    for (Origin origin : order) {
      if (!allowed.isEmpty() && origin.archive() && !allowed.contains(packs.get(origin).checksum))
        continue;
      for (var entry : sources.get(origin).entrySet()) {
        if (!allowed.isEmpty() && !origin.archive() && !looseAllowed(entry.getKey().value()))
          continue;
        files.putIfAbsent(entry.getKey(), entry.getValue());
      }
    }
  }

  private static boolean looseAllowed(String path) {
    if (path.endsWith(".cfg")
        || path.endsWith(".menu")
        || path.endsWith(".game")
        || path.endsWith(".dat")) return true;
    int dot = path.lastIndexOf('.');
    if (dot < 0) return false;
    String extension = path.substring(dot + 1);
    if (!extension.startsWith("dm_")) return false;
    int start = 3;
    while (start < extension.length() && extension.charAt(start) == ' ') start++;
    String suffix = extension.substring(start);
    int end = suffix.startsWith("+") || suffix.startsWith("-") ? 1 : 0;
    int first = end;
    while (end < suffix.length() && suffix.charAt(end) >= '0' && suffix.charAt(end) <= '9') end++;
    if (end == first) return false;
    // Native atoi uses the host strtol range, then keeps the low signed32 bits.
    int protocol =
        new java.math.BigInteger(suffix.substring(0, end))
            .max(java.math.BigInteger.valueOf(Long.MIN_VALUE))
            .min(java.math.BigInteger.valueOf(Long.MAX_VALUE))
            .intValue();
    return protocol == 66 || protocol == 67 || protocol == 68 || protocol == 71;
  }

  private static int referenceFlags(String requested) {
    String lower = requested.toLowerCase(Locale.ROOT);
    int flags = 0;
    if (!(lower.endsWith(".cfg")
        || lower.endsWith(".txt")
        || lower.endsWith(".shader")
        || lower.endsWith(".arena")
        || lower.endsWith(".bot")
        || lower.endsWith(".menu")
        || lower.endsWith(".config")
        || lower.equals("vm/qagame.qvm")
        || requested.contains("levelshots"))) flags |= GENERAL_REFERENCE;
    if (requested.contains("cgame.qvm")) flags |= CGAME_REFERENCE;
    if (requested.contains("ui.qvm")) flags |= UI_REFERENCE;
    return flags;
  }

  @Override
  public synchronized byte[] read(VirtualPath path) throws IOException {
    ensureOpen();
    Entry entry = files.get(path);
    if (entry == null) throw new FileNotFoundException(path.value());
    return readEntry(entry, path);
  }

  private static long streamLimit(VirtualPath path) {
    return path.value().endsWith(".roq") ? MAX_VIDEO_BYTES : MAX_FILE_BYTES;
  }

  /** Streams the selected virtual entry, retaining pure selection and validating CRC at EOF. */
  @Override
  public synchronized InputStream open(VirtualPath path) throws IOException {
    ensureOpen();
    var entry = files.get(path);
    if (entry == null) throw new FileNotFoundException(path.value());
    long size;
    InputStream input;
    if (entry.zip != null) {
      size = entry.member.getSize();
      input = entry.zip.getInputStream(entry.member);
      packs.get(entry.origin).references |= referenceFlags(path.requested());
    } else {
      if (!entry.loose.toRealPath().startsWith(entry.root)
          || !Files.isRegularFile(entry.loose, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Loose stream escaped its root or changed type: " + path.value());
      size = Files.size(entry.loose);
      if (size > streamLimit(path)) throw new IOException("Virtual stream exceeds size limit");
      input = Files.newInputStream(entry.loose, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }
    return new VerifiedStream(
        input, size, entry.member == null ? -1 : entry.member.getCrc(), path.value());
  }

  /** No read/skip path can bypass the declared size or archive checksum. Early close is allowed. */
  private static final class VerifiedStream extends InputStream {
    private final InputStream input;
    private final long size, expectedCrc;
    private final String name;
    private final CRC32 crc = new CRC32();
    private long count;
    private boolean ended, closed;

    VerifiedStream(InputStream input, long size, long expectedCrc, String name) {
      this.input = input;
      this.size = size;
      this.expectedCrc = expectedCrc;
      this.name = name;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
      if (closed) throw new IOException("Virtual stream is closed");
      if (length == 0) return 0;
      if (ended) return -1;
      int read = input.read(bytes, offset, (int) Math.min(length, size - count + 1));
      if (read < 0) {
        ended = true;
        if (count != size || expectedCrc >= 0 && crc.getValue() != expectedCrc)
          throw reject("Virtual stream size/CRC mismatch: " + name);
      } else {
        count += read;
        if (count > size) throw reject("Virtual stream grew beyond declared size: " + name);
        crc.update(bytes, offset, read);
      }
      return read;
    }

    private IOException reject(String message) {
      var failure = new IOException(message);
      try {
        close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      return failure;
    }

    @Override
    public void close() throws IOException {
      if (!closed) {
        closed = true;
        input.close();
      }
    }
  }

  /** Host-side identity lookup in a mounted source without changing normal search/pure ordering. */
  public synchronized byte[] readFrom(Origin origin, VirtualPath path) throws IOException {
    ensureOpen();
    var source = sources.get(origin);
    var entry = source == null ? null : source.get(path);
    if (entry == null) throw new FileNotFoundException(path.value());
    return readEntry(entry, path);
  }

  private byte[] readEntry(Entry entry, VirtualPath path) throws IOException {
    if (entry.zip != null) {
      if (entry.member.getSize() > MAX_FILE_BYTES)
        throw new IOException("Virtual file exceeds 64 MiB buffered-read limit; use streaming");
      byte[] result;
      try (InputStream input = entry.zip.getInputStream(entry.member)) {
        result = boundedRead(input);
      }
      CRC32 crc = new CRC32();
      crc.update(result);
      if (result.length != entry.member.getSize() || crc.getValue() != entry.member.getCrc()) {
        throw new IOException("PK3 size/CRC mismatch: " + path.value());
      }
      packs.get(entry.origin).references |= referenceFlags(path.requested());
      return result;
    }
    if (!entry.loose.toRealPath().startsWith(entry.root)
        || !Files.isRegularFile(entry.loose, LinkOption.NOFOLLOW_LINKS)
        || Files.size(entry.loose) > MAX_FILE_BYTES) {
      throw new IOException(
          "Loose file escaped its root, changed type, or exceeds size limit: " + path.value());
    }
    try (InputStream input =
        Files.newInputStream(entry.loose, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      return boundedRead(input);
    }
  }

  private static byte[] boundedRead(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
    if (bytes.length > MAX_FILE_BYTES) throw new IOException("Virtual file exceeds 64 MiB limit");
    return bytes;
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Filesystem is closed");
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    for (ZipFile zip : archives) {
      try {
        zip.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    }
    archives.clear();
    sources.clear();
    packs.clear();
    files.clear();
    if (failure != null) throw failure;
  }
}
