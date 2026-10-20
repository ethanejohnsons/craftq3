package dev.bluevista.craftq3.core.net;

import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.demo.DemoRecord;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Client-side ownership of an established legacy connection: reliable retransmission, XOR keys,
 * level state and bounded snapshot history. A transport must validate the peer before receive. This
 * class owns no socket, clock, filesystem or VM, and never executes received command text.
 */
public final class Protocol68ClientSession {
  public static final int RELIABLE_WINDOW = 64, SNAPSHOT_HISTORY = 32;

  public enum Status {
    ACCEPTED,
    PARTIAL,
    IGNORED,
    REJECTED
  }

  public record Received(
      Status status,
      ServerMessageCodec.Message message,
      List<ServerMessageCodec.Command> commands,
      String diagnostic,
      Optional<DemoRecord> demoRecord) {
    public Received {
      commands = List.copyOf(commands);
      java.util.Objects.requireNonNull(demoRecord);
      if (status != Status.ACCEPTED && demoRecord.isPresent())
        throw new IllegalArgumentException("Only accepted messages can be recorded");
    }

    public Received(
        Status status,
        ServerMessageCodec.Message message,
        List<ServerMessageCodec.Command> commands,
        String diagnostic) {
      this(status, message, commands, diagnostic, Optional.empty());
    }
  }

  private final int challenge;
  private final Protocol68Channel channel;
  private final TreeMap<Integer, String> clientCommands = new TreeMap<>();
  private int reliableSequence,
      transmittedReliableSequence,
      pendingReliableSequence,
      reliableAcknowledge;
  private int messageAcknowledge;
  private boolean requestDelta, fullSnapshots;
  private Integer presentedServerId;
  private Level level = new Level();

  public Protocol68ClientSession(int challenge, int qport) {
    this.challenge = challenge;
    channel = new Protocol68Channel(Protocol68Channel.Endpoint.CLIENT, qport);
    clientCommands.put(0, "");
  }

  public int messageAcknowledge() {
    return messageAcknowledge;
  }

  /** Current client command sequence, used by the native demo initial-gamestate header. */
  public int reliableSequence() {
    return reliableSequence;
  }

  public int reliableAcknowledge() {
    return reliableAcknowledge;
  }

  public int serverCommandSequence() {
    return level.commandSequence;
  }

  public record SnapshotState(Snapshot snapshot, int serverCommandSequence) {}

  public Optional<SnapshotState> snapshotState(int sequence) {
    Snapshot snapshot = level.history.get(sequence);
    return snapshot == null
        ? Optional.empty()
        : Optional.of(new SnapshotState(snapshot, level.snapshotCommands.get(sequence)));
  }

  public Optional<String> serverCommand(int sequence) {
    return Optional.ofNullable(level.commands.get(sequence));
  }

  public int gameStateMessageSequence() {
    return level.gameMessageSequence;
  }

  public int serverId() {
    return presentedServerId == null ? level.serverId : presentedServerId;
  }

  /**
   * A cgame consumer advances the outgoing server ID when it consumes systeminfo. Wire-only users
   * retain the newest decoded ID. A new gamestate always restores its own immediate baseline.
   */
  public void usePresentedServerId(int serverId) {
    if (level.game == null) throw new IllegalStateException("Server ID requires a gamestate");
    presentedServerId = serverId;
  }

  /** Original level baseline before subsequent reliable commands, for cgame initialization. */
  public Optional<ServerMessageCodec.GameState> initialGameState() {
    return Optional.ofNullable(level.game);
  }

  public Optional<ServerMessageCodec.GameState> gameState() {
    if (level.game == null) return Optional.empty();
    return Optional.of(
        new ServerMessageCodec.GameState(
            level.game.commandSequence(),
            level.config.strings(),
            level.game.baselines(),
            level.game.clientNumber(),
            level.game.checksumFeed()));
  }

  public Optional<Snapshot> snapshot() {
    return Optional.ofNullable(level.current);
  }

  /** Queue once; every subsequent message repeats unacknowledged commands in order. */
  public int command(String text) {
    if (reliableSequence == Integer.MAX_VALUE)
      throw new IllegalStateException("Reliable sequence exhausted; reconnect");
    if (reliableSequence - reliableAcknowledge >= RELIABLE_WINDOW)
      throw new IllegalStateException("Reliable client command window is full");
    new ClientMessageCodec.Command(reliableSequence + 1, text);
    clientCommands.put(++reliableSequence, text);
    // Include the acknowledged key even when all 64 subsequent slots are outstanding.
    while (clientCommands.size() > RELIABLE_WINDOW + 1) clientCommands.pollFirstEntry();
    return reliableSequence;
  }

