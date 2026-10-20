package dev.bluevista.craftq3.engine.net.chan;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.demo.DemoRecord;
import dev.bluevista.craftq3.engine.net.msg.ClientMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.ConfigstringCommands;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Client-side ownership of an established legacy connection: reliable retransmission, XOR keys,
 * level state and bounded snapshot history. A transport must validate the peer before receive. This
 * class owns no socket, clock, filesystem or VM, and never executes received command text.
 */
public final class Protocol68ClientSession {
  private static final Log LOG = Log.get(Protocol68ClientSession.class);

  public enum Status {
    ACCEPTED,
    PARTIAL,
    /** Ordinary loss or reordering (a stale or out-of-order datagram); counted, not an error. */
    DROPPED,
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
      Objects.requireNonNull(demoRecord);
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

  public record SnapshotState(Snapshot snapshot, int serverCommandSequence) {}

  private final int challenge;
  private final Protocol68Channel channel;
  private final TreeMap<Integer, String> clientCommands = new TreeMap<>();
  private final MessageWriter writer = new MessageWriter();
  private final MessageReader reader = new MessageReader();
  private int reliableSequence,
      transmittedReliableSequence,
      pendingReliableSequence,
      reliableAcknowledge;
  private int messageAcknowledge;
  private boolean requestDelta, fullSnapshots;
  private Integer presentedServerId;
  private Level level = new Level();
  private int downloadBlock;
  private long droppedPackets;

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

  /** Datagrams discarded as stale or out of order since the session started. */
  public long droppedPackets() {
    return droppedPackets;
  }

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
    return Optional.of(level.gameState(level.game.commandSequence()));
  }

  public Optional<Snapshot> snapshot() {
    return Optional.ofNullable(level.current);
  }

  /** Queue once; every subsequent message repeats unacknowledged commands in order. */
  public int command(String text) {
    if (reliableSequence == Integer.MAX_VALUE)
      throw new IllegalStateException("Reliable sequence exhausted; reconnect");
    if (reliableSequence - reliableAcknowledge >= Protocol68.MAX_RELIABLE_COMMANDS)
      throw new IllegalStateException("Reliable client command window is full");
    new ClientMessageCodec.Command(reliableSequence + 1, text);
    clientCommands.put(++reliableSequence, text);
    // Include the acknowledged key even when all 64 subsequent slots are outstanding.
    while (clientCommands.size() > Protocol68.MAX_RELIABLE_COMMANDS + 1)
      clientCommands.pollFirstEntry();
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
      writer.reset();
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
    if (packet.status() != Protocol68Channel.Status.COMPLETE) return notComplete(packet);
    try {
      byte[] encrypted = packet.payload();
      int acknowledged = acknowledgedClientCommand(encrypted);
      byte[] clear =
          LegacyPayloadXor.server(
              encrypted, challenge, packet.sequence(), clientCommands.get(acknowledged));
      var message = decode(clear, packet.sequence(), acknowledged);
      var update = new Update(new Level(level), downloadBlock, requestDelta);
      checkGameStates(update, message);
      for (var operation : message.operations()) apply(update, operation, packet.sequence());
      checkLevel(update.level);
      commit(update, acknowledged, packet.sequence());
      return new Received(
          Status.ACCEPTED,
          message,
          update.commands,
          "",
          Optional.of(new DemoRecord(packet.sequence(), clear)));
    } catch (IllegalArgumentException failure) {
      requestDelta = false;
      return new Received(Status.REJECTED, null, List.of(), failure.getMessage());
    }
  }

  /** Maps a datagram that did not complete a message; loss and reordering are counted drops. */
  private Received notComplete(Protocol68Channel.Received packet) {
    Status status =
        switch (packet.status()) {
          case PARTIAL -> Status.PARTIAL;
          case STALE, OUT_OF_ORDER -> Status.DROPPED;
          case MALFORMED, WRONG_QPORT -> Status.REJECTED;
          case COMPLETE, CONNECTIONLESS -> Status.IGNORED;
        };
    if (status == Status.DROPPED) {
      droppedPackets++;
      LOG.debug(
          "Dropped "
              + packet.status()
              + " server datagram "
              + packet.sequence()
              + " (incoming "
              + channel.incomingSequence()
              + ", "
              + droppedPackets
              + " dropped)");
    }
    return new Received(status, null, List.of(), packet.status().name());
  }

  /** The clear reliable acknowledgement header, checked against the commands actually sent. */
  private int acknowledgedClientCommand(byte[] encrypted) {
    reader.reset(encrypted, 0, encrypted.length);
    int acknowledged = reader.intValue();
    if (acknowledged < reliableAcknowledge
        || acknowledged > transmittedReliableSequence
        || clientCommands.get(acknowledged) == null)
      throw new NetFormatException("Invalid reliable client acknowledgement " + acknowledged);
    return acknowledged;
  }

  private ServerMessageCodec.Message decode(byte[] clear, int sequence, int acknowledged) {
    reader.reset(clear, 0, clear.length);
    var message =
        ServerMessageCodec.read(
            reader,
            sequence,
            level.game == null ? Baselines.EMPTY : level.game.baselines(),
            level.history::get,
            downloadBlock);
    if (message.reliableAcknowledge() != acknowledged)
      throw new NetFormatException("Transformed acknowledgement differs from its clear header");
    return message;
  }

