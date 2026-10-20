package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CommandInbox;
import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.ConfigSource;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.chan.Protocol68ServerSession;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec;
import dev.bluevista.craftq3.engine.net.msg.CommandTokens;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectPacketCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessMessage;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessPacket;
import dev.bluevista.craftq3.engine.net.oob.Protocol68Datagram;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A protocol-68 server for session tests: its own console (owned by the runner's thread), {@code
 * map}/{@code map_restart}/{@code kick}/{@code killserver} and a few test commands, any number of
 * peers on any transports, one entity per bot in every snapshot at 20 Hz.
 */
final class FakeLocalServer implements LocalServer {
  /** Degradations the next published status reports (any thread). */
  volatile long degradations;

  static final int CHALLENGE = 0x1234567;

  /** Every factory-built server, newest last (tests inspect them). */
  static final List<FakeLocalServer> CREATED = new CopyOnWriteArrayList<>();

  static final Factory FACTORY =
      context -> {
        FakeLocalServer server = new FakeLocalServer(context);
        CREATED.add(server);
        return server;
      };

  private record Endpoint(PacketTransport transport, boolean local) {}

  private static final class Peer {
    final Endpoint endpoint;
    final SocketAddress address;
    final Protocol68ServerSession session;
    final int number;
    boolean entered;

    Peer(Endpoint endpoint, SocketAddress address, Protocol68ServerSession session, int number) {
      this.endpoint = endpoint;
      this.address = address;
      this.session = session;
      this.number = number;
    }
  }

  final Context context;
  final List<String> executed = new CopyOnWriteArrayList<>();
  final List<String> clientCommands = new CopyOnWriteArrayList<>();
  final List<Thread> frameThreads = new CopyOnWriteArrayList<>();
  volatile String gametypeAtSpawn = "";
  volatile boolean closed;
  volatile int peersConnected;
  volatile int maxPeers;
  private final ThreadOwner owner;
  private final Console console;
  private final List<Endpoint> endpoints = new ArrayList<>();
  private final List<Peer> peers = new ArrayList<>();
  private final byte[] buffer = new byte[Protocol68.MAX_MSGLEN + 1];
  private Phase phase = Phase.IDLE;
  private String map = "";
  private String dropReason = "";
  private int spawns, drops, sync, bots, serverId = 100, time = 1000;
  private long nextSnapshot;
  private long frames;
  private Status status = Status.INITIAL;

  private FakeLocalServer(Context context) {
    this.context = context;
    this.owner = context.owner();
    this.console = Console.server(owner, context.output(), ConfigSource.NONE, ConfigSink.NONE);
    CvarTable cvars = console.cvars();
    cvars.register("sv_hostname", "fake", 4);
    cvars.register("g_gametype", "0", 4);
    var commands = console.commands();
    for (String name : List.of("map", "devmap", "spmap", "spdevmap"))
      commands.register(name, args -> spawn(args.argc() > 1 ? args.argv(1) : ""));
    commands.register("map_restart", args -> broadcast("map_restart"));
    commands.register(
        "kick",
        args -> {
          broadcast("disconnect \"was kicked\"");
          sendAll();
          peers.clear();
        });
    commands.register("killserver", args -> shutdown("Server was killed"));
    commands.register("fake_drop", args -> drop("Fake game error"));
    commands.register(
        LocalServer.SYNC_COMMAND,
        args -> sync = args.argc() > 1 ? Integer.parseInt(args.argv(1)) : sync);
    commands.addFallback(
        args -> {
          executed.add(args.text().strip());
          if (args.name().equalsIgnoreCase("addbot")) bots++;
          return true;
        });
  }

  CvarTable cvars() {
    return console.cvars();
  }

  @Override
  public ThreadOwner owner() {
    return owner;
  }

  @Override
  public CommandInbox inbox() {
    return console.inbox();
  }

  @Override
  public void addTransport(PacketTransport transport, boolean local) {
    owner.check();
    endpoints.add(new Endpoint(transport, local));
  }

  private void spawn(String name) {
    if (name.equals("bad")) {
      context.output().print("Can't spawn " + name);
      return;
    }
    gametypeAtSpawn = console.cvars().string("g_gametype");
    map = name;
    spawns++;
    serverId++;
    bots = 0;
    phase = Phase.RUNNING;
    for (Peer peer : peers) {
      peer.session.queueGameState(gameState(peer.number), serverId);
      flush(peer);
    }
  }

  private ServerMessageCodec.GameState gameState(int client) {
    Map<Integer, String> strings = new TreeMap<>();
    strings.put(
        Protocol68.CS_SERVERINFO,
        "\\mapname\\"
            + map
            + "\\sv_hostname\\"
            + console.cvars().string("sv_hostname")
            + "\\g_gametype\\"
            + console.cvars().string("g_gametype"));
    strings.put(Protocol68.CS_SYSTEMINFO, "\\sv_serverid\\" + serverId + "\\sv_pure\\0");
    return new ServerMessageCodec.GameState(
        0, strings, SnapshotDeltaCodec.Baselines.EMPTY, client, 0);
  }

  private void broadcast(String text) {
    for (Peer peer : peers) peer.session.command(text);
  }

  private void drop(String reason) {
    broadcast("disconnect \"" + reason + "\"");
    sendAll();
    phase = Phase.DROPPED;
    drops++;
    dropReason = reason;
  }

  private void shutdown(String reason) {
    broadcast("disconnect \"" + reason + "\"");
    sendAll();
    peers.clear();
    phase = Phase.IDLE;
  }

