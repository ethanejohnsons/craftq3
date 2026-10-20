package dev.bluevista.craftq3.client.net;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-owned original UI server records. This class sends no packets and performs no DNS or
 * discovery.
 */
public final class LanServerList {
  public static final int LOCAL = 0;
  public static final int MPLAYER = 1;
  public static final int GLOBAL = 2;
  public static final int FAVORITES = 3;
  public static final int OTHER_CAPACITY = 128;
  public static final int GLOBAL_CAPACITY = 4096;

  /** Fixed native text capacities exclude their terminating NUL; address null is an unused slot. */
  public record Entry(
      InetSocketAddress address,
      String hostName,
      String mapName,
      String game,
      int netType,
      int gameType,
      int clients,
      int maxClients,
      int minPing,
      int maxPing,
      int ping,
      int visible,
      int punkbuster,
      int humanPlayers,
      int needPass) {
    public Entry {
      if (address != null) requireResolved(address);
      hostName = text(hostName, 79);
      mapName = text(mapName, 31);
      game = text(game, 31);
    }

    public static Entry empty() {
      return new Entry(null, "", "", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public Entry withPing(int value) {
      return new Entry(
          address,
          hostName,
          mapName,
          game,
          netType,
          gameType,
          clients,
          maxClients,
          minPing,
          maxPing,
          value,
          visible,
          punkbuster,
          humanPlayers,
          needPass);
    }

    public Entry withVisible(int value) {
      return new Entry(
          address,
          hostName,
          mapName,
          game,
          netType,
          gameType,
          clients,
          maxClients,
          minPing,
          maxPing,
          ping,
          value,
          punkbuster,
          humanPlayers,
          needPass);
    }
  }

  private static final class Records {
    final Entry[] entries;
    final long[] identities;
    int count;

    Records(int capacity) {
      entries = new Entry[capacity];
      identities = new long[capacity];
      Arrays.fill(entries, Entry.empty());
    }
  }

  private final Records local = new Records(OTHER_CAPACITY);
  private final Records global = new Records(GLOBAL_CAPACITY);
  private final Records favorites = new Records(OTHER_CAPACITY);
  private long nextIdentity;

  private Records records(int source) {
    return switch (source) {
      case LOCAL -> local;
      case MPLAYER, GLOBAL -> global;
      case FAVORITES -> favorites;
      default -> null;
    };
  }

  public int capacity(int source) {
    Records records = records(source);
    return records == null ? 0 : records.entries.length;
  }

  public int count(int source) {
    Records records = records(source);
    return records == null ? 0 : records.count;
  }

  /** Discovery may publish -1 while awaiting the global list. Existing slots are retained. */
  public void setCount(int source, int value) {
    Records records = records(source);
    if (records == null || value < (records == global ? -1 : 0) || value > records.entries.length)
      throw new IllegalArgumentException("Invalid LAN server count");
    records.count = value;
  }

  /** Getters address full storage capacity, including retained slots beyond the active count. */
  public Optional<Entry> entry(int source, int index) {
    Records records = records(source);
    return records == null || index < 0 || index >= records.entries.length
        ? Optional.empty()
        : Optional.of(records.entries[index]);
  }

  /** Host-only slot identity for asynchronous work; zero means invalid or never allocated. */
  public long identity(int source, int index) {
    Records records = records(source);
    return records == null || index < 0 || index >= records.entries.length
        ? 0
        : records.identities[index];
  }

  private long newIdentity() {
    nextIdentity = Math.incrementExact(nextIdentity);
    return nextIdentity;
  }

  /** Replace an immutable record without changing the discovery count. */
  public void updateEntry(int source, int index, Entry value) {
    Records records = records(source);
    if (records == null || index < 0 || index >= records.entries.length)
      throw new IllegalArgumentException("Invalid LAN server slot");
    Objects.requireNonNull(value);
    Entry previous = records.entries[index];
    if (!Objects.equals(previous.address, value.address)
        || (value.address == null && !previous.hostName.equals(value.hostName)))
      records.identities[index] = newIdentity();
    records.entries[index] = value;
  }

  public String address(int source, int index) {
    return entry(source, index).map(Entry::address).map(LanServerList::formatAddress).orElse("");
  }

  public int ping(int source, int index) {
    return entry(source, index).map(Entry::ping).orElse(-1);
  }

  public int visible(int source, int index) {
    return entry(source, index).map(Entry::visible).orElse(0);
  }

  public void markVisible(int source, int index, int value) {
    Records records = records(source);
    if (records == null) return;
    if (index == -1) {
      for (int i = 0; i < records.entries.length; i++)
        records.entries[i] = records.entries[i].withVisible(value);
    } else if (index >= 0 && index < records.entries.length)
      records.entries[index] = records.entries[index].withVisible(value);
  }

  public void resetPings(int source) {
    Records records = records(source);
    if (records == null) return;
    for (int i = 0; i < records.entries.length; i++)
      records.entries[i] = records.entries[i].withPing(-1);
  }

  /**
   * Returns 1 for an addition, 0 for a duplicate, or -1 for invalid/full/pending storage. A null
   * address records an inert native NA_BAD placeholder and is never a duplicate.
   */
  public int add(int source, String name, InetSocketAddress address) {
    Records records = records(source);
    if (records == null || records.count < 0 || records.count >= records.entries.length) return -1;
    if (address != null) requireResolved(address);
    String storedName = text(name, 79);
    for (int i = 0; i < records.count; i++)
      if (address != null && address.equals(records.entries[i].address)) return 0;
    Entry previous = records.entries[records.count];
    records.identities[records.count] = newIdentity();
    records.entries[records.count++] =
        new Entry(
            address,
            storedName,
            previous.mapName,
            previous.game,
            previous.netType,
            previous.gameType,
            previous.clients,
            previous.maxClients,
            previous.minPing,
            previous.maxPing,
            previous.ping,
            1,
            previous.punkbuster,
            previous.humanPlayers,
            previous.needPass);
    return 1;
  }

  /** Removal shifts complete records; the old final slot remains available to future additions. */
  public void remove(int source, InetSocketAddress address) {
    Records records = records(source);
    if (records == null || records.count <= 0 || address == null) return;
    requireResolved(address);
    for (int i = 0; i < records.count; i++) {
      if (address.equals(records.entries[i].address)) {
        System.arraycopy(records.entries, i + 1, records.entries, i, records.count - i - 1);
        System.arraycopy(records.identities, i + 1, records.identities, i, records.count - i - 1);
        records.count--;
        records.identities[records.count] = 0;
        return;
      }
    }
  }

  public int compare(int source, int key, int direction, int first, int second) {
    Entry a = entry(source, first).orElse(null);
    Entry b = entry(source, second).orElse(null);
    if (a == null || b == null) return 0;
    int result =
        switch (key) {
          case 0 -> compareText(a.hostName, b.hostName);
          case 1 -> compareText(a.mapName, b.mapName);
          case 2 ->
              a.clients == b.clients
                  ? Integer.compare(a.maxClients, b.maxClients)
                  : Integer.compare(a.clients, b.clients);
          case 3 -> Integer.compare(a.gameType, b.gameType);
          case 4 -> Integer.compare(a.ping, b.ping);
          default -> 0;
        };
    return direction == 0 ? result : -result;
  }

  public String info(int source, int index) {
    Entry e = entry(source, index).orElse(null);
    if (e == null) return "";
    StringBuilder info = new StringBuilder(512);
    field(info, "g_humanplayers", e.humanPlayers);
    field(info, "g_needpass", e.needPass);
    field(info, "punkbuster", e.punkbuster);
    field(info, "addr", e.address == null ? "" : formatAddress(e.address));
    field(info, "nettype", e.netType);
    field(info, "gametype", e.gameType);
    field(info, "game", e.game);
    field(info, "maxping", e.maxPing);
    field(info, "minping", e.minPing);
    field(info, "ping", e.ping);
    field(info, "sv_maxclients", e.maxClients);
    field(info, "clients", e.clients);
    field(info, "mapname", e.mapName);
    field(info, "hostname", e.hostName);
    return info.toString();
  }

  private static void field(StringBuilder out, String key, int value) {
    field(out, key, Integer.toString(value));
  }

  private static void field(StringBuilder out, String key, String value) {
    if (value.isEmpty()
        || value.indexOf('\\') >= 0
        || value.indexOf(';') >= 0
        || value.indexOf('"') >= 0) return;
    out.append('\\').append(key).append('\\').append(value);
  }

  private static int compareText(String first, String second) {
    for (int i = 0; i <= Math.min(first.length(), second.length()); i++) {
      int a = i == first.length() ? 0 : (byte) first.charAt(i);
      int b = i == second.length() ? 0 : (byte) second.charAt(i);
      if (a >= 'a' && a <= 'z') a -= 'a' - 'A';
      if (b >= 'a' && b <= 'z') b -= 'a' - 'A';
      if (a != b) return Integer.compare(a, b);
      if (a == 0) return 0;
    }
    return 0;
  }

  private static String text(String value, int maximum) {
    Objects.requireNonNull(value);
    if (value.length() > 8192) throw new IllegalArgumentException("LAN text exceeds input bound");
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) == 0 || value.charAt(i) > 255)
        throw new IllegalArgumentException("LAN text must be a native byte string");
    return value.substring(0, Math.min(maximum, value.length()));
  }

