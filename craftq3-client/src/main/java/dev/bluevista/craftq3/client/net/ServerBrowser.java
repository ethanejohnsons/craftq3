package dev.bluevista.craftq3.client.net;

import dev.bluevista.craftq3.client.UiBrowser;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.fs.WritableFiles;
import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import dev.bluevista.craftq3.core.net.MasterServerResponse;
import dev.bluevista.craftq3.platform.net.DiscoveryTransport;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Session-owned browser networking for the original UI. VM callbacks only queue bounded work; pump
 * performs nonblocking I/O and publishes completed name lookups on the engine thread.
 */
public final class ServerBrowser implements UiBrowser, AutoCloseable {
  private static final int MAX_PENDING = 128;
  private static final int RESPONSE_WINDOW = 10000;
  private static final int MASTER_PORT = 27950;
  private static final String GAME_NAME = "Quake3Arena";
  private final LanServerList servers = new LanServerList();
  private final BrowserPings pings;
  private final BrowserStatus statuses;
  private final BrowserResolver resolver;
  private final CvarSystem cvars;
  private final WritableFiles saved;
  private final Consumer<String> output;
  private final IntSupplier clock;
  private final SocketFactory sockets;
  private final Map<Boolean, Wire> wires = new LinkedHashMap<>();
  private final ArrayDeque<Outgoing> outgoing = new ArrayDeque<>();
  private final Map<String, Pending> pending = new LinkedHashMap<>();
  private final Map<InetSocketAddress, Integer> consoleStatuses = new LinkedHashMap<>();
  private final Map<InetSocketAddress, Integer> masters = new LinkedHashMap<>();
  private int localStarted, globalStarted, globalEpoch;
  private boolean localDiscovery, globalDiscovery, closed;

  interface Wire extends AutoCloseable {
    boolean send(InetSocketAddress address, byte[] packet) throws IOException;

    DiscoveryTransport.Poll poll() throws IOException;

    @Override
    void close() throws IOException;
  }

  interface SocketFactory {
    Wire open(boolean ipv6) throws IOException;
  }

  private record Outgoing(InetSocketAddress address, byte[] packet, int started) {}

  private record Pending(
      String address, int port, int started, Consumer<InetSocketAddress> action) {}

  public ServerBrowser(CvarSystem cvars, WritableFiles saved, Consumer<String> output) {
    this(
        cvars,
        saved,
        output,
        () -> (int) (System.nanoTime() / 1_000_000),
        new BrowserResolver(),
        ServerBrowser::openWire);
  }

  ServerBrowser(
      CvarSystem cvars,
      WritableFiles saved,
      Consumer<String> output,
      IntSupplier clock,
      BrowserResolver resolver,
      SocketFactory sockets) {
    this.cvars = java.util.Objects.requireNonNull(cvars);
    this.saved = saved;
    this.output = java.util.Objects.requireNonNull(output);
    this.clock = java.util.Objects.requireNonNull(clock);
    this.resolver = java.util.Objects.requireNonNull(resolver);
    this.sockets = java.util.Objects.requireNonNull(sockets);
    cvars.register("cl_maxPing", "800", CvarSystem.ARCHIVE);
    cvars.register("cl_serverStatusResendTime", "750", 0);
    // Public Q3 master fallback; additional masters remain user-configurable.
    cvars.register("sv_master1", "master.quake3arena.com", CvarSystem.ARCHIVE);
    for (int i = 2; i <= 5; i++) cvars.register("sv_master" + i, "", CvarSystem.ARCHIVE);
    // CraftQ3's game connection implements protocol 68; menu refreshes discover that protocol.
    cvars.register("protocol", "68", CvarSystem.ROM);
    pings =
        new BrowserPings(
            servers,
            clock,
            () -> cvars.integer("cl_maxPing"),
            GAME_NAME,
            71,
            68,
            request -> enqueue(request.address(), ConnectionlessPacket.text(request.command())));
    pings.discoverySource(-1);
    statuses = new BrowserStatus(this::enqueue);
    loadCache();
  }

