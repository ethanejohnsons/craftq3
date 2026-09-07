package dev.bluevista.craftq3.client.net;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Native-observed browser ping policy. The single-threaded host owns transport, framing and
 * polling; this helper performs no DNS, network I/O, waits or background work.
 */
public final class BrowserPings {
  public static final int CAPACITY = 32;
  public static final int MAX_RESPONSE_BYTES = 16384;
  private static final int INFO_CAPACITY = 1024;
  private static final int[] SOURCES = {
    LanServerList.LOCAL, LanServerList.GLOBAL, LanServerList.FAVORITES
  };

  /** Outbound connectionless command text, without the four marker bytes or a terminating NUL. */
  public record Request(InetSocketAddress address, String command) {
    public Request {
      requireAddress(address);
      Objects.requireNonNull(command);
    }
  }

  /**
   * Zero milliseconds means pending; an expired pending request reports its current elapsed time.
   */
  public record Ping(InetSocketAddress address, int milliseconds) {}

  private static final class Slot {
    InetSocketAddress address;
    int start, time;
    String info = "";
  }

  private final LanServerList servers;
  private final IntSupplier clock, maxPing;
  private final String gameName;
  private final int protocol, legacyProtocol;
  private final Consumer<Request> outbound;
  private final Slot[] slots = new Slot[CAPACITY];
  private final ArrayDeque<InetSocketAddress> globalOverflow = new ArrayDeque<>();
  private int discoverySource;

  public BrowserPings(
      LanServerList servers,
      IntSupplier clock,
      IntSupplier maxPing,
      String gameName,
      int protocol,
      int legacyProtocol,
      Consumer<Request> outbound) {
    this.servers = Objects.requireNonNull(servers);
    this.clock = Objects.requireNonNull(clock);
    this.maxPing = Objects.requireNonNull(maxPing);
    this.gameName = Objects.requireNonNull(gameName);
    if (gameName.isEmpty()
        || gameName.length() > 255
        || gameName.chars().anyMatch(value -> value < 32 || value > 126 || value == '\\')
        || protocol < 1
        || legacyProtocol < 0)
      throw new IllegalArgumentException("Invalid browser protocol settings");
    this.protocol = protocol;
    this.legacyProtocol = legacyProtocol;
    this.outbound = Objects.requireNonNull(outbound);
    Arrays.setAll(slots, ignored -> new Slot());
  }

  /** Selects one native queue slot and emits exactly one request, even for a duplicate address. */
  public int request(InetSocketAddress address) {
    requireAddress(address);
    int now = clock.getAsInt(), selected = -1;
    for (int index = 0; index < slots.length; index++) {
      Slot slot = slots[index];
      if (slot.address == null || (slot.time == 0 && now - slot.start >= 500) || slot.time >= 500) {
        selected = index;
        break;
      }
    }
    if (selected < 0) {
      selected = 0;
      for (int index = 1; index < slots.length; index++)
        if (now - slots[index].start > now - slots[selected].start) selected = index;
    }
    start(slots[selected], address, now);
    updateServers(address, null, 0);
    return selected;
  }

  public int count() {
    int count = 0;
    for (Slot slot : slots) if (slot.address != null) count++;
    return count;
  }

  /** Native clear makes the slot unused but retains its previous info until a later reply. */
  public void clear(int index) {
    if (index >= 0 && index < slots.length) slots[index].address = null;
  }

  /** Polling updates matching LAN records; it does not clear an expired or completed slot. */
  public Optional<Ping> get(int index) {
    if (index < 0 || index >= slots.length || slots[index].address == null) return Optional.empty();
    Slot slot = slots[index];
    int time = slot.time;
    if (time == 0) {
      int elapsed = clock.getAsInt() - slot.start;
      time = elapsed < Math.max(100, maxPing.getAsInt()) ? 0 : elapsed;
    }
    updateServers(slot.address, slot.info, slot.time);
    return Optional.of(new Ping(slot.address, time));
  }

  public String info(int index) {
    return index < 0 || index >= slots.length || slots[index].address == null
        ? ""
        : slots[index].info;
  }

  /**
   * Occupancy without polling the clock or updating LAN records, for VM output-buffer semantics.
   */
  public boolean occupied(int index) {
    return index >= 0 && index < slots.length && slots[index].address != null;
  }

