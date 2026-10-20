package dev.bluevista.craftq3.engine.game.net;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandParser;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.fs.PureManifest;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.snapshot.ClientSnapshot;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.chan.DownloadSender;
import dev.bluevista.craftq3.engine.net.chan.Protocol68ServerSession;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.msg.ClientMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectPacketCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessMessage;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessPacket;
import dev.bluevista.craftq3.engine.net.oob.Protocol68Datagram;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * The protocol-68 network endpoint of a {@link GameServer} ({@code sv_client.c}, {@code sv_main.c}
 * connectionless handling, {@code sv_snapshot.c} transmission) over {@code engine:net} transports.
 *
 * <ul>
 *   <li>Connectionless: {@code getchallenge}, {@code connect} (Huffman-compressed userinfo,
 *       protocol and challenge checks, qport, private slots, reconnects), {@code getinfo}, {@code
 *       getstatus} and {@code rcon} ({@link RconAuthenticator}), rate-limited per address.
 *   <li>Connections: one {@link Protocol68ServerSession} per client for netchan, reliable commands
 *       and delta snapshots; gamestates are (re)sent when a client is on an older level and has
 *       acknowledged past the last gamestate; snapshots go out at the client's {@code snaps} and
 *       {@code rate} (every frame for loopback); usercmds enter the world and run the game; {@code
 *       cp}/{@code vdr} implement pure admission ({@link PureVerifier}); {@code download} serves
 *       referenced non-official PK3s over the UDP download protocol when {@code sv_allowDownload}
 *       allows it.
 *   <li>Dropped clients linger for {@code sv_zombietime} to deliver their final reliable commands;
 *       when the level ends every connection gets its {@code disconnect}.
 *   <li>The optional master heartbeat ({@link MasterHeartbeat}) is off by default.
 * </ul>
 *
 * Local play connects through a {@link LoopbackTransport} from {@link #openLoopback()} and the same
 * handshake as a remote client. Owned by the simulation thread, like its server.
 */
public final class Protocol68Host implements GameServer.Listener, AutoCloseable {
  /** {@code MAX_CHALLENGES}. */
  public static final int MAX_CHALLENGES = 1024;

  /** Connectionless packets accepted per address and second. */
  public static final int OOB_PER_ADDRESS = 10;

  /** Connectionless packets accepted per second in total. */
  public static final int OOB_TOTAL = 100;

  /** {@code SV_OUTPUTBUF_LENGTH}: rcon output is sent in chunks of this size. */
  public static final int RCON_CHUNK = 1024 - 16;

  /** Download blocks queued per frame for a remote client. */
  public static final int DOWNLOAD_BLOCKS_PER_FRAME = 8;

  private static final Log LOG = Log.get(Protocol68Host.class);
  private static final int HEADER_RATE_BYTES = 48;
  private static final int GT_SINGLE_PLAYER = 2;

  private static final class Challenge {
    final Object base;
    final SocketAddress address;
    final int value;
    long time;
    boolean connected;

    Challenge(Object base, SocketAddress address, int value, long time) {
      this.base = base;
      this.address = address;
      this.value = value;
      this.time = time;
    }
  }

  private record Endpoint(PacketTransport transport, boolean local) {}

  private final GameServer server;
  private final List<Endpoint> endpoints = new ArrayList<>();
  private final NetPeer[] peers = new NetPeer[Protocol68.MAX_CLIENTS];
  private final List<NetPeer> lingering = new ArrayList<>();
  private final List<Challenge> challenges = new ArrayList<>();
  private final Map<Object, ArrayDeque<Long>> oobByAddress = new LinkedHashMap<>();
  private final ArrayDeque<Long> oobTotal = new ArrayDeque<>();
  private final RconAuthenticator rcon = new RconAuthenticator();
  private final MasterHeartbeat heartbeat;
  private final Random random;
  private final byte[] buffer = new byte[Protocol68.MAX_MSGLEN + 1];
  private long now;
  private boolean closed;

  /**
   * A host for {@code server} with no transports yet ({@link #addTransport}, {@link
   * #openLoopback}).
   */
  public Protocol68Host(GameServer server) {
    this(server, MasterHeartbeat::resolve, new Random());
  }

  /**
   * @param resolver resolves {@code sv_master} names for the heartbeat
   * @param random draws challenges
   */
  public Protocol68Host(
      GameServer server, Function<String, Optional<SocketAddress>> resolver, Random random) {
    this.server = Objects.requireNonNull(server);
    this.heartbeat = new MasterHeartbeat(resolver);
    this.random = Objects.requireNonNull(random);
    server.addListener(this);
    server.console().commands().register("heartbeat", args -> heartbeat.force());
  }

  public GameServer server() {
    return server;
  }

  /** Serves clients arriving on {@code transport}; {@code local} marks loopback-like links. */
  public void addTransport(PacketTransport transport, boolean local) {
    endpoints.add(new Endpoint(Objects.requireNonNull(transport), local));
  }

  /**
   * Opens an in-process link for a local player: the returned client end speaks the same protocol
   * (challenge, connect, netchan) as a remote client, without rate limits.
   */
  public LoopbackTransport openLoopback() {
    LoopbackTransport.Pair pair = LoopbackTransport.pair();
    addTransport(pair.server(), true);
    return pair.client();
  }

  /** The network client in slot {@code client}, if one is connected through this host. */
  public boolean hasPeer(int client) {
    return client >= 0 && client < peers.length && peers[client] != null;
  }

  /** Connected (not lingering) network clients. */
  public int peerCount() {
    int count = 0;
    for (NetPeer peer : peers) if (peer != null) count++;
    return count;
  }

  // ---- frame ------------------------------------------------------------------------------------

  /**
   * One host frame at {@code nowMillis}: reads every waiting datagram, advances the server, then
   * transmits to every connection that is due and sends a heartbeat when one is due.
   */
  public void frame(long nowMillis) {
    if (closed) return;
    now = nowMillis;
    receiveAll();
    int frames = server.frame(nowMillis);
    now = nowMillis;
    if (frames > 0) sendClientMessages();
    serviceLingering();
    if (server.state() == GameServer.State.RUNNING) sendHeartbeats();
  }

  private void receiveAll() {
    for (Endpoint endpoint : List.copyOf(endpoints)) {
      for (int guard = 0; guard < 4096; guard++) {
        int length;
        try {
          if (!endpoint.transport.isOpen()) break;
          length = endpoint.transport.poll(buffer);
        } catch (IOException failure) {
          LOG.warnOnce("poll", "Server transport failed: " + failure.getMessage());
          break;
        }
        if (length == PacketTransport.NONE) break;
        if (length == PacketTransport.OVERSIZE || length <= 0) continue;
        byte[] packet = java.util.Arrays.copyOf(buffer, length);
        SocketAddress from = endpoint.transport.lastSource();
        if (from == null) continue;
        try {
          packet(endpoint, from, packet);
        } catch (RuntimeException failure) {
          LOG.warnOnce("packet", "Ignoring a malformed packet: " + failure);
        }
      }
    }
  }

  private static Object base(SocketAddress address) {
    return address instanceof InetSocketAddress inet && inet.getAddress() != null
        ? inet.getAddress()
        : address;
  }

  private static String addressString(SocketAddress address, boolean local) {
    if (local) return "localhost";
    if (address instanceof InetSocketAddress inet && inet.getAddress() != null)
      return inet.getAddress().getHostAddress() + ":" + inet.getPort();
    return address.toString();
  }

  private void packet(Endpoint endpoint, SocketAddress from, byte[] data) {
    Protocol68Datagram.Kind kind = Protocol68Datagram.classify(data);
    if (kind == Protocol68Datagram.Kind.CONNECTIONLESS) {
      if (server.state() != GameServer.State.RUNNING) return;
      if (!endpoint.local && !permit(base(from))) return;
      connectionless(endpoint, from, data);
      return;
    }
    if (kind != Protocol68Datagram.Kind.SEQUENCED || data.length < 6) return;
    int qport = (data[4] & 255) | (data[5] & 255) << 8;
    NetPeer peer = find(endpoint.transport, base(from), qport);
    if (peer == null) {
      if (server.state() == GameServer.State.RUNNING && (endpoint.local || permit(base(from))))
        send(endpoint.transport, from, ConnectionlessPacket.text("disconnect"));
      return;
    }
    if (!peer.address.equals(from)) peer.address = from;
    sequenced(peer, data);
  }

  private NetPeer find(PacketTransport transport, Object base, int qport) {
    for (NetPeer peer : peers)
      if (peer != null
          && peer.transport == transport
          && peer.qport == qport
          && peer.baseAddress.equals(base)) return peer;
    for (NetPeer peer : lingering)
      if (peer.transport == transport && peer.qport == qport && peer.baseAddress.equals(base))
        return peer;
    return null;
  }

  private boolean permit(Object address) {
    while (!oobTotal.isEmpty() && now - oobTotal.peekFirst() >= 1000) oobTotal.removeFirst();
    if (oobTotal.size() >= OOB_TOTAL) return false;
    ArrayDeque<Long> times = oobByAddress.computeIfAbsent(address, ignored -> new ArrayDeque<>());
    while (!times.isEmpty() && now - times.peekFirst() >= 1000) times.removeFirst();
    if (times.size() >= OOB_PER_ADDRESS) return false;
    times.addLast(now);
    oobTotal.addLast(now);
    if (oobByAddress.size() > MAX_CHALLENGES) {
      Iterator<Object> oldest = oobByAddress.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
    return true;
  }

  // ---- connectionless ---------------------------------------------------------------------------

  private void connectionless(Endpoint endpoint, SocketAddress from, byte[] data) {
    byte[] raw = data;
    if (data.length >= ConnectPacketCodec.PREFIX_LENGTH
        && new String(data, 4, 8, StandardCharsets.ISO_8859_1).equals("connect ")) {
      try {
        raw = ConnectPacketCodec.decompress(data);
      } catch (IllegalArgumentException malformed) {
        return;
      }
    }
    String line;
    try {
      line = ConnectionlessMessage.parse(raw).line();
    } catch (IllegalArgumentException malformed) {
      return;
    }
    CommandArgs args = CommandParser.tokenize(line);
    switch (args.argv(0).toLowerCase(Locale.ROOT)) {
      case "getstatus" -> status(endpoint, from, args.argv(1));
      case "getinfo" -> info(endpoint, from, args.argv(1));
      case "getchallenge" -> challenge(endpoint, from, args);
      case "connect" -> connect(endpoint, from, args);
      case "rcon" -> remoteCommand(endpoint, from, line);
      default -> LOG.debug("Bad connectionless packet from " + from + ": " + args.argv(0));
    }
  }

  private void print(Endpoint endpoint, SocketAddress to, String text) {
    send(endpoint.transport, to, ConnectionlessPacket.text("print\n" + text));
  }

  private void send(PacketTransport transport, SocketAddress to, byte[] data) {
    try {
      transport.send(to, data, 0, data.length);
    } catch (IOException | IllegalArgumentException failure) {
      LOG.debug("Cannot send to " + to + ": " + failure.getMessage());
    }
  }

  private void challenge(Endpoint endpoint, SocketAddress from, CommandArgs args) {
    if (!endpoint.local && server.cvars().integer("g_gametype") == GT_SINGLE_PLAYER) return;
    Object base = base(from);
    Challenge found = null;
    for (Challenge challenge : challenges)
      if (!challenge.connected && challenge.address.equals(from)) found = challenge;
    if (found == null) {
      found = new Challenge(base, from, (random.nextInt() & 0x7fffffff) ^ server.serverTime(), now);
      if (challenges.size() >= MAX_CHALLENGES) {
        Challenge oldest = challenges.getFirst();
        for (Challenge challenge : challenges) if (challenge.time < oldest.time) oldest = challenge;
        challenges.remove(oldest);
      }
      challenges.add(found);
    }
    found.time = now;
    String reply = "challengeResponse " + found.value;
    if (args.argc() > 1) reply += " " + args.argv(1) + " " + Protocol68.PROTOCOL_VERSION;
    send(endpoint.transport, from, ConnectionlessPacket.text(reply));
  }

  private void connect(Endpoint endpoint, SocketAddress from, CommandArgs args) {
    String userinfo = args.argv(1);
    if (userinfo.length() >= Protocol68.MAX_INFO_STRING) {
      print(endpoint, from, "Userinfo string length exceeded.\n");
      return;
    }
    if (parse(infoValue(userinfo, "protocol"), -1) != Protocol68.PROTOCOL_VERSION) {
      print(endpoint, from, "Server uses protocol version " + Protocol68.PROTOCOL_VERSION + ".\n");
      return;
    }
    int challengeValue = parse(infoValue(userinfo, "challenge"), 0);
    int qport = parse(infoValue(userinfo, "qport"), -1) & 0xffff;
    Object base = base(from);
    CvarTable cvars = server.cvars();
    NetPeer existing = null;
    for (NetPeer peer : peers)
      if (peer != null
          && peer.transport == endpoint.transport
          && peer.baseAddress.equals(base)
          && (peer.qport == qport || peer.address.equals(from))) existing = peer;
    if (existing != null) {
      ServerClient slot = server.client(existing.client);
      int limit = 1000 * Math.max(0, cvars.integer("sv_reconnectlimit"));
      if (server.serverTime() - slot.lastPacketTime() < limit
          && existing.challenge == challengeValue) {
        // A retransmitted connect for the connection we already accepted.
        send(endpoint.transport, from, ConnectionlessPacket.text("connectResponse"));
        return;
      }
    }
    if (!endpoint.local) {
      Challenge match = null;
      for (Challenge challenge : challenges)
        if (challenge.base.equals(base) && challenge.value == challengeValue) match = challenge;
      if (match == null) {
        print(endpoint, from, "No or bad challenge for address.\n");
        return;
      }
      match.connected = true;
    }
    int reuse = existing == null ? -1 : existing.client;
    if (existing != null) {
      peers[existing.client] = null;
      existing.stopDownload();
    }
    GameServer.Admission admission =
        server.connect(userinfo, addressString(from, endpoint.local), endpoint.local, reuse);
    if (!admission.accepted()) {
      print(endpoint, from, admission.denied() + "\n");
      return;
    }
    NetPeer peer =
        new NetPeer(
            endpoint.transport,
            base,
            from,
            qport,
            challengeValue,
            admission.client(),
            endpoint.local);
    peers[admission.client()] = peer;
    send(endpoint.transport, from, ConnectionlessPacket.text("connectResponse"));
    LOG.info(
        "Client " + admission.client() + " connected from " + addressString(from, endpoint.local));
  }

  private static String infoValue(String info, String key) {
    int i = info.startsWith("\\") ? 1 : 0;
    String[] fields = info.substring(i).split("\\\\", -1);
    for (int f = 0; f + 1 < fields.length; f += 2)
      if (fields[f].equalsIgnoreCase(key)) return fields[f + 1];
    return "";
  }

  private static int parse(String text, int fallback) {
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException invalid) {
      return fallback;
    }
  }

  private int clientsConnected(boolean countPrivate) {
    int start = countPrivate ? 0 : Math.max(0, server.cvars().integer("sv_privateClients"));
    int count = 0;
    for (int i = start; i < server.maxClients(); i++)
      if (server.client(i).state().compareTo(ServerClient.State.CONNECTED) >= 0) count++;
    return count;
  }

  private void info(Endpoint endpoint, SocketAddress from, String challenge) {
    CvarTable cvars = server.cvars();
    if (cvars.integer("g_gametype") == GT_SINGLE_PLAYER && !endpoint.local) return;
    Map<String, String> info = new LinkedHashMap<>();
    info.put("challenge", challenge);
    info.put("protocol", Integer.toString(Protocol68.PROTOCOL_VERSION));
    info.put("hostname", cvars.string("sv_hostname"));
    info.put("mapname", cvars.string("mapname"));
    info.put("clients", Integer.toString(clientsConnected(false)));
    info.put(
        "sv_maxclients",
        Integer.toString(server.maxClients() - Math.max(0, cvars.integer("sv_privateClients"))));
    info.put("gametype", Integer.toString(cvars.integer("g_gametype")));
    info.put("pure", Integer.toString(pureActive() ? 1 : 0));
    if (cvars.integer("sv_minPing") != 0) info.put("minPing", cvars.string("sv_minPing"));
    if (cvars.integer("sv_maxPing") != 0) info.put("maxPing", cvars.string("sv_maxPing"));
    String game = cvars.string("fs_game");
    if (!game.isEmpty()) info.put("game", game);
    StringBuilder text = new StringBuilder("infoResponse\n");
    info.forEach(
        (key, value) -> {
          if (!value.isEmpty() && value.indexOf('\\') < 0 && value.indexOf('"') < 0)
            text.append('\\').append(key).append('\\').append(value);
        });
    send(endpoint.transport, from, ConnectionlessPacket.text(text.toString()));
  }

  private void status(Endpoint endpoint, SocketAddress from, String challenge) {
    CvarTable cvars = server.cvars();
    if (cvars.integer("g_gametype") == GT_SINGLE_PLAYER && !endpoint.local) return;
    StringBuilder text = new StringBuilder("statusResponse\n");
    text.append(cvars.info(CvarFlags.SERVERINFO));
    if (!challenge.isEmpty() && challenge.indexOf('\\') < 0 && challenge.indexOf('"') < 0)
      text.append("\\challenge\\").append(challenge);
    text.append('\n');
    for (int i = 0; i < server.maxClients(); i++) {
      ServerClient client = server.client(i);
      if (client.state().compareTo(ServerClient.State.CONNECTED) < 0) continue;
      int score = server.entities().isPresent() ? server.score(i) : 0;
      String row = score + " " + client.ping() + " \"" + client.name().replace('"', ' ') + "\"\n";
      if (text.length() + row.length() >= Protocol68.MAX_MSGLEN - 16) break;
      text.append(row);
    }
    send(endpoint.transport, from, ConnectionlessPacket.text(text.toString()));
  }

  private void remoteCommand(Endpoint endpoint, SocketAddress from, String line) {
    RconAuthenticator.Request request = RconAuthenticator.parse(line);
    RconAuthenticator.Result result =
        rcon.check(server.cvars().string("rconPassword"), request.password(), now);
    if (result == RconAuthenticator.Result.RATE_LIMITED) return;
    String output;
    switch (result) {
      case NO_PASSWORD -> output = "No rconpassword set on the server.\n";
      case BAD_PASSWORD -> {
        LOG.info("Bad rcon from " + from);
        output = "Bad rconpassword.\n";
      }
      default -> {
        LOG.info("Rcon from " + from + ": " + request.command());
        output = server.executeRedirected(request.command());
      }
    }
    if (output.isEmpty()) return;
    for (int start = 0; start < output.length(); start += RCON_CHUNK)
      print(endpoint, from, output.substring(start, Math.min(output.length(), start + RCON_CHUNK)));
  }

  // ---- connections ------------------------------------------------------------------------------

  private boolean pureActive() {
    return server.pure().isPresent() && server.cvars().integer("sv_pure") != 0;
  }

  private void sequenced(NetPeer peer, byte[] data) {
    Protocol68ServerSession.Received received = peer.session.receive(data);
    if (peer.closing) return;
    ServerClient slot = server.client(peer.client);
    switch (received.status()) {
      case REJECTED -> {
        server.drop(peer.client, "Illegible client message: " + received.diagnostic());
        return;
      }
      case WRONG_GAME -> {
        server.packetReceived(peer.client);
        acknowledge(peer, slot);
        if (peer.restartPending || peer.download != null) return;
        if (peer.session.messageAcknowledge() > peer.gamestateMessageNum) sendGamestate(peer);
        return;
      }
      case ACCEPTED -> {}
      default -> {
        return;
      }
    }
    server.packetReceived(peer.client);
    peer.restartPending = false;
    acknowledge(peer, slot);
    for (ClientMessageCodec.Command command : received.commands()) {
      clientCommand(peer, command.text());
      if (peers[peer.client] != peer || slot.state() == ServerClient.State.ZOMBIE) return;
    }
    if (received.message() != null && received.message().movement() != null) {
      peer.acknowledged(peer.session.messageAcknowledge(), server.serverTime());
      server.setPing(peer.client, peer.ping());
      userMove(peer, slot, received.userCommands());
    }
  }

  private void acknowledge(NetPeer peer, ServerClient slot) {
    slot.acknowledgeReliable(peer.session.reliableAcknowledge() + peer.sequenceOffset);
  }

  private void userMove(NetPeer peer, ServerClient slot, List<byte[]> commands) {
    if (commands.isEmpty() || peer.download != null) return;
    if (pureActive() && !peer.pureAuthentic && !peer.gotCp) {
      if (slot.state() == ServerClient.State.ACTIVE) sendGamestate(peer);
      return;
    }
    if (pureActive() && !peer.pureAuthentic && slot.state() == ServerClient.State.PRIMED) {
      server.drop(peer.client, "Cannot validate pure client!");
      return;
    }
    List<UserCmd> parsed = new ArrayList<>(commands.size());
    for (byte[] command : commands) parsed.add(UserCmd.fromCanonical(command));
    server.userMove(peer.client, parsed);
  }

  private void clientCommand(NetPeer peer, String text) {
    CommandArgs args = CommandParser.tokenize(text);
    switch (args.argv(0).toLowerCase(Locale.ROOT)) {
      case "cp" -> {
        if (!pureActive()) return;
        PureManifest manifest = server.pure().orElseThrow();
        PureVerifier.Result result =
            PureVerifier.verify(args, manifest, server.restartedServerId(), server.checksumFeed());
        if (result == PureVerifier.Result.IGNORED) return;
        peer.gotCp = true;
        peer.pureAuthentic = result == PureVerifier.Result.ACCEPTED;
        if (!peer.pureAuthentic)
          server.drop(peer.client, "Unpure client detected. Invalid .PK3 files referenced!");
      }
      case "vdr" -> {
        peer.pureAuthentic = false;
        peer.gotCp = false;
      }
      case "download" -> beginDownload(peer, args.argv(1));
      case "nextdl" -> {
        if (peer.download == null) return;
        try {
          peer.download.acknowledge(parse(args.argv(1), -1), now);
        } catch (IOException broken) {
          server.drop(peer.client, "Broken download: " + broken.getMessage());
        }
      }
      case "stopdl" -> peer.stopDownload();
      case "donedl" -> {
        peer.stopDownload();
        sendGamestate(peer);
      }
      default -> server.clientCommand(peer.client, text, true);
    }
  }

  private void beginDownload(NetPeer peer, String requested) {
    peer.stopDownload();
    peer.downloadName = requested;
    try {
      int allow = server.cvars().integer("sv_allowDownload");
      if ((allow & 1) == 0)
        throw new IOException(
            "Could not download because autodownloading is disabled on the server.");
      PureManifest manifest =
          server.pure().orElseThrow(() -> new IOException("Server has no PK3 files to offer."));
      String name = requested.replace('\\', '/');
      String[] parts = name.split("/", -1);
      if (parts.length != 2 || !parts[1].toLowerCase(Locale.ROOT).endsWith(".pk3"))
        throw new IOException("File " + requested + " is not a referenced PK3 archive.");
      String stem = parts[1].substring(0, parts[1].length() - 4);
      String lower = stem.toLowerCase(Locale.ROOT);
      if ((parts[0].equalsIgnoreCase("baseq3") && lower.matches("pak[0-8]"))
          || (parts[0].equalsIgnoreCase("missionpack") && lower.matches("pak[0-3]")))
        throw new IOException("Cannot autodownload official pk3 file " + requested + ".");
      Set<String> referenced =
          Set.of(
              server.cvars().string("sv_referencedPakNames").toLowerCase(Locale.ROOT).split(" "));
      PureManifest.Pack pack = null;
      for (PureManifest.Pack candidate : manifest.packs())
        if (candidate.mount().game().equalsIgnoreCase(parts[0])
            && candidate.name().equalsIgnoreCase(stem)
            && referenced.contains((parts[0] + "/" + stem).toLowerCase(Locale.ROOT)))
          pack = candidate;
      if (pack == null)
        throw new IOException("File " + requested + " is not referenced by this server.");
      var file = pack.mount().archive().file();
      long size = Files.size(file);
      if (size <= 0 || size > DownloadSender.MAX_FILE_BYTES)
        throw new IOException("File " + requested + " exceeds the download limit.");
      InputStream input = new BufferedInputStream(Files.newInputStream(file));
      peer.download = new DownloadSender(input, (int) size);
    } catch (IOException | RuntimeException refused) {
      peer.download = null;
      peer.downloadError =
          refused.getMessage() == null ? "Download refused." : refused.getMessage();
    }
  }

  private void sendGamestate(NetPeer peer) {
    forward(peer);
    drain(peer);
    if (peer.session.hasPendingPacket()) return;
    server.gamestateSent(peer.client);
    peer.gotCp = false;
    peer.pureAuthentic = false;
    peer.gamestateMessageNum = peer.session.nextMessageSequence();
    List<byte[]> baselines = server.baselines();
    peer.session.queueGameState(
        new ServerMessageCodec.GameState(
            0,
            server.configstrings(),
            new Baselines(baselines),
            peer.client,
            server.checksumFeed()),
        server.serverId());
    drain(peer);
    server.client(peer.client).scheduleSnapshot(server.serverTime());
  }

  /** Hands the slot's new reliable commands to the connection. */
  private void forward(NetPeer peer) {
    ServerClient slot = server.client(peer.client);
    while (peer.forwardCursor <= slot.reliableSequence()) {
      String text = slot.reliableCommand(peer.forwardCursor);
      try {
        if (text == null)
          throw new IllegalArgumentException("Reliable command fell out of the window");
        peer.session.command(text);
      } catch (IllegalStateException full) {
        return;
      } catch (IllegalArgumentException invalid) {
        LOG.warnOnce(
            "reliable:" + peer.client, "Skipping reliable command: " + invalid.getMessage());
        peer.sequenceOffset++;
      }
      peer.forwardCursor++;
    }
  }

  private void sendClientMessages() {
    for (NetPeer peer : peers) {
      if (peer == null) continue;
      try {
        transmit(peer);
      } catch (RuntimeException failure) {
        LOG.warn("Connection " + peer.client + " failed", failure);
        server.drop(peer.client, "Server connection error: " + failure.getMessage());
      }
    }
  }

  private void transmit(NetPeer peer) {
    ServerClient slot = server.client(peer.client);
    if (slot.state() == ServerClient.State.FREE) {
      peers[peer.client] = null;
      return;
    }
    forward(peer);
    if (peer.session.hasPendingPacket()) {
      drain(peer);
      return;
    }
    int time = server.serverTime();
    if (!peer.local && time < slot.nextSnapshotTime()) return;
    int bytes;
    if (peer.download != null || peer.downloadError != null) bytes = downloadTick(peer);
    else if (slot.state() == ServerClient.State.CONNECTED || peer.gamestateMessageNum < 0) {
      peer.session.queueKeepalive();
      bytes = drain(peer);
    } else {
      ClientSnapshot snapshot = server.snapshot(peer.client);
      int flags = server.snapFlags();
      if (peer.rateDelayed) flags |= GameServer.SNAPFLAG_RATE_DELAYED;
      if (slot.state() != ServerClient.State.ACTIVE) flags |= GameServer.SNAPFLAG_NOT_ACTIVE;
      int sequence = peer.session.nextMessageSequence();
      peer.session.queueSnapshot(
          snapshot.serverTime(),
          flags,
          snapshot.areaMask(),
          snapshot.playerState(),
          snapshot.entities());
      peer.snapshotSent(sequence, time);
      peer.lastPlayerState = snapshot.playerState();
      peer.lastServerTime = snapshot.serverTime();
      peer.lastFlags = flags;
      bytes = drain(peer);
    }
    schedule(peer, slot, bytes);
  }

  private void schedule(NetPeer peer, ServerClient slot, int bytes) {
    int time = server.serverTime();
    if (peer.local) {
      slot.scheduleSnapshot(time - 1);
      return;
    }
    int rate = slot.rate();
    CvarTable cvars = server.cvars();
    int maxRate = cvars.integer("sv_maxRate"), minRate = cvars.integer("sv_minRate");
    if (maxRate > 0) rate = Math.min(rate, Math.max(1000, maxRate));
    if (minRate > 0) rate = Math.max(rate, Math.max(1000, minRate));
    int rateMsec = (bytes + HEADER_RATE_BYTES) * 1000 / Math.max(1, rate);
    if (rateMsec < slot.snapshotMsec()) {
      rateMsec = slot.snapshotMsec();
      peer.rateDelayed = false;
    } else peer.rateDelayed = true;
    int next = time + rateMsec;
    if (slot.state() != ServerClient.State.ACTIVE && next < time + 1000) next = time + 1000;
    slot.scheduleSnapshot(next);
  }

  private int downloadTick(NetPeer peer) {
    int bytes = 0;
    if (peer.downloadError != null) {
      peer.session.queueDownload(
          new ServerMessageCodec.Download(0, -1, new byte[0], peer.downloadError));
      peer.downloadError = null;
      peer.downloadName = "";
      return drain(peer);
    }
    int blocks = peer.local ? DownloadSender.WINDOW : DOWNLOAD_BLOCKS_PER_FRAME;
    try {
      for (int i = 0; i < blocks && peer.download != null; i++) {
        Optional<ServerMessageCodec.Download> next = peer.download.next(now);
        if (next.isEmpty()) break;
        peer.session.queueDownload(next.get());
        bytes += drain(peer);
        if (peer.session.hasPendingPacket()) break;
      }
    } catch (IOException failure) {
      peer.stopDownload();
      server.drop(peer.client, "Download failed: " + failure.getMessage());
    }
    if (bytes == 0) {
      peer.session.queueKeepalive();
      bytes = drain(peer);
    }
    return bytes;
  }

  /** Sends every queued fragment of the connection; returns the bytes sent. */
  private int drain(NetPeer peer) {
    int bytes = 0;
    while (true) {
      Optional<byte[]> packet = peer.session.pollPacket();
      if (packet.isEmpty()) return bytes;
      bytes += packet.get().length;
      send(peer.transport, peer.address, packet.get());
    }
  }

  // ---- lingering and lifecycle ------------------------------------------------------------------

  private void linger(NetPeer peer) {
    if (peer.closing) return;
    peer.closing = true;
    peer.stopDownload();
    peer.closeAt = now + 1000L * Math.max(1, server.cvars().integer("sv_zombietime"));
    peer.nextFinalMessage = now;
    if (peers[peer.client] == peer) peers[peer.client] = null;
    lingering.add(peer);
    finalMessage(peer);
  }

  private void finalMessage(NetPeer peer) {
    try {
      forward(peer);
      drain(peer);
      if (peer.session.hasPendingPacket()) return;
      if (peer.gamestateMessageNum < 0) peer.session.queueKeepalive();
      else
        peer.session.queueSnapshot(
            peer.lastServerTime, peer.lastFlags, new byte[0], peer.lastPlayerState, List.of());
      drain(peer);
    } catch (RuntimeException failure) {
      LOG.debug("Final message to client " + peer.client + " failed: " + failure.getMessage());
    }
    peer.nextFinalMessage = now + 500;
  }

  private void serviceLingering() {
    for (Iterator<NetPeer> it = lingering.iterator(); it.hasNext(); ) {
      NetPeer peer = it.next();
      if (now >= peer.closeAt) {
        it.remove();
        continue;
      }
      if (now >= peer.nextFinalMessage) finalMessage(peer);
    }
  }

  private void sendHeartbeats() {
    List<SocketAddress> masters = heartbeat.due(server.cvars(), now);
    if (masters.isEmpty()) return;
    for (Endpoint endpoint : endpoints) {
      if (endpoint.local) continue;
      for (SocketAddress master : masters)
        send(
            endpoint.transport,
            master,
            ConnectionlessPacket.text("heartbeat " + MasterHeartbeat.GAME + "\n"));
      return;
    }
  }

  @Override
  public void levelSpawned(GameServer source) {
    for (NetPeer peer : peers) {
      if (peer == null) continue;
      peer.stopDownload();
      // Packets naming the old level now come back as WRONG_GAME and trigger the new gamestate.
      peer.session.serverId(source.serverId());
      peer.restartPending = false;
      peer.gotCp = false;
      peer.pureAuthentic = false;
    }
  }

  @Override
  public void levelRestarted(GameServer source) {
    for (NetPeer peer : peers) {
      if (peer == null) continue;
      peer.session.serverId(source.serverId());
      peer.restartPending = true;
    }
  }

  @Override
  public void levelEnded(GameServer source, String reason) {
    for (NetPeer peer : peers) if (peer != null) linger(peer);
  }

  @Override
  public void clientDropped(GameServer source, int client, String reason) {
    NetPeer peer = peers[client];
    if (peer != null) linger(peer);
  }

  /** Disconnects every client with {@code reason} and stops serving. */
  @Override
  public void close() {
    if (closed) return;
    if (server.state() == GameServer.State.RUNNING)
      for (int i = 0; i < peers.length; i++)
        if (peers[i] != null) server.drop(i, "Server shutdown");
    for (NetPeer peer : lingering) finalMessage(peer);
    lingering.clear();
    server.removeListener(this);
    closed = true;
  }
}