  /**
   * Queue a keepalive or a batch of canonical user commands. The caller drains all fragments before
   * queuing another message. A large reliable backlog is sent in a fitting prefix; unsent commands
   * remain queued and cannot be acknowledged by the peer.
   */
  public void queue(List<byte[]> userCommands) {
    if (channel.hasPendingPacket())
      throw new IllegalStateException("Drain pending datagrams first");
    var movement =
        userCommands.isEmpty()
            ? null
            : new ClientMessageCodec.Movement(
                requestDelta
                    && !fullSnapshots
                    && level.current != null
                    && level.current.sequence() == messageAcknowledge,
                userCommands);
    if (movement != null && level.game == null)
      throw new IllegalStateException("Movement requires a gamestate");
    var pending = new ArrayList<ClientMessageCodec.Command>();
    clientCommands
        .tailMap(reliableAcknowledge, false)
        .forEach((number, text) -> pending.add(new ClientMessageCodec.Command(number, text)));
    String key = level.commands.get(level.commandSequence);
    if (key == null) throw new IllegalStateException("Missing reliable server command key");
    int feed = level.game == null ? 0 : level.game.checksumFeed();
    int count = pending.size();
    while (true) {
      var writer = new MessageWriter();
      try {
        ClientMessageCodec.write(
            writer,
            new ClientMessageCodec.Message(
                serverId(),
                messageAcknowledge,
                level.commandSequence,
                pending.subList(0, count),
                movement),
            feed,
            key);
        channel.queue(
            LegacyPayloadXor.client(
                writer.bytes(), challenge, serverId(), messageAcknowledge, key));
        pendingReliableSequence =
            count == 0
                ? transmittedReliableSequence
                : Math.max(transmittedReliableSequence, pending.get(count - 1).sequence());
        return;
      } catch (IllegalArgumentException tooLarge) {
        if (count == 0) throw tooLarge;
        count--;
      }
    }
  }

  private int downloadBlock;

  /** Start a requested file; reliable numbering and level state remain intact. */
  public void beginDownload() {
    downloadBlock = 0;
  }

  public boolean hasPendingPacket() {
    return channel.hasPendingPacket();
  }

  public Optional<byte[]> pollPacket() {
    var packet = channel.pollPacket();
    if (packet.isPresent() && !channel.hasPendingPacket())
      transmittedReliableSequence = pendingReliableSequence;
    return packet;
  }

  /** Keep requesting independent snapshots while a recorder waits for its first full frame. */
  public void fullSnapshots(boolean value) {
    fullSnapshots = value;
  }

  /** Forces the next movement to request a complete snapshot after local history loss. */
  public void requestFullSnapshot() {
    requestDelta = false;
  }