  /** Checks pending source identity without polling the clock or modifying any browser state. */
  public boolean expects(InetSocketAddress address) {
    if (address == null || address.isUnresolved() || address.getPort() == 0) return false;
    for (Slot slot : slots)
      if (slot.address != null && slot.time == 0 && sameAddress(slot.address, address)) return true;
    return false;
  }

  /** Selects which discovery operation may accept unsolicited info responses. */
  public void discoverySource(int source) {
    discoverySource = source;
  }

  public int discoverySource() {
    return discoverySource;
  }

  /**
   * Additional master results are consumed from the end when a visible global row has ping zero.
   */
  public void setGlobalOverflow(List<InetSocketAddress> addresses) {
    Objects.requireNonNull(addresses);
    if (addresses.size() > LanServerList.GLOBAL_CAPACITY)
      throw new IllegalArgumentException("Too many additional browser addresses");
    addresses.forEach(BrowserPings::requireAddress);
    globalOverflow.clear();
    globalOverflow.addAll(addresses);
  }

  public List<InetSocketAddress> globalOverflow() {
    return List.copyOf(globalOverflow);
  }

  /**
   * Consume bytes following the infoResponse command line. Native string decoding replaces '%' and
   * high bytes with '.', stops at NUL/1023 bytes, and retains newlines. Returns true only when a
   * pending slot or a new local discovery record accepted the response. Challenge is not checked.
   */
  public boolean receiveInfo(InetSocketAddress address, byte[] rawInfo) {
    requireAddress(address);
    Objects.requireNonNull(rawInfo);
    if (rawInfo.length > MAX_RESPONSE_BYTES)
      throw new IllegalArgumentException("Browser response exceeds datagram bound");
    String info = decode(rawInfo);
    int peerProtocol = integer(field(info, "protocol"));
    if (peerProtocol != protocol && (legacyProtocol == 0 || peerProtocol != legacyProtocol))
      return false;
    String peerGame = field(info, "gamename");
    if (!peerGame.isEmpty() && !peerGame.equals(gameName)) return false;
    for (Slot slot : slots) {
      if (slot.address != null && slot.time == 0 && sameAddress(slot.address, address)) {
        slot.time = clock.getAsInt() - slot.start;
        String withoutType = removeFirst(info, "nettype");
        String augmented =
            "\\nettype\\" + (address.getAddress().getAddress().length == 4 ? 1 : 2) + withoutType;
        slot.info = augmented.length() < INFO_CAPACITY ? augmented : withoutType;
        updateServers(address, info, slot.time);
        return true;
      }
    }
    if (discoverySource != LanServerList.LOCAL) return false;
    for (int index = 0; index < servers.capacity(LanServerList.LOCAL); index++) {
      var current = servers.entry(LanServerList.LOCAL, index).orElseThrow();
      if (current.address() == null || current.address().getPort() == 0) {
        servers.updateEntry(LanServerList.LOCAL, index, initialized(address, current.visible()));
        servers.setCount(LanServerList.LOCAL, index + 1);
        return true;
      }
      if (sameAddress(address, current.address())) return false;
    }
    return false;
  }

  /**
   * Fill available slots from visible ping==-1 rows, then poll and clear nonzero results. Slots
   * released by that polling become available on the next call. At most 32 requests are emitted.
   * Unlike LAN storage accessors, source1 is not a global alias for this operation.
   */
  public boolean updateVisible(int source) {
    if (source < LanServerList.LOCAL || source > LanServerList.FAVORITES) return false;
    discoverySource = source;
    if (source != LanServerList.LOCAL
        && source != LanServerList.GLOBAL
        && source != LanServerList.FAVORITES) return false;
    int count = count();
    boolean active = count != 0;
    if (count < slots.length) {
      for (int index = 0; index < servers.count(source); index++) {
        var entry = servers.entry(source, index).orElseThrow();
        if (entry.visible() == 0) continue;
        if (entry.ping() == -1) {
          // An unresolved discovery placeholder has no endpoint for the host transport yet.
          if (entry.address() == null || entry.address().getPort() == 0) continue;
          if (count >= slots.length) break;
          boolean pending = false;
          for (Slot slot : slots)
            if (slot.address != null && sameAddress(slot.address, entry.address())) {
              pending = true;
              break;
            }
          if (pending) continue;
          for (Slot slot : slots) {
            if (slot.address == null) {
              start(slot, entry.address(), clock.getAsInt());
              count++;
              active = true;
              break;
            }
          }
        } else if (source == LanServerList.GLOBAL
            && entry.ping() == 0
            && !globalOverflow.isEmpty()) {
          servers.updateEntry(
              source, index, initialized(globalOverflow.removeLast(), entry.visible()));
        }
      }
    }
    for (int index = 0; index < slots.length; index++) {
      var ping = get(index);
      if (ping.isPresent() && ping.orElseThrow().milliseconds() != 0) clear(index);
    }
    return active;
  }