  /** Every gamestate must cover the reliable commands before it and name a valid client slot. */
  private static void checkGameStates(Update update, ServerMessageCodec.Message message) {
    for (var operation : message.operations())
      if (operation instanceof ServerMessageCodec.GameState game) {
        if (game.commandSequence() < update.covered
            || game.clientNumber() < 0
            || game.clientNumber() >= Protocol68.MAX_CLIENTS)
          throw new NetFormatException("Invalid gamestate sequence or client number");
        update.covered = game.commandSequence();
      }
  }

  private static void apply(Update update, ServerMessageCodec.Operation operation, int sequence) {
    switch (operation) {
      case ServerMessageCodec.NoOp ignored -> {}
      case ServerMessageCodec.Download download -> {
        if (download.error() == null && download.block() == (update.downloadBlock & 65535))
          update.downloadBlock++;
      }
      case ServerMessageCodec.Command command -> applyCommand(update, command);
      case ServerMessageCodec.GameState game -> applyGameState(update, game, sequence);
      case ServerMessageCodec.Frame frame -> applyFrame(update, frame);
    }
  }

  private static void applyCommand(Update update, ServerMessageCodec.Command command) {
    Level next = update.level;
    if (command.sequence() < 1) throw new NetFormatException("Invalid server command sequence");
    if (command.sequence() <= next.commandSequence) return;
    if (command.sequence() != next.commandSequence + 1 && command.sequence() > update.covered)
      throw new NetFormatException("Reliable server command gap");
    next.commands.put(command.sequence(), command.text());
    next.commandSequence = command.sequence();
    while (next.commands.size() > Protocol68.MAX_RELIABLE_COMMANDS) next.commands.pollFirstEntry();
    next.apply(command.text());
    update.commands.add(command);
  }

  private static void applyGameState(
      Update update, ServerMessageCodec.GameState game, int sequence) {
    Level next = update.level;
    if (game.commandSequence() < next.commandSequence)
      throw new NetFormatException("Gamestate rewinds preceding reliable commands");
    next.game = game;
    update.downloadBlock = 0;
    next.gameMessageSequence = sequence;
    next.config = new ConfigstringCommands(game.configstrings());
    next.commandSequence = game.commandSequence();
    next.history.clear();
    next.snapshotCommands.clear();
    next.current = null;
    update.requestDelta = false;
    next.systemInfo();
  }

  private static void applyFrame(Update update, ServerMessageCodec.Frame frame) {
    Level next = update.level;
    if (next.game == null) throw new NetFormatException("Snapshot precedes gamestate");
    next.history.put(frame.current().sequence(), frame.current());
    next.snapshotCommands.put(frame.current().sequence(), next.commandSequence);
    while (next.history.size() > Protocol68.PACKET_BACKUP) {
      int expired = next.history.pollFirstEntry().getKey();
      next.snapshotCommands.remove(expired);
    }
    next.current = frame.current();
    update.requestDelta = true;
  }

  /** The reliable key must survive, and reliable configstring updates must fit the budget. */
  private static void checkLevel(Level next) {
    if (!next.commands.containsKey(next.commandSequence))
      throw new NetFormatException("Gamestate omitted the reliable command key");
    if (next.game != null) next.gameState(next.commandSequence);
  }

  private void commit(Update update, int acknowledged, int sequence) {
    if (update.level.gameMessageSequence != level.gameMessageSequence) presentedServerId = null;
    level = update.level;
    downloadBlock = update.downloadBlock;
    reliableAcknowledge = acknowledged;
    messageAcknowledge = sequence;
    requestDelta = update.requestDelta;
  }

  /** Candidate state of one message, committed only when every operation applied cleanly. */
  private static final class Update {
    final Level level;
    final List<ServerMessageCodec.Command> commands = new ArrayList<>();
    int downloadBlock;
    boolean requestDelta;
    int covered;

    Update(Level level, int downloadBlock, boolean requestDelta) {
      this.level = level;
      this.downloadBlock = downloadBlock;
      this.requestDelta = requestDelta;
      covered = level.commandSequence;
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

    /** The gamestate with reliable configstring updates applied; validates the text budget. */
    ServerMessageCodec.GameState gameState(int sequence) {
      return new ServerMessageCodec.GameState(
          sequence, config.strings(), game.baselines(), game.clientNumber(), game.checksumFeed());
    }

    void apply(String text) {
      String previous = config.strings().get(Protocol68.CS_SYSTEMINFO);
      config.consume(text);
      if (!Objects.equals(previous, config.strings().get(Protocol68.CS_SYSTEMINFO))) systemInfo();
    }

    void systemInfo() {
      Map<String, String> info =
          InfoString.parse(
              config.strings().getOrDefault(Protocol68.CS_SYSTEMINFO, ""),
              Protocol68.BIG_INFO_STRING);
      String id = info.get("sv_serverid");
      if (id == null) throw new NetFormatException("Missing server ID in system info");
      serverId = Integer.parseInt(id);
    }
  }
}