  private static void requireResolved(InetSocketAddress address) {
    if (address == null || address.isUnresolved())
      throw new IllegalArgumentException("LAN server address must already be resolved");
  }

  /** Numeric address formatting performs no name resolution. */
  public static String formatAddress(InetSocketAddress address) {
    requireResolved(address);
    byte[] bytes = address.getAddress().getAddress();
    if (bytes.length == 4) return ipv4(bytes, 0) + ":" + address.getPort();
    int[] groups = new int[8];
    for (int i = 0; i < groups.length; i++)
      groups[i] = (bytes[i * 2] & 255) << 8 | bytes[i * 2 + 1] & 255;
    String host;
    if (Arrays.stream(groups, 0, 5).allMatch(n -> n == 0) && groups[5] == 65535)
      host = "::ffff:" + ipv4(bytes, 12);
    else if (Arrays.stream(groups, 0, 6).allMatch(n -> n == 0) && groups[6] != 0)
      host = "::" + ipv4(bytes, 12);
    else {
      int bestStart = -1, bestLength = 1;
      for (int i = 0; i < 8; ) {
        if (groups[i] != 0) {
          i++;
          continue;
        }
        int end = i + 1;
        while (end < 8 && groups[end] == 0) end++;
        if (end - i > bestLength) {
          bestStart = i;
          bestLength = end - i;
        }
        i = end;
      }
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < 8; ) {
        if (i == bestStart) {
          result.append("::");
          i += bestLength;
        } else {
          if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') result.append(':');
          result.append(Integer.toHexString(groups[i++]));
        }
      }
      host = result.toString();
    }
    if (address.getAddress() instanceof Inet6Address v6
        && v6.getScopeId() != 0
        && (v6.isLinkLocalAddress() || v6.isMCNodeLocal() || v6.isMCLinkLocal())) {
      try {
        NetworkInterface network = NetworkInterface.getByIndex(v6.getScopeId());
        host = network == null ? "" : host + "%" + network.getName();
      } catch (SocketException unavailable) {
        host = "";
      }
    }
    return "[" + host + "]:" + address.getPort();
  }

  private static String ipv4(byte[] bytes, int offset) {
    return (bytes[offset] & 255)
        + "."
        + (bytes[offset + 1] & 255)
        + "."
        + (bytes[offset + 2] & 255)
        + "."
        + (bytes[offset + 3] & 255);
  }
}