  private void start(Slot slot, InetSocketAddress address, int now) {
    slot.address = address;
    slot.start = now;
    slot.time = 0;
    outbound.accept(new Request(address, "getinfo xxx"));
  }

  private void updateServers(InetSocketAddress address, String info, int ping) {
    for (int source : SOURCES) {
      for (int index = 0; index < servers.capacity(source); index++) {
        var entry = servers.entry(source, index).orElseThrow();
        if (!sameAddress(address, entry.address())) continue;
        servers.updateEntry(
            source,
            index,
            info == null
                ? entry.withPing(ping)
                : new LanServerList.Entry(
                    entry.address(),
                    field(info, "hostname"),
                    field(info, "mapname"),
                    field(info, "game"),
                    integer(field(info, "nettype")),
                    integer(field(info, "gametype")),
                    integer(field(info, "clients")),
                    integer(field(info, "sv_maxclients")),
                    integer(field(info, "minping")),
                    integer(field(info, "maxping")),
                    ping,
                    entry.visible(),
                    integer(field(info, "punkbuster")),
                    integer(field(info, "g_humanplayers")),
                    integer(field(info, "g_needpass"))));
      }
    }
  }

  private static LanServerList.Entry initialized(InetSocketAddress address, int visible) {
    return new LanServerList.Entry(address, "", "", "", 0, 0, 0, 0, 0, 0, -1, visible, 0, 0, 0);
  }

  private static String decode(byte[] bytes) {
    var text = new StringBuilder(Math.min(INFO_CAPACITY - 1, bytes.length));
    for (int index = 0;
        index < bytes.length && text.length() < INFO_CAPACITY - 1 && bytes[index] != 0;
        index++) {
      int value = bytes[index] & 255;
      text.append(value > 127 || value == '%' ? '.' : (char) value);
    }
    return text.toString();
  }

  private static String field(String info, String wanted) {
    int cursor = info.startsWith("\\") ? 1 : 0;
    while (cursor < info.length()) {
      int separator = info.indexOf('\\', cursor);
      if (separator < 0) return "";
      String key = info.substring(cursor, separator);
      int end = info.indexOf('\\', separator + 1);
      if (end < 0) end = info.length();
      if (key.equalsIgnoreCase(wanted)) return info.substring(separator + 1, end);
      cursor = end + 1;
    }
    return "";
  }

  private static String removeFirst(String info, String wanted) {
    int begin = 0;
    while (begin < info.length()) {
      int key = info.charAt(begin) == '\\' ? begin + 1 : begin;
      int separator = info.indexOf('\\', key);
      if (separator < 0) return info;
      int end = info.indexOf('\\', separator + 1);
      if (end < 0) end = info.length();
      if (info.substring(key, separator).equalsIgnoreCase(wanted))
        return info.substring(0, begin) + info.substring(end);
      begin = end;
    }
    return info;
  }

  private static int integer(String text) {
    int begin = 0;
    while (begin < text.length() && " \t\r\n\f\u000b".indexOf(text.charAt(begin)) >= 0) begin++;
    int end = begin;
    if (end < text.length() && (text.charAt(end) == '-' || text.charAt(end) == '+')) end++;
    int digits = end;
    while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') end++;
    if (digits == end) return 0;
    return new BigInteger(text.substring(begin, end))
        .max(BigInteger.valueOf(Long.MIN_VALUE))
        .min(BigInteger.valueOf(Long.MAX_VALUE))
        .intValue();
  }

  private static boolean sameAddress(InetSocketAddress first, InetSocketAddress second) {
    return first != null
        && second != null
        && first.getPort() == second.getPort()
        && Arrays.equals(first.getAddress().getAddress(), second.getAddress().getAddress());
  }

  private static void requireAddress(InetSocketAddress address) {
    if (address == null || address.isUnresolved() || address.getPort() == 0)
      throw new IllegalArgumentException(
          "Browser peer must have a resolved address and nonzero port");
  }
}
