package dev.bluevista.craftq3.engine.fs;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Pure-server state over one {@link SearchPath}: the server's pack checksum list, the checksum
 * feed, the resulting search order, and which packs have been referenced.
 *
 * <p>Semantics follow the original engine. Setting the server's packs ({@link #setServerPaks})
 * immediately hides every other PK3 and every loose file except configs, menus, game scripts,
 * {@code .dat} files and demos; the search order changes only on {@link #restart}, which moves the
 * server's packs to the front in the server's order and clears references. Clearing the server
 * packs restores the build order.
 *
 * <p>Thread ownership: the mutating methods belong to the one thread that owns the session's file
 * system (they are not synchronized). {@link #view()} is published through a volatile field and the
 * views it returns may be read from any thread; references they record are atomic.
 */
public final class PureManifest {
  public static final int GENERAL_REFERENCE = 1;
  public static final int UI_REFERENCE = 2;
  public static final int CGAME_REFERENCE = 4;

  /** Most server pack checksums accepted. */
  public static final int MAX_SERVER_PAKS = SearchPath.MAX_ARCHIVES;

  /** The original engine's string budget for the referenced-checksum reply. */
  public static final int MAX_REFERENCE_STRING = 8192;

  /** A pack as seen in the current order. */
  public record Pack(
      PackMount mount, String name, int checksum, int pureChecksum, int references) {}

  private final SearchPath searchPath;
  private final AtomicIntegerArray references;
  private final FileView.ReferenceSink sink;
  private List<Mount> order;
  private int[] serverPaks = new int[0];
  private boolean reordered;
  private int feed;
  private int[] pureChecksums;
  private volatile FileView view;

  public PureManifest(SearchPath searchPath) {
    this.searchPath = searchPath;
    this.references = new AtomicIntegerArray(searchPath.mounts().size());
    this.sink = (pack, flags) -> references.accumulateAndGet(pack.index(), flags, (a, b) -> a | b);
    this.order = searchPath.mounts();
    computePureChecksums();
    publish();
  }

  public SearchPath searchPath() {
    return searchPath;
  }

  /** The current view; reads through it record references. */
  public FileView view() {
    return view;
  }

  public int checksumFeed() {
    return feed;
  }

  /** True while a server pack list is in force. */
  public boolean pure() {
    return serverPaks.length > 0;
  }

  /**
   * Replaces the server's pack checksums ({@code sv_paks}); an empty list leaves pure mode. Takes
   * effect for lookups at once; the order changes at the next {@link #restart}.
   */
  public void setServerPaks(int... checksums) {
    if (checksums.length > MAX_SERVER_PAKS)
      throw new IllegalArgumentException("Too many server PK3 checksums: " + checksums.length);
    serverPaks = checksums.clone();
    if (serverPaks.length == 0 && reordered) {
      order = searchPath.mounts();
      clearReferences(0);
      reordered = false;
    }
    publish();
  }

  /** {@link #setServerPaks(int...)} for a parsed list. */
  public void setServerPaks(List<Integer> checksums) {
    if (checksums.size() > MAX_SERVER_PAKS)
      throw new IllegalArgumentException("Too many server PK3 checksums: " + checksums.size());
    setServerPaks(checksums.stream().mapToInt(Integer::intValue).toArray());
  }

  /**
   * Restarts the view with a new checksum feed: clears references and moves the server's packs to
   * the front in the server's order. Each listed checksum claims at most one pack; a checksum with
   * no matching pack is ignored. It does not rescan the installation.
   */
  public void restart(int checksumFeed) {
    feed = checksumFeed;
    computePureChecksums();
    clearReferences(0);
    var remaining = new ArrayList<>(searchPath.mounts());
    var next = new ArrayList<Mount>(remaining.size());
    reordered = false;
    for (int checksum : serverPaks) {
      for (int i = 0; i < remaining.size(); i++) {
        if (remaining.get(i) instanceof PackMount pack && pack.checksum() == checksum) {
          next.add(remaining.remove(i));
          reordered = true;
          break;
        }
      }
    }
    next.addAll(remaining);
    order = List.copyOf(next);
    publish();
  }

  /** Clears the given reference flags on every pack; 0 clears them all. */
  public void clearReferences(int flags) {
    int keep = flags == 0 ? 0 : ~flags;
    for (int i = 0; i < references.length(); i++)
      references.accumulateAndGet(i, keep, (a, b) -> a & b);
  }

  /** Every mounted pack in the current order, including packs the server hides. */
  public List<Pack> packs() {
    var result = new ArrayList<Pack>();
    for (Mount mount : order)
      if (mount instanceof PackMount pack)
        result.add(
            new Pack(
                pack,
                pack.name(),
                pack.checksum(),
                pureChecksums[pack.index()],
                references.get(pack.index())));
    return List.copyOf(result);
  }

  /**
   * The client's pure reply fields: the first cgame-referenced and first ui-referenced pack's pure
   * checksum, {@code @}, every generally referenced pack's pure checksum, then the feed XOR those
   * checksums XOR their count. The caller adds the command and server-id prefix.
   */
  public String referencedPureChecksums() {
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
    int combined = feed, count = 0;
    for (Pack pack : current) {
      if ((pack.references() & GENERAL_REFERENCE) != 0) {
        result.append(pack.pureChecksum()).append(' ');
        combined ^= pack.pureChecksum();
        count++;
      }
    }
    result.append(combined ^ count).append(' ');
    if (result.length() >= MAX_REFERENCE_STRING)
      throw new IllegalStateException("Referenced PK3 checksums exceed the engine string budget");
    return result.toString();
  }

  /** Server {@code sv_paks}: every pack's checksum in the current order. */
  public String loadedPakChecksums() {
    var result = new StringBuilder();
    for (Pack pack : packs()) result.append(pack.checksum()).append(' ');
    return result.toString();
  }

  /** Server {@code sv_pakNames}: every pack's base name in the current order. */
  public String loadedPakNames() {
    var result = new StringBuilder();
    for (Pack pack : packs()) {
      if (!result.isEmpty()) result.append(' ');
      result.append(pack.name());
    }
    return result.toString();
  }

  /**
   * Server {@code sv_referencedPaks}: packs that were referenced, plus every pack outside {@code
   * baseq3} (a mod's packs always count as referenced).
   */
  public String referencedPakChecksums() {
    var result = new StringBuilder();
    for (Pack pack : packs())
      if (referencedForDownload(pack)) result.append(pack.checksum()).append(' ');
    return result.toString();
  }

  /**
   * Server {@code sv_referencedPakNames}: {@code game/name} for {@link #referencedPakChecksums}.
   */
  public String referencedPakNames() {
    var result = new StringBuilder();
    for (Pack pack : packs()) {
      if (!referencedForDownload(pack)) continue;
      if (!result.isEmpty()) result.append(' ');
      result.append(pack.mount().game()).append('/').append(pack.name());
    }
    return result.toString();
  }

  private static boolean referencedForDownload(Pack pack) {
    return pack.references() != 0 || !pack.mount().game().startsWith(SearchPath.BASE_GAME);
  }

  private void computePureChecksums() {
    int[] sums = new int[searchPath.mounts().size()];
    for (PackMount pack : searchPath.packs())
      sums[pack.index()] = Pk3Checksums.pure(pack.archive().checksumCrcs(), feed);
    pureChecksums = sums;
  }

  private void publish() {
    Set<Mount> hidden = new HashSet<>();
    if (serverPaks.length > 0) {
      Set<Integer> allowed = new HashSet<>();
      for (int checksum : serverPaks) allowed.add(checksum);
      for (PackMount pack : searchPath.packs())
        if (!allowed.contains(pack.checksum())) hidden.add(pack);
    }
    view = FileView.of(searchPath, order, hidden, serverPaks.length > 0, sink);
  }

  /**
   * Whether a pure server still lets a loose file be read: {@code .cfg}, {@code .menu}, {@code
   * .game}, {@code .dat}, and demos whose {@code dm_} suffix parses (as C {@code atoi} would) to
   * protocol 66, 67, 68 or 71.
   */
  static boolean looseAllowed(String key) {
    if (key.endsWith(".cfg")
        || key.endsWith(".menu")
        || key.endsWith(".game")
        || key.endsWith(".dat")) return true;
    int dot = key.lastIndexOf('.');
    if (dot < 0) return false;
    String extension = key.substring(dot + 1);
    if (!extension.startsWith("dm_")) return false;
    int start = 3;
    while (start < extension.length() && extension.charAt(start) == ' ') start++;
    String suffix = extension.substring(start);
    int end = suffix.startsWith("+") || suffix.startsWith("-") ? 1 : 0;
    int first = end;
    while (end < suffix.length() && suffix.charAt(end) >= '0' && suffix.charAt(end) <= '9') end++;
    if (end == first) return false;
    // atoi uses the host strtol range, then the result keeps its low signed 32 bits.
    int protocol =
        new BigInteger(suffix.substring(0, end))
            .max(BigInteger.valueOf(Long.MIN_VALUE))
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .intValue();
    return protocol == 66 || protocol == 67 || protocol == 68 || protocol == 71;
  }

  /**
   * Reference flags for a read, from the caller's spelling (case-sensitive substring tests, as the
   * original engine): most files mark the pack generally referenced; {@code cgame.qvm} and {@code
   * ui.qvm} also mark module references.
   */
  static int referenceFlags(String requested) {
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
}