  private static Wire openWire(boolean ipv6) throws IOException {
    var transport =
        DiscoveryTransport.open(
            ipv6 ? DiscoveryTransport.Family.IPV6 : DiscoveryTransport.Family.IPV4, !ipv6);
    return new Wire() {
      public boolean send(InetSocketAddress address, byte[] packet) throws IOException {
        return transport.send(address, packet);
      }

      public DiscoveryTransport.Poll poll() throws IOException {
        return transport.poll();
      }

      public void close() throws IOException {
        transport.close();
      }
    };
  }

  /** Register on each session command buffer; no network access happens merely by registering. */
  public void registerCommands(CommandSystem commands) {
    commands.register("localservers", command -> discoverLocal());
    commands.register("globalservers", this::globalCommand);
    commands.register(
        "ping",
        command -> {
          if (command.arguments().size() != 2) {
            output.accept("ping <server>");
            return;
          }
          defer(
              "ping:" + command.argument(1),
              command.argument(1),
              RemoteAddress.DEFAULT_PORT,
              pings::request);
        });
    commands.register(
        "serverstatus",
        command -> {
          if (command.arguments().size() != 2) {
            output.accept("serverstatus <server>");
            return;
          }
          defer(
              "status:" + command.argument(1),
              command.argument(1),
              RemoteAddress.DEFAULT_PORT,
              peer -> {
                if (consoleStatuses.size() >= BrowserStatus.MAX_REQUESTS) return;
                consoleStatuses.put(peer, clock.getAsInt());
                statuses.query(peer, BrowserStatus.TEXT_CAPACITY, clock.getAsInt(), statusResend());
              });
        });
  }

  private int statusResend() {
    return Math.max(0, cvars.integer("cl_serverStatusResendTime"));
  }

  private void globalCommand(CommandParser.Command command) {
    if (command.arguments().size() < 3) {
      output.accept("globalservers <master 0..5> <protocol> [filters]");
      return;
    }
    try {
      int master = Integer.parseInt(command.argument(1));
      int protocol = Integer.parseInt(command.argument(2));
      if (master < 0 || master > 5 || protocol < 1) throw new IllegalArgumentException();
      var targets = new ArrayList<String>();
      for (int i = master == 0 ? 1 : master; i <= (master == 0 ? 5 : master); i++) {
        String address = cvars.string("sv_master" + i);
        if (!address.isEmpty()) targets.add(address);
      }
      discoverGlobal(targets, protocol, command.arguments().subList(3, command.arguments().size()));
    } catch (IllegalArgumentException invalid) {
      output.accept("Invalid global server query");
    }
  }

  /** Explicit user refresh. IPv4 LAN broadcast covers the standard four game ports. */
  public void discoverLocal() {
    var targets = new ArrayList<InetSocketAddress>();
    for (int port = RemoteAddress.DEFAULT_PORT; port < RemoteAddress.DEFAULT_PORT + 4; port++)
      targets.add(new InetSocketAddress(InetAddress.ofLiteral("255.255.255.255"), port));
    discoverLocal(targets);
  }

  /** Host-selected targets also permit private loopback LAN audits without a network broadcast. */
  public void discoverLocal(List<InetSocketAddress> targets) {
    ensureOpen();
    if (targets.size() > 16 || targets.stream().anyMatch(peer -> !usable(peer)))
      throw new IllegalArgumentException("Invalid local discovery targets");
    for (int i = 0; i < servers.capacity(LanServerList.LOCAL); i++) {
      int visible = servers.visible(LanServerList.LOCAL, i);
      servers.updateEntry(LanServerList.LOCAL, i, LanServerList.Entry.empty().withVisible(visible));
    }
    servers.setCount(LanServerList.LOCAL, 0);
    pings.discoverySource(LanServerList.LOCAL);
    localDiscovery = true;
    localStarted = clock.getAsInt();
    for (int repeat = 0; repeat < 2; repeat++)
      for (var target : targets) enqueue(target, ConnectionlessPacket.text("getinfo xxx"));
  }