  /**
   * Invalid payloads consume channel sequencing but cannot partially alter level/reliable state.
   */
  public Received receive(byte[] datagram) {
    var packet = channel.receive(datagram);
    if (packet.status() != Protocol68Channel.Status.COMPLETE) {
      Status status =
          switch (packet.status()) {
            case PARTIAL -> Status.PARTIAL;
            case MALFORMED, WRONG_QPORT -> Status.REJECTED;
            default -> Status.IGNORED;
          };
      return new Received(status, null, List.of(), packet.status().name());
    }
    try {
      byte[] encrypted = packet.payload();
      int acknowledged = new MessageReader(encrypted).intValue();
      String key = clientCommands.get(acknowledged);
      if (acknowledged < reliableAcknowledge
          || acknowledged > transmittedReliableSequence
          || key == null)
        throw new IllegalArgumentException("Invalid reliable client acknowledgement");
      byte[] clear = LegacyPayloadXor.server(encrypted, challenge, packet.sequence(), key);
      var decoded = new MessageReader(clear);
      var message =
          ServerMessageCodec.read(
              decoded,
              packet.sequence(),
              level.game == null ? Baselines.EMPTY : level.game.baselines(),
              level.history::get,
              downloadBlock);
      if (message.reliableAcknowledge() != acknowledged)
        throw new IllegalArgumentException(
            "Transformed acknowledgement differs from its clear header");
      Level next = new Level(level);
      int nextDownloadBlock = downloadBlock;
      boolean nextDelta = requestDelta;
      var commands = new ArrayList<ServerMessageCodec.Command>();
      int covered = next.commandSequence;
      for (var operation : message.operations())
        if (operation instanceof ServerMessageCodec.GameState game) {
          if (game.commandSequence() < covered
              || game.clientNumber() < 0
              || game.clientNumber() >= 64)
            throw new IllegalArgumentException("Invalid gamestate sequence or client number");
          covered = game.commandSequence();
        }
      for (var operation : message.operations()) {
        switch (operation) {
          case ServerMessageCodec.NoOp ignored -> {}
          case ServerMessageCodec.Download download -> {
            if (download.error() == null && download.block() == (nextDownloadBlock & 65535))
              nextDownloadBlock++;
          }
          case ServerMessageCodec.Command command -> {
            if (command.sequence() < 1)
              throw new IllegalArgumentException("Invalid server command sequence");
            if (command.sequence() <= next.commandSequence) continue;
            if (command.sequence() != next.commandSequence + 1 && command.sequence() > covered)
              throw new IllegalArgumentException("Reliable server command gap");
            next.commands.put(command.sequence(), command.text());
            next.commandSequence = command.sequence();
            while (next.commands.size() > RELIABLE_WINDOW) next.commands.pollFirstEntry();
            next.apply(command.text());
            commands.add(command);
          }
          case ServerMessageCodec.GameState game -> {
            if (game.commandSequence() < next.commandSequence)
              throw new IllegalArgumentException("Gamestate rewinds preceding reliable commands");
            next.game = game;
            nextDownloadBlock = 0;
            next.gameMessageSequence = packet.sequence();
            next.config = new ConfigstringCommands(game.configstrings());
            next.commandSequence = game.commandSequence();
            next.history.clear();
            next.snapshotCommands.clear();
            next.current = null;
            nextDelta = false;
            next.systemInfo();
          }
          case ServerMessageCodec.Frame frame -> {
            if (next.game == null)
              throw new IllegalArgumentException("Snapshot precedes gamestate");
            next.history.put(frame.current().sequence(), frame.current());
            next.snapshotCommands.put(frame.current().sequence(), next.commandSequence);
            while (next.history.size() > SNAPSHOT_HISTORY) {
              int expired = next.history.pollFirstEntry().getKey();
              next.snapshotCommands.remove(expired);
            }
            next.current = frame.current();
            nextDelta = true;
          }
        }
      }
      if (!next.commands.containsKey(next.commandSequence))
        throw new IllegalArgumentException("Gamestate omitted the reliable command key");
      // Reconstructing here validates the combined configstring budget after reliable updates.
      if (next.game != null)
        new ServerMessageCodec.GameState(
            next.commandSequence,
            next.config.strings(),
            next.game.baselines(),
            next.game.clientNumber(),
            next.game.checksumFeed());
      if (next.gameMessageSequence != level.gameMessageSequence) presentedServerId = null;
      level = next;
      downloadBlock = nextDownloadBlock;
      reliableAcknowledge = acknowledged;
      messageAcknowledge = packet.sequence();
      requestDelta = nextDelta;
      return new Received(
          Status.ACCEPTED,
          message,
          commands,
          "",
          Optional.of(new DemoRecord(packet.sequence(), clear)));
    } catch (IllegalArgumentException failure) {
      requestDelta = false;
      return new Received(Status.REJECTED, null, List.of(), failure.getMessage());
    }
  }

  private static final class Level {
    private ServerMessageCodec.GameState game;
    private ConfigstringCommands config = new ConfigstringCommands(Map.of());
    private final TreeMap<Integer, String> commands = new TreeMap<>();
    private final TreeMap<Integer, Snapshot> history = new TreeMap<>();
    private final TreeMap<Integer, Integer> snapshotCommands = new TreeMap<>();
    private Snapshot current;
    private int commandSequence, serverId, gameMessageSequence;

    Level() {
      commands.put(0, "");
    }

    Level(Level from) {
      game = from.game;
      config = new ConfigstringCommands(from.config);
      commands.putAll(from.commands);
      history.putAll(from.history);
      snapshotCommands.putAll(from.snapshotCommands);
      gameMessageSequence = from.gameMessageSequence;
      current = from.current;
      commandSequence = from.commandSequence;
      serverId = from.serverId;
    }

    void apply(String text) {
      String previous = config.strings().get(1);
      config.consume(text);
      if (!java.util.Objects.equals(previous, config.strings().get(1))) systemInfo();
    }

    void systemInfo() {
      Map<String, String> info = InfoString.parse(config.strings().getOrDefault(1, ""), 8192);
      String id = info.get("sv_serverid");
      if (id == null) throw new IllegalArgumentException("Missing server ID in system info");
      serverId = Integer.parseInt(id);
    }
  }
}