  @Override
  public void frame(long nowMillis) {
    owner.check();
    if (closed) return;
    if (!frameThreads.contains(Thread.currentThread())) frameThreads.add(Thread.currentThread());
    console.frame();
    receive();
    if (phase == Phase.RUNNING && nowMillis >= nextSnapshot) {
      nextSnapshot = nowMillis + 50;
      time += 50;
      frames++;
      for (Peer peer : peers) {
        if (peer.session.gameMessageSequence() == 0) continue;
        byte[] player = new byte[Protocol68.PLAYER_STATE_BYTES];
        ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN).putInt(0, time);
        List<byte[]> entities = new ArrayList<>();
        for (int i = 0; i < peers.size() + bots; i++) {
          byte[] entity = new byte[Protocol68.ENTITY_STATE_BYTES];
          ByteBuffer.wrap(entity).order(ByteOrder.LITTLE_ENDIAN).putInt(0, i).putInt(8, time);
          entities.add(entity);
        }
        if (!peer.session.hasPendingPacket())
          peer.session.queueSnapshot(time, 0, new byte[0], player, entities);
        flush(peer);
      }
    }
    Set<String> names = new HashSet<>();
    for (var cvar : console.cvars().all())
      names.add(cvar.name().toLowerCase(java.util.Locale.ROOT));
    peersConnected = peers.size();
    maxPeers = Math.max(maxPeers, peers.size());
    status =
        new Status(
            phase,
            map,
            spawns,
            drops,
            dropReason,
            peers.size(),
            bots,
            degradations,
            frames,
            sync,
            names);
  }

  private void sendAll() {
    for (Peer peer : peers) {
      if (peer.session.hasPendingPacket()) {
        flush(peer);
        continue;
      }
      if (peer.session.reliableSequence() <= peer.session.reliableAcknowledge()) continue;
      peer.session.queueKeepalive();
      flush(peer);
    }
  }

  private void receive() {
    for (Endpoint endpoint : endpoints) {
      for (int guard = 0; guard < 1024; guard++) {
        int length;
        try {
          length = endpoint.transport.poll(buffer);
        } catch (IOException closedTransport) {
          break;
        }
        if (length == PacketTransport.NONE) break;
        if (length < 0) continue;
        byte[] datagram = java.util.Arrays.copyOf(buffer, length);
        SocketAddress from = endpoint.transport.lastSource();
        if (Protocol68Datagram.classify(datagram) == Protocol68Datagram.Kind.CONNECTIONLESS) {
          connectionless(endpoint, from, datagram);
          continue;
        }
        Peer peer = find(endpoint, from);
        if (peer == null) {
          send(endpoint, from, "disconnect");
          continue;
        }
        var received = peer.session.receive(datagram);
        if (received.status() != Protocol68ServerSession.Status.ACCEPTED) continue;
        for (var command : received.commands()) {
          clientCommands.add(command.text());
          if (command.text().startsWith("disconnect")) peers.remove(peer);
        }
      }
    }
  }

  private Peer find(Endpoint endpoint, SocketAddress from) {
    for (Peer peer : peers) if (peer.endpoint == endpoint && peer.address.equals(from)) return peer;
    return null;
  }

  private void connectionless(Endpoint endpoint, SocketAddress from, byte[] datagram) {
    if (phase != Phase.RUNNING) return;
    byte[] raw = datagram;
    String line = ConnectionlessMessage.parse(datagram).line();
    if (line.startsWith("connect")) {
      raw = ConnectPacketCodec.decompress(datagram);
      line = ConnectionlessMessage.parse(raw).line();
    }
    CommandTokens tokens = CommandTokens.tokenize(line);
    switch (tokens.argument(0)) {
      case "getchallenge" ->
          send(endpoint, from, "challengeResponse " + CHALLENGE + " " + tokens.argument(1) + " 68");
      case "connect" -> {
        Map<String, String> info = InfoString.parse(tokens.argument(1), Protocol68.MAX_INFO_STRING);
        Peer existing = find(endpoint, from);
        if (existing != null) peers.remove(existing);
        var session = new Protocol68ServerSession(CHALLENGE, Integer.parseInt(info.get("qport")));
        Peer peer = new Peer(endpoint, from, session, peers.size());
        peers.add(peer);
        send(endpoint, from, "connectResponse " + CHALLENGE);
        session.queueGameState(gameState(peer.number), serverId);
        flush(peer);
      }
      default -> {}
    }
  }

  private void send(Endpoint endpoint, SocketAddress to, String text) {
    byte[] packet = ConnectionlessPacket.text(text);
    try {
      endpoint.transport.send(to, packet, 0, packet.length);
    } catch (IOException ignored) {
      // Lost, as UDP would be.
    }
  }

  private void flush(Peer peer) {
    while (true) {
      var packet = peer.session.pollPacket();
      if (packet.isEmpty()) return;
      try {
        peer.endpoint.transport.send(peer.address, packet.get(), 0, packet.get().length);
      } catch (IOException ignored) {
        return;
      }
    }
  }

  @Override
  public Status status() {
    return status;
  }

  @Override
  public void close() {
    owner.check();
    if (closed) return;
    shutdown("Server shutdown");
    closed = true;
    for (Endpoint endpoint : endpoints) {
      try {
        endpoint.transport.close();
      } catch (IOException ignored) {
        // Closing.
      }
    }
    phase = Phase.CLOSED;
    status = new Status(phase, map, spawns, drops, dropReason, 0, 0, 0, frames, sync, Set.of());
  }
}