  /** Explicit host/UI request; at most five masters and bounded printable filter tokens. */
  public void discoverGlobal(List<String> targets, int protocol, List<String> filters) {
    ensureOpen();
    if (targets.size() > 5
        || protocol < 1
        || filters.size() > 32
        || filters.stream()
            .anyMatch(
                s ->
                    s.isEmpty()
                        || s.length() > 128
                        || s.chars().anyMatch(c -> c <= 32 || c >= 127 || c == '"' || c == '\\')))
      throw new IllegalArgumentException("Invalid master query");
    pending.keySet().removeIf(key -> key.startsWith("master:"));
    outgoing.removeIf(
        request -> starts(ConnectionlessPacket.payload(request.packet()), "getservers"));
    globalEpoch++;
    int epoch = globalEpoch;
    masters.clear();
    pings.setGlobalOverflow(List.of());
    servers.setCount(LanServerList.GLOBAL, targets.isEmpty() ? 0 : -1);
    globalStarted = clock.getAsInt();
    globalDiscovery = !targets.isEmpty();
    pings.discoverySource(LanServerList.GLOBAL);
    String suffix = filters.isEmpty() ? "" : " " + String.join(" ", filters);
    for (String target : targets) {
      defer(
          "master:" + target,
          target,
          MASTER_PORT,
          peer -> {
            if (epoch != globalEpoch) return;
            masters.put(peer, clock.getAsInt());
            String query =
                peer.getAddress() instanceof Inet6Address
                    ? "getserversExt " + GAME_NAME + " " + protocol + suffix
                    : "getservers " + protocol + suffix;
            enqueue(peer, ConnectionlessPacket.text(query));
          });
    }
  }

  /** Bounded per-frame work, including while the original menu is visible. */
  public void pump() {
    if (closed) return;
    int now = clock.getAsInt();
    var completed = new ArrayList<Runnable>();
    var iterator = pending.entrySet().iterator();
    while (iterator.hasNext()) {
      Pending request = iterator.next().getValue();
      var peer = resolver.resolve(request.address(), request.port());
      if (peer.isPresent()) {
        iterator.remove();
        if (usable(peer.orElseThrow()))
          completed.add(() -> request.action().accept(peer.orElseThrow()));
      } else if (now - request.started() >= 15000) {
        iterator.remove();
        output.accept("Server address lookup timed out: " + request.address());
      }
    }
    completed.forEach(Runnable::run);
    if (localDiscovery && now - localStarted >= RESPONSE_WINDOW) localDiscovery = false;
    if (globalDiscovery && now - globalStarted >= RESPONSE_WINDOW) {
      globalDiscovery = false;
      if (servers.count(LanServerList.GLOBAL) < 0) servers.setCount(LanServerList.GLOBAL, 0);
    }
    masters.entrySet().removeIf(entry -> now - entry.getValue() >= RESPONSE_WINDOW);
    for (int i = 0, count = outgoing.size(); i < count; i++) {
      var request = outgoing.removeFirst();
      if (now - request.started() >= 1000) continue;
      boolean ipv6 = request.address().getAddress() instanceof Inet6Address;
      try {
        Wire wire = wires.get(ipv6);
        if (wire == null) {
          wire = sockets.open(ipv6);
          wires.put(ipv6, wire);
        }
        if (!wire.send(request.address(), request.packet())) outgoing.addLast(request);
      } catch (IOException | IllegalArgumentException failure) {
        output.accept("Browser send failed: " + failure.getMessage());
      }
    }
    for (var wire : wires.values()) {
      for (int i = 0; i < 64; i++) {
        try {
          var poll = wire.poll();
          if (poll.status() == DiscoveryTransport.Status.EMPTY) break;
          if (poll.status() == DiscoveryTransport.Status.PACKET)
            receive(poll.source(), poll.payload());
        } catch (IOException failure) {
          output.accept("Browser receive failed: " + failure.getMessage());
          break;
        }
      }
    }
    pollConsoleStatuses(now);
  }

