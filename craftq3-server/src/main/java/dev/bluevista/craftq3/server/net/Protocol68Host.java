package dev.bluevista.craftq3.server.net;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

/** Single-thread hosted game router. The platform owns sockets; original qagame owns gameplay. */
public final class Protocol68Host implements AutoCloseable {
  public record Datagram(InetSocketAddress peer, byte[] payload) {
    public Datagram {
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  public record Client(
      int number,
      InetSocketAddress address,
      boolean active,
      boolean closing,
      String name,
      int ping,
      int rate) {}

  private record Challenge(int value, int nonce, long expires) {}

  private static final int MAX_OUTBOX = 1024;

  private record Outbound(Datagram packet, Peer peer, int sequence, boolean complete) {}

  private static final class Peer {
    final int number, qport, challenge;
    final Protocol68ServerSession wire;
    final HostPacketTiming timing = new HostPacketTiming();
    boolean queued, closingMessagePending, rateDelayed;
    Q3Server admittedGame;
    DownloadSender download;
    ServerMessageCodec.Download downloadError;
    boolean downloading;
    InetSocketAddress address;
    Map<String, String> userInfo;
    Map<Integer, String> config;
    int commandCursor, lastFrame = -1, finalTime, finalFlags;
    byte[] finalPlayer = new byte[468];
    long lastPacket, nextSend, lastGame, closingAt;
    boolean active, closing, gamePending, gotPure, pure;

    Peer(
        int number,
        InetSocketAddress address,
        int qport,
        int challenge,
        Map<String, String> info,
        long now) {
      this.number = number;
      this.address = address;
      this.qport = qport;
      this.challenge = challenge;
      userInfo = info;
      wire = new Protocol68ServerSession(challenge, qport);
      lastPacket = now;
    }
  }

  private Q3Server game;
  private final Pk3FileSystem fs;
  private final Consumer<String> output;
  private final SecureRandom random = new SecureRandom();
  private final LinkedHashMap<InetSocketAddress, Challenge> challenges = new LinkedHashMap<>();
  private final LinkedHashMap<InetAddress, ArrayDeque<Long>> limits = new LinkedHashMap<>();
  private final ArrayDeque<Long> globalLimit = new ArrayDeque<>();
  private final Map<Integer, Peer> peers = new LinkedHashMap<>();
  private final ArrayDeque<Outbound> outbox = new ArrayDeque<>();
  private List<Pk3FileSystem.Pack> packs;
  private Set<Integer> allowedPure;
  private int serverId, checksumFeed, checksumFeedServerId, flags;
  private Integer cgameChecksum, uiChecksum;
  private boolean pure, closed;
  private long clock;

  /** The caller must initialize the game and occupy any local-player slot first. */
  public Protocol68Host(
      Q3Server game, Pk3FileSystem fs, int serverId, int checksumFeed, Consumer<String> output) {
    this.game = Objects.requireNonNull(game);
    this.fs = Objects.requireNonNull(fs);
    this.output = Objects.requireNonNull(output);
    this.serverId = serverId;
    this.checksumFeed = checksumFeed;
    configure();
  }

  private CvarSystem cvars() {
    return game.cvars();
  }

  private void configure() {
    cvars().register("sv_pure", "1", CvarSystem.SYSTEMINFO);
    cvars()
        .register(
            "sv_serverid", Integer.toString(serverId), CvarSystem.SYSTEMINFO | CvarSystem.ROM);
    cvars().register("sv_timeout", "200", 0);
    cvars().register("sv_minRate", "0", CvarSystem.SERVERINFO);
    cvars().register("sv_maxRate", "0", CvarSystem.SERVERINFO);
    cvars().register("sv_privateClients", "0", CvarSystem.SERVERINFO);
    cvars().register("sv_privatePassword", "", 0);
    cvars().register("sv_allowDownload", "0", 0);
    cvars().register("protocol", "68", CvarSystem.ROM);
    pure = cvars().integer("sv_pure") != 0;
    flags = game.snapshotFlags();
    checksumFeedServerId = serverId;
    packs = fs.packs();
    allowedPure = new HashSet<>();
    for (var pack : packs) allowedPure.add(pack.pureChecksum());
    cgameChecksum = moduleChecksum("vm/cgame.qvm");
    uiChecksum = moduleChecksum("vm/ui.qvm");
    if (pure && (cgameChecksum == null || uiChecksum == null))
      throw new IllegalArgumentException(
          "Pure hosting requires cgame and UI in installed PK3s; use sv_pure 0 for loose modules");
    setSystem("sv_serverid", Integer.toString(serverId));
    setSystem("sv_paks", pure ? joined(packs, false, false) : "");
    setSystem("sv_pakNames", pure ? joined(packs, true, false) : "");
    setSystem(
        "sv_referencedPaks",
        joined(
            packs.stream()
                .filter(p -> p.references() != 0 || !p.origin().game().equals("baseq3"))
                .toList(),
            false,
            false));
    setSystem(
        "sv_referencedPakNames",
        joined(
            packs.stream()
                .filter(p -> p.references() != 0 || !p.origin().game().equals("baseq3"))
                .toList(),
            true,
            true));
    refreshInfo();
  }

  private String joined(List<Pk3FileSystem.Pack> selected, boolean names, boolean gamePrefix) {
    return String.join(
        " ",
        selected.stream()
            .map(
                p ->
                    names
                        ? (gamePrefix ? p.origin().game() + "/" : "") + p.name()
                        : Integer.toString(p.checksum()))
            .toList());
  }

  private Integer moduleChecksum(String path) {
    var origin = fs.which(new VirtualPath(path)).orElse(null);
    return packs.stream()
        .filter(p -> p.origin().equals(origin))
        .map(Pk3FileSystem.Pack::pureChecksum)
        .findFirst()
        .orElse(null);
  }

  private void setSystem(String name, String value) {
    cvars().register(name, value, CvarSystem.SYSTEMINFO | CvarSystem.ROM);
    cvars().set(name, value, CvarSystem.Source.ENGINE);
  }

  private void refreshInfo() {
    game.configstrings().set(0, cvars().infoString(CvarSystem.SERVERINFO, 1024));
    game.configstrings().set(1, cvars().infoString(CvarSystem.SYSTEMINFO, 8192));
  }

  public List<Client> clients() {
    return peers.values().stream()
        .map(
            p ->
                new Client(
                    p.number,
                    p.address,
                    p.active,
                    p.closing,
                    p.userInfo.getOrDefault("name", ""),
                    p.active ? p.timing.ping() : 999,
                    clientRate(p)))
        .toList();
  }

  public Optional<Datagram> peekPacket() {
    return Optional.ofNullable(outbox.peekFirst()).map(Outbound::packet);
  }

  public void packetSent() {
    packetSent(clock);
  }

  /** Called only after a complete UDP datagram has been accepted by the transport. */
  public void packetSent(long now) {
    var sent = outbox.pollFirst();
    if (sent == null) throw new IllegalStateException("No queued packet");
    if (sent.peer() != null) {
      var peer = sent.peer();
      peer.queued = false;
      peer.timing.sent(sent.sequence(), sent.packet().payload.length, sent.complete(), now);
      if (sent.complete() && sent.sequence() == peer.wire.gameMessageSequence())
        peer.lastGame = now;
      if (peer.closing) {
        if (!peer.wire.hasPendingPacket() && peer.closingMessagePending) finalMessage(peer);
        drain(peer);
      }
    }
  }

  public boolean hasClients() {
    return peers.values().stream().anyMatch(p -> !p.closing);
  }

  /** Retain sockets/reliable channels while the owner replaces a fully loaded map. */
  public void replaceGame(Q3Server next, long now) {
    game = Objects.requireNonNull(next);
    serverId = serverId == Integer.MAX_VALUE ? 1 : serverId + 1;
    configure();
    for (var peer : peers.values()) {
      if (peer.closing) continue;
      stopDownload(peer);
      if (peer.number >= capacity() || game.isConnected(peer.number)) {
        drop(peer, "Server capacity changed", now);
        continue;
      }
      try {
        game.connectPending(peer.number, peer.userInfo, false);
        peer.admittedGame = game;
      } catch (IllegalStateException denied) {
        drop(peer, denied.getMessage(), now);
        continue;
      }
      peer.active = false;
      peer.gotPure = false;
      peer.pure = false;
      peer.gamePending = true;
      peer.lastFrame = -1;
      peer.commandCursor = game.currentServerCommandSequence();
    }
  }

  public void receive(InetSocketAddress from, byte[] bytes, long now) {
    clock = now;
    if (closed
        || from.isUnresolved()
        || from.getPort() == 0
        || from.getAddress().isAnyLocalAddress()
        || from.getAddress().isMulticastAddress()) return;
    if (bytes.length > Protocol68Channel.MAX_MESSAGE) return;
    if (ConnectionlessPacket.isConnectionless(bytes)) {
      if (!permit(from.getAddress(), now)) return;
      try {
        oob(from, bytes, now);
      } catch (IllegalArgumentException ignored) {
        /* Malformed datagram has no command authority. */
      }
      return;
    }
    if (bytes.length < 6 || bytes.length > Protocol68Channel.MAX_PACKET) return;
    int qport =
        Short.toUnsignedInt(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(4));
    Peer peer =
        peers.values().stream()
            .filter(p -> p.qport == qport && p.address.getAddress().equals(from.getAddress()))
            .findFirst()
            .orElse(null);
    if (peer == null) return;
    var received = peer.wire.receive(bytes);
    if (received.status() == Protocol68ServerSession.Status.REJECTED) {
      drop(peer, received.diagnostic(), now);
      return;
    }
    if (received.status() == Protocol68ServerSession.Status.WRONG_GAME) {
      peer.lastPacket = now;
      if (now - peer.lastGame >= 3000) peer.gamePending = true;
      return;
    }
    if (received.status() != Protocol68ServerSession.Status.ACCEPTED) return;
    peer.address = from;
    peer.lastPacket = now;
    if (received.message().movement() != null)
      peer.timing.acknowledge(peer.wire.messageAcknowledge(), now);
    if (peer.closing) return;
    try {
      for (var command : received.commands()) {
        dispatch(peer, CommandParser.tokenize(command.text()), now);
        if (peer.closing) return;
      }
      if (!peer.downloading && !received.userCommands().isEmpty()) {
        if (pure && !peer.pure) {
          if (peer.gotPure) drop(peer, "Cannot validate pure client!", now);
          else if (now - peer.lastGame >= 3000) peer.gamePending = true;
          return;
        }
        var commands = received.userCommands();
        int first = 0;
        if (!peer.active) {
          if (!game.hasEntered(peer.number))
            game.begin(peer.number, UserCommand.fromBytes(commands.getFirst()));
          peer.active = true;
          first = 1;
          peer.nextSend = now;
        }
        for (int i = first; i < commands.size() && game.isConnected(peer.number); i++)
          game.userCommand(peer.number, UserCommand.fromBytes(commands.get(i)));
      }
      if (!game.isConnected(peer.number)) drop(peer, "Disconnected by game", now);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      if (game.state() == Q3Server.State.FAILED) throw failure;
      drop(peer, failure.getMessage(), now);
    }
  }

  private void oob(InetSocketAddress from, byte[] bytes, long now) {
    boolean connect =
        bytes.length >= 12
            && new String(bytes, 4, 8, java.nio.charset.StandardCharsets.ISO_8859_1)
                .equals("connect ");
    var command =
        CommandParser.tokenize(
            ConnectionlessMessage.parse(connect ? ConnectPacketCodec.decompress(bytes) : bytes)
                .line());
    switch (command.argument(0)) {
      case "getchallenge" -> {
        int nonce = command.arguments().size() > 1 ? Integer.parseInt(command.argument(1)) : 0;
        var existing = challenges.get(from);
        if (existing == null || existing.nonce() != nonce || existing.expires() < now) {
          existing = new Challenge(random.nextInt(), nonce, now + 30000);
          challenges.put(from, existing);
          while (challenges.size() > 2048) challenges.remove(challenges.keySet().iterator().next());
        }
        send(
            from,
            ConnectionlessPacket.text(
                "challengeResponse " + existing.value() + " " + nonce + " 68"));
      }
      case "connect" -> connect(from, command, now);
      case "getinfo" ->
          send(from, ConnectionlessPacket.text("infoResponse\n" + info(command.argument(1))));
      case "getstatus" -> {
        var status =
            new StringBuilder("statusResponse\n")
                .append(statusInfo(command.argument(1)))
                .append('\n');
        for (int client = 0; client < capacity(); client++)
          if (game.isConnected(client)) {
            var player = ByteBuffer.wrap(game.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
            String playerInfo = game.configstrings().get(544 + client);
            if (!playerInfo.isEmpty() && !playerInfo.startsWith("\\"))
              playerInfo = "\\" + playerInfo;
            String name;
            try {
              name = InfoString.parse(playerInfo, 2048).getOrDefault("n", "player");
            } catch (IllegalArgumentException malformedGameInfo) {
              name = "player";
            }
            String row =
                player.getInt(248)
                    + " "
                    + (game.isBot(client) || !peers.containsKey(client)
                        ? 0
                        : peers.get(client).active ? peers.get(client).timing.ping() : 999)
                    + " \""
                    + safe(name, 32)
                    + "\"\n";
            if (status.length() + row.length() > 16000) break;
            status.append(row);
          }
        send(from, ConnectionlessPacket.text(status.toString()));
      }
      default -> {}
    }
  }

  private void connect(InetSocketAddress from, CommandParser.Command command, long now) {
    if (command.arguments().size() != 2) return;
    var info = new LinkedHashMap<>(InfoString.parse(command.argument(1), 1024));
    if (!"68".equals(info.get("protocol"))) {
      reject(from, "Server uses protocol 68");
      return;
    }
    int qport = Integer.parseInt(info.getOrDefault("qport", "-1"));
    int challenge = Integer.parseInt(info.getOrDefault("challenge", "0"));
    if (qport < 0 || qport > 65535) return;
    var offered = challenges.get(from);
    if (offered == null || offered.expires() < now || offered.value() != challenge) {
      reject(from, "No or bad challenge for address");
      return;
    }
    for (var peer : peers.values())
      if (peer.address.getAddress().equals(from.getAddress()) && peer.qport == qport) {
        if (!peer.closing && peer.challenge == challenge) {
          send(from, ConnectionlessPacket.text("connectResponse"));
          return;
        }
        reject(from, "Connection is still closing; retry shortly");
        return;
      }
    int privateSlots = Math.clamp(cvars().integer("sv_privateClients"), 0, capacity());
    int start =
        !cvars().string("sv_privatePassword").isEmpty()
                && cvars().string("sv_privatePassword").equals(info.get("password"))
            ? 0
            : privateSlots;
    int number = -1;
    for (int i = start; i < capacity(); i++)
      if (!game.isConnected(i) && !peers.containsKey(i)) {
        number = i;
        break;
      }
    if (number < 0) {
      reject(from, "Server is full");
      return;
    }
    info.put("ip", from.getAddress().getHostAddress());
    info.remove("challenge");
    info.remove("qport");
    info.remove("protocol");
    InfoString.encode(info, 1024);
    try {
      game.connectPending(number, info);
    } catch (IllegalStateException denied) {
      reject(from, denied.getMessage());
      return;
    }
    var peer = new Peer(number, from, qport, challenge, Map.copyOf(info), now);
    peer.admittedGame = game;
    peer.commandCursor = game.currentServerCommandSequence();
    peer.gamePending = true;
    peers.put(number, peer);
    send(from, ConnectionlessPacket.text("connectResponse"));
    output.accept("Hosted client " + number + " connected from " + from);
  }

  private void dispatch(Peer peer, CommandParser.Command command, long now) {
    switch (command.argument(0)) {
      case "disconnect" -> drop(peer, "Client disconnected", now);
      case "userinfo" -> {
        var info = new LinkedHashMap<>(InfoString.parse(command.argument(1), 1024));
        info.put("ip", peer.address.getAddress().getHostAddress());
        game.updateUserInfo(peer.number, info);
        peer.userInfo = Map.copyOf(info);
      }
      case "cp" -> {
        var result =
            PureClientPolicy.verify(
                command,
                pure,
                checksumFeedServerId,
                checksumFeed,
                cgameChecksum == null ? 0 : cgameChecksum,
                uiChecksum == null ? 0 : uiChecksum,
                allowedPure);
        if (result != PureClientPolicy.Result.IGNORED) {
          peer.gotPure = true;
          peer.pure = result == PureClientPolicy.Result.ACCEPTED;
          if (!peer.pure) drop(peer, "Cannot validate pure client!", now);
        }
      }
      case "vdr" -> {
        peer.gotPure = false;
        peer.pure = false;
      }
      case "download" -> beginDownload(peer, command.argument(1));
      case "stopdl" -> stopDownload(peer);
      case "nextdl" -> {
        if (peer.download != null) {
          try {
            peer.download.acknowledge(Integer.parseInt(command.argument(1)), now);
          } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
          }
        }
      }
      case "donedl" -> {
        if (peer.download != null && !peer.download.complete())
          throw new IllegalArgumentException("Download has not completed");
        stopDownload(peer);
        peer.gamePending = true;
      }
      default -> {
        if (peer.active) game.clientCommand(peer.number, command.text());
      }
    }
  }

  private void beginDownload(Peer peer, String requested) {
    stopDownload(peer);
    peer.downloading = true;
    peer.active = false;
    try {
      int allow = cvars().integer("sv_allowDownload");
      if ((allow & 1) == 0 || (allow & 4) != 0)
        throw new IOException("Server UDP downloads are disabled");
      String name = new VirtualPath(requested).value();
      String[] parts = name.split("/", -1);
      if (parts.length != 2 || !parts[1].endsWith(".pk3"))
        throw new IOException("Only referenced PK3 archives may be downloaded");
      String stem = parts[1].substring(0, parts[1].length() - 4);
      if (parts[0].equals("baseq3") && stem.matches("pak[0-8]")
          || parts[0].equals("missionpack") && stem.matches("pak[0-3]"))
        throw new IOException("Official game PK3s must be installed locally");
      var pack =
          packs.stream()
              .filter(
                  p ->
                      p.origin().game().equals(parts[0])
                          && p.name().equalsIgnoreCase(stem)
                          && (p.references() != 0 || !p.origin().game().equals("baseq3")))
              .findFirst()
              .orElseThrow(() -> new IOException("PK3 is not referenced by this server"));
      var archive = fs.openArchive(pack);
      try {
        if (archive.size() <= 0 || archive.size() > DownloadSender.MAX_FILE_BYTES)
          throw new IOException("PK3 exceeds the download budget");
        peer.download = new DownloadSender(archive.input(), (int) archive.size());
      } catch (IOException | RuntimeException failure) {
        archive.close();
        throw failure;
      }
    } catch (IOException | IllegalArgumentException failure) {
      peer.downloadError =
          new ServerMessageCodec.Download(0, -1, new byte[0], safe(failure.getMessage(), 500));
    }
  }

  private void stopDownload(Peer peer) {
    if (peer.download != null)
      try {
        peer.download.close();
      } catch (IOException failure) {
        output.accept("Download close: " + failure.getMessage());
      }
    peer.download = null;
    peer.downloadError = null;
    peer.downloading = false;
  }

  private void downloadTick(Peer peer, long now) {
    if (peer.downloadError != null) {
      peer.wire.queueDownload(peer.downloadError);
      peer.downloadError = null;
      drain(peer);
    } else if (peer.download != null) {
      try {
        var packet = peer.download.next(now);
        if (packet.isPresent()) {
          peer.wire.queueDownload(packet.orElseThrow());
          drain(peer);
        }
      } catch (IOException failure) {
        throw new IllegalStateException("Download read failed: " + failure.getMessage(), failure);
      }
    }
  }

  public void tick(long now) {
    clock = now;
    if (closed) return;
    for (String name : List.of("sv_minRate", "sv_maxRate")) {
      int value = cvars().integer(name);
      if (value > 0 && value < 1000) cvars().set(name, "1000", CvarSystem.Source.ENGINE);
    }
    if (game.snapshotFlags() != flags) {
      flags = game.snapshotFlags();
      serverId = serverId == Integer.MAX_VALUE ? 1 : serverId + 1;
      setSystem("sv_serverid", Integer.toString(serverId));
      refreshInfo();
      for (var peer : peers.values()) {
        peer.wire.serverId(serverId);
        // Allow the new systeminfo to arrive before treating the old ID as a stalled load.
        peer.lastGame = now;
      }
    }
    if ((cvars().integer("sv_pure") != 0) != pure) {
      configure();
      for (var peer : peers.values()) {
        peer.gamePending = true;
        peer.active = false;
        peer.gotPure = false;
        peer.pure = false;
      }
    }
    challenges.entrySet().removeIf(entry -> entry.getValue().expires() < now);
    for (var peer : new ArrayList<>(peers.values())) {
      if (peer.closing && now >= peer.closingAt) {
        peers.remove(peer.number);
        continue;
      }
      if (!peer.closing
          && now - peer.lastPacket > Math.clamp(cvars().integer("sv_timeout"), 1, 3600) * 1000L)
        drop(peer, "Timed out", now);
      if (!peer.closing && game.isConnected(peer.number) && game.hasEntered(peer.number))
        game.ping(peer.number, peer.active ? peer.timing.ping() : 999);
      if (outbox.size() > MAX_OUTBOX - 32) break;
      try {
        if (peer.queued) continue;
        if (peer.wire.hasPendingPacket()) {
          drain(peer);
          continue;
        }
        if (peer.closing) {
          if (now >= peer.nextSend) {
            finalMessage(peer);
            peer.nextSend = now + 500;
            drain(peer);
          }
          continue;
        }
        if (!game.isConnected(peer.number)) {
          drop(peer, "Disconnected by game", now);
          continue;
        }
        if (rateDelay(peer, now) > 0) {
          if (peer.active && now >= peer.nextSend) peer.rateDelayed = true;
          continue;
        }
        if (peer.downloading) {
          downloadTick(peer, now);
          continue;
        }
        if (peer.gamePending || !peer.active && now - peer.lastGame >= 3000) {
          peer.config = game.configstrings().snapshot();
          peer.wire.queueGameState(
              new GameState(0, peer.config, Baselines.EMPTY, peer.number, checksumFeed), serverId);
          peer.gamePending = false;
          peer.lastGame = now;
          peer.nextSend = now + 50;
          drain(peer);
          continue;
        }
        if (now < peer.nextSend) continue;
        sync(peer);
        if (peer.active && peer.lastFrame != game.frameNumber()) {
          var snapshot = game.entitySnapshot(peer.number);
          peer.finalPlayer = game.playerState(peer.number);
          peer.finalTime = game.time();
          peer.finalFlags = game.snapshotFlags();
          peer.wire.queueSnapshot(
              game.time(),
              game.snapshotFlags() | (peer.rateDelayed ? 1 : 0),
              snapshot.areaMask().copy(),
              game.playerState(peer.number),
              snapshot.entities());
          peer.lastFrame = game.frameNumber();
          peer.rateDelayed = false;
        } else peer.wire.queueKeepalive();
        peer.nextSend =
            now
                + HostPacketTiming.snapshotInterval(
                    peer.userInfo.get("snaps"), cvars().integer("sv_fps"));
        drain(peer);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        if (game.state() == Q3Server.State.FAILED) throw failure;
        drop(peer, failure.getMessage(), now);
      }
    }
  }

  private void sync(Peer peer) {
    var current = game.configstrings().snapshot();
    for (int i = 0; i < 1024; i++)
      if (!current.getOrDefault(i, "").equals(peer.config.getOrDefault(i, "")))
        for (String command : ConfigstringCommands.outbound(i, current.getOrDefault(i, "")))
          peer.wire.command(command);
    peer.config = current;
    for (var command : game.commandsSince(peer.commandCursor)) {
      if (command.client() == -1 || command.client() == peer.number)
        peer.wire.command(command.text());
      peer.commandCursor = command.sequence();
    }
  }

  private static int clientRate(Peer peer) {
    return HostPacketTiming.clientRate(peer.userInfo.get("rate"));
  }

  private long rateDelay(Peer peer, long now) {
    float scale = cvars().number("timescale");
    if (!Float.isFinite(scale) || scale <= 0) scale = 1;
    return peer.timing.delay(
        clientRate(peer),
        cvars().integer("sv_minRate"),
        cvars().integer("sv_maxRate"),
        scale,
        peer.address.getAddress() instanceof java.net.Inet6Address,
        now);
  }

  private void drain(Peer peer) {
    if (peer.queued
        || !peer.wire.hasPendingPacket()
        || outbox.size() >= MAX_OUTBOX
        || !peer.closing && rateDelay(peer, clock) > 0) return;
    byte[] bytes = peer.wire.pollPacket().orElseThrow();
    int sequence =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() & Integer.MAX_VALUE;
    peer.queued = true;
    outbox.addLast(
        new Outbound(
            new Datagram(peer.address, bytes), peer, sequence, !peer.wire.hasPendingPacket()));
  }

  private void drop(Peer peer, String reason, long now) {
    if (peer.closing) return;
    stopDownload(peer);
    peer.closing = true;
    peer.active = false;
    peer.closingAt = now + 2000;
    peer.nextSend = now;
    if (peer.admittedGame == game
        && game.state() == Q3Server.State.RUNNING
        && peer.number < capacity()
        && game.isConnected(peer.number)) {
      peer.finalPlayer = game.playerState(peer.number);
      peer.finalTime = game.time();
      peer.finalFlags = game.snapshotFlags();
      game.disconnect(peer.number, safe(reason, 200));
    }
    try {
      peer.wire.command("disconnect \"" + safe(reason, 200) + "\"");
      peer.closingMessagePending = true;
      if (!peer.wire.hasPendingPacket() && !peer.queued) {
        finalMessage(peer);
        drain(peer);
      }
    } catch (IllegalStateException | IllegalArgumentException ignored) {
      peer.closingAt = now;
    }
  }

  private void finalMessage(Peer peer) {
    peer.closingMessagePending = false;
    if (peer.wire.gameMessageSequence() == 0) peer.wire.queueKeepalive();
    else
      peer.wire.queueSnapshot(
          peer.finalTime, peer.finalFlags, new byte[0], peer.finalPlayer, List.of());
  }

  private int capacity() {
    return Math.clamp(cvars().integer("sv_maxclients"), 1, 64);
  }

  private String info(String challenge) {
    var result = new LinkedHashMap<String, String>();
    result.put("challenge", challenge);
    result.put("protocol", "68");
    result.put("hostname", cvars().string("sv_hostname"));
    result.put("mapname", cvars().string("mapname"));
    int clients = 0;
    for (int i = 0; i < capacity(); i++) if (game.isConnected(i)) clients++;
    result.put("clients", Integer.toString(clients));
    result.put("sv_maxclients", Integer.toString(capacity()));
    result.put("gametype", cvars().string("g_gametype"));
    result.put("pure", pure ? "1" : "0");
    result.put("game", fs.gameDirectory());
    result.put("g_needpass", cvars().string("g_needpass"));
    return InfoString.encode(result, 1024);
  }

  private String statusInfo(String challenge) {
    var info =
        new LinkedHashMap<>(
            InfoString.parse(cvars().infoString(CvarSystem.SERVERINFO, 1024), 1024));
    info.put("challenge", challenge);
    return InfoString.encode(info, 1024);
  }

  private boolean permit(InetAddress address, long now) {
    while (!globalLimit.isEmpty() && now - globalLimit.peekFirst() >= 1000)
      globalLimit.removeFirst();
    if (globalLimit.size() >= 100) return false;
    var queue = limits.computeIfAbsent(address, ignored -> new ArrayDeque<>());
    while (!queue.isEmpty() && now - queue.peekFirst() >= 1000) queue.removeFirst();
    while (limits.size() > 1024) limits.remove(limits.keySet().iterator().next());
    if (queue.size() >= 10) return false;
    queue.addLast(now);
    globalLimit.addLast(now);
    return true;
  }

  private void reject(InetSocketAddress peer, String reason) {
    send(peer, ConnectionlessPacket.text("print\n" + safe(reason, 500) + "\n"));
  }

  private void send(InetSocketAddress peer, byte[] payload) {
    if (outbox.size() < MAX_OUTBOX)
      outbox.addLast(new Outbound(new Datagram(peer, payload), null, 0, true));
  }

  private static int integer(String text, int fallback) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String safe(String text, int max) {
    if (text == null) return "Server disconnected";
    var result = new StringBuilder();
    for (int i = 0; i < text.length() && result.length() < max; i++) {
      char c = text.charAt(i);
      result.append(c < 32 || c > 255 || c == '"' ? ' ' : c);
    }
    return result.toString();
  }

  /** Queue final reliable disconnects before the owner drains and closes its listener. */
  public void shutdown(long now) {
    for (var peer : peers.values()) drop(peer, "Server shut down", now);
    for (var peer : peers.values())
      try {
        drain(peer);
        if (!peer.wire.hasPendingPacket()) {
          finalMessage(peer);
          drain(peer);
        }
      } catch (IllegalArgumentException | IllegalStateException ignored) {
      }
  }

  @Override
  public void close() {
    if (closed) return;
    try {
      for (var peer : peers.values())
        if (!peer.closing
            && peer.admittedGame == game
            && game.state() == Q3Server.State.RUNNING
            && game.isConnected(peer.number)) game.disconnect(peer.number, "Server shut down");
    } finally {
      for (var peer : peers.values()) stopDownload(peer);
      peers.clear();
      challenges.clear();
      outbox.clear();
      closed = true;
    }
  }
}