  private void pollConsoleStatuses(int now) {
    var iterator = consoleStatuses.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var result = statuses.query(entry.getKey(), BrowserStatus.TEXT_CAPACITY, now, statusResend());
      if (result.isPresent()) {
        output.accept(
            "Server status "
                + LanServerList.formatAddress(entry.getKey())
                + "\n"
                + result.orElseThrow().replace('\\', '\n'));
        iterator.remove();
      } else if (now - entry.getValue() >= RESPONSE_WINDOW) {
        statuses.reset(entry.getKey());
        iterator.remove();
        output.accept("Server status timed out: " + LanServerList.formatAddress(entry.getKey()));
      }
    }
  }

  private void receive(InetSocketAddress source, byte[] packet) {
    if (!usable(source) || !ConnectionlessPacket.isConnectionless(packet)) return;
    try {
      byte[] data = ConnectionlessPacket.payload(packet);
      if (starts(data, "getservers")) {
        if (!masters.containsKey(source)) return;
        var response =
            MasterServerResponse.parse(
                packet, source.getAddress() instanceof Inet6Address v6 ? v6.getScopeId() : 0);
        if (servers.count(LanServerList.GLOBAL) < 0) servers.setCount(LanServerList.GLOBAL, 0);
        var overflow = new ArrayList<>(pings.globalOverflow());
        for (var endpoint : response.endpoints()) {
          var peer = endpoint.socketAddress();
          if (!usable(peer)) continue;
          int count = servers.count(LanServerList.GLOBAL);
          if (count >= servers.capacity(LanServerList.GLOBAL)) {
            if (overflow.size() < LanServerList.GLOBAL_CAPACITY) overflow.add(peer);
          } else {
            int visible = servers.visible(LanServerList.GLOBAL, count);
            if (servers.add(LanServerList.GLOBAL, "", peer) == 1)
              servers.updateEntry(LanServerList.GLOBAL, count, blank(peer, visible));
          }
        }
        pings.setGlobalOverflow(overflow);
      } else if (starts(data, "infoResponse\n")) {
        if (localDiscovery || pings.expects(source))
          pings.receiveInfo(source, Arrays.copyOfRange(data, 13, data.length));
      } else if (starts(data, "statusResponse\n")) {
        statuses.receive(source, Arrays.copyOfRange(data, 15, data.length));
      }
    } catch (IllegalArgumentException malformed) {
      // Untrusted datagrams are discarded. Their content is never an engine command or a path.
    }
  }

  private static boolean starts(byte[] data, String prefix) {
    byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII);
    if (data.length < expected.length) return false;
    for (int i = 0; i < expected.length; i++) if (data[i] != expected[i]) return false;
    return true;
  }

  private static LanServerList.Entry blank(InetSocketAddress peer, int visible) {
    return new LanServerList.Entry(peer, "", "", "", 0, 0, 0, 0, 0, 0, -1, visible, 0, 0, 0);
  }

  private static boolean usable(InetSocketAddress peer) {
    return peer != null
        && !peer.isUnresolved()
        && peer.getPort() > 0
        && !peer.getAddress().isAnyLocalAddress()
        && !peer.getAddress().isMulticastAddress();
  }

  private void enqueue(InetSocketAddress peer, byte[] packet) {
    if (closed || !usable(peer) || outgoing.size() >= MAX_PENDING) return;
    outgoing.addLast(new Outgoing(peer, packet.clone(), clock.getAsInt()));
  }

  private void defer(String key, String address, int port, Consumer<InetSocketAddress> action) {
    if (closed) return;
    try {
      var peer = resolver.resolve(address, port);
      if (peer.isPresent()) {
        if (usable(peer.orElseThrow())) action.accept(peer.orElseThrow());
      } else if (pending.size() < MAX_PENDING)
        pending.putIfAbsent(key, new Pending(address, port, clock.getAsInt(), action));
    } catch (IllegalArgumentException invalid) {
      output.accept("Invalid browser server address: " + address);
    }
  }

  public int capacity(int source) {
    return servers.capacity(source);
  }

  public int count(int source) {
    return servers.count(source);
  }

  public String address(int source, int index) {
    return servers.address(source, index);
  }

  public String info(int source, int index) {
    return servers.info(source, index);
  }

  public int serverPing(int source, int index) {
    return servers.ping(source, index);
  }

  public int visible(int source, int index) {
    return servers.visible(source, index);
  }

  public void markVisible(int source, int index, int value) {
    servers.markVisible(source, index, value);
  }

  public void resetPings(int source) {
    servers.resetPings(source);
  }

  public int compare(int source, int key, int direction, int first, int second) {
    return servers.compare(source, key, direction, first, second);
  }

  public int add(int source, String name, String address) {
    ensureOpen();
    Optional<InetSocketAddress> peer;
    try {
      peer = resolver.resolve(address, RemoteAddress.DEFAULT_PORT);
    } catch (IllegalArgumentException invalid) {
      return servers.add(source, name, null);
    }
    int index = servers.count(source);
    int result = servers.add(source, name, peer.orElse(null));
    if (result == 1 && peer.isEmpty()) {
      long identity = servers.identity(source, index);
      defer(
          "add:" + source + ":" + identity,
          address,
          RemoteAddress.DEFAULT_PORT,
          resolved -> {
            for (int i = 0; i < servers.count(source); i++) {
              if (servers.identity(source, i) == identity) {
                var placeholder = servers.entry(source, i).orElseThrow();
                servers.updateEntry(
                    source,
                    i,
                    new LanServerList.Entry(
                        resolved,
                        placeholder.hostName(),
                        placeholder.mapName(),
                        placeholder.game(),
                        placeholder.netType(),
                        placeholder.gameType(),
                        placeholder.clients(),
                        placeholder.maxClients(),
                        placeholder.minPing(),
                        placeholder.maxPing(),
                        placeholder.ping(),
                        placeholder.visible(),
                        placeholder.punkbuster(),
                        placeholder.humanPlayers(),
                        placeholder.needPass()));
                break;
              }
            }
          });
    }
    return result;
  }

  public void remove(int source, String address) {
    try {
      resolver
          .resolve(address, RemoteAddress.DEFAULT_PORT)
          .ifPresent(peer -> servers.remove(source, peer));
    } catch (IllegalArgumentException invalid) {
      /* Native bad addresses cannot match an entry. */
    }
  }

  public int pingCount() {
    return pings.count();
  }

  public void clearPing(int index) {
    pings.clear(index);
  }

  public boolean pingOccupied(int index) {
    return pings.occupied(index);
  }

  public Ping ping(int index) {
    return pings
        .get(index)
        .map(value -> new Ping(LanServerList.formatAddress(value.address()), value.milliseconds()))
        .orElseGet(() -> new Ping("", 0));
  }

  public String pingInfo(int index) {
    return pings.info(index);
  }

  public boolean updatePings(int source) {
    return !closed && pings.updateVisible(source);
  }

  public Optional<String> status(String address, int capacity) {
    if (closed) return Optional.empty();
    try {
      return resolver
          .resolve(address, RemoteAddress.DEFAULT_PORT)
          .filter(ServerBrowser::usable)
          .flatMap(
              peer ->
                  statuses.query(
                      peer,
                      Math.min(capacity, BrowserStatus.TEXT_CAPACITY),
                      clock.getAsInt(),
                      statusResend()));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  public void resetStatus(String address) {
    if (address == null) statuses.resetAll();
    else {
      try {
        resolver.resolve(address, RemoteAddress.DEFAULT_PORT).ifPresent(statuses::reset);
      } catch (IllegalArgumentException invalid) {
        /* Nothing to reset for invalid input. */
      }
    }
  }

  public void loadCache() {
    if (saved == null) return;
    try {
      var bytes = saved.read(new VirtualPath(BrowserCache.FILE_NAME));
      if (bytes.isPresent()) BrowserCache.restore(servers, bytes.orElseThrow());
    } catch (IOException | IllegalArgumentException invalid) {
      output.accept("Could not load browser cache: " + invalid.getMessage());
    }
  }

  public void saveCache() {
    if (saved == null) return;
    try {
      saved.write(new VirtualPath(BrowserCache.FILE_NAME), BrowserCache.encode(servers));
    } catch (IOException | IllegalArgumentException failure) {
      output.accept("Could not save browser cache: " + failure.getMessage());
    }
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Server browser closed");
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    saveCache();
    resolver.close();
    pending.clear();
    outgoing.clear();
    masters.clear();
    consoleStatuses.clear();
    statuses.resetAll();
    IOException failure = null;
    for (var wire : wires.values()) {
      try {
        wire.close();
      } catch (IOException problem) {
        if (failure == null) failure = problem;
        else failure.addSuppressed(problem);
      }
    }
    wires.clear();
    if (failure != null) throw failure;
  }
}
