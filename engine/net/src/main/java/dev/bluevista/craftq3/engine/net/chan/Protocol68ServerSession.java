package dev.bluevista.craftq3.engine.net.chan;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.msg.ClientMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Command;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Message;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One established protocol-68 server connection, without sockets or game policy. The host verifies
 * the peer, accepts connections, validates pure/userinfo commands, and owns qagame
 * admission/timing. Invalid messages consume netchan sequencing but never partially publish
 * application commands.
 */
public final class Protocol68ServerSession {
  private static final Log LOG = Log.get(Protocol68ServerSession.class);

  public enum Status {
    ACCEPTED,
    PARTIAL,
    /** Ordinary loss or reordering (a stale or out-of-order datagram); counted, not an error. */
    DROPPED,
    IGNORED,
    WRONG_GAME,
    REJECTED
  }

  public record Received(
      Status status,
      ClientMessageCodec.Message message,
      List<ClientMessageCodec.Command> commands,
      List<byte[]> userCommands,
      String diagnostic) {
    public Received {
      commands = List.copyOf(commands);
      userCommands = userCommands.stream().map(byte[]::clone).toList();
    }

    @Override
    public List<byte[]> userCommands() {
      return userCommands.stream().map(byte[]::clone).toList();
    }
  }

  private final int challenge;
  private final Protocol68Channel channel;
  private final TreeMap<Integer, String> reliable = new TreeMap<>();
  private final TreeMap<Integer, Snapshot> snapshots = new TreeMap<>();
  private int reliableSequence, reliableAcknowledge, transmittedReliable, pendingReliable;
  private int clientSequence, messageAcknowledge, serverId, gameMessageSequence;
  private int lastUserTime = Integer.MIN_VALUE;
  private String lastClientCommand = "";
  private GameState game;
  private boolean delta;
  private long droppedPackets;
  private final MessageWriter writer = new MessageWriter();
  private final MessageReader reader = new MessageReader();

  public Protocol68ServerSession(int challenge, int qport) {
    this.challenge = challenge;
    channel = new Protocol68Channel(Protocol68Channel.Endpoint.SERVER, qport);
    reliable.put(0, "");
  }

  public int command(String text) {
    Objects.requireNonNull(text);
    if (reliableSequence == Integer.MAX_VALUE
        || reliableSequence - reliableAcknowledge >= Protocol68.MAX_RELIABLE_COMMANDS)
      throw new IllegalStateException("Reliable server command window exhausted");
    new Command(reliableSequence + 1, text);
    reliable.put(++reliableSequence, text);
    while (reliable.size() > Protocol68.MAX_RELIABLE_COMMANDS + 1) reliable.pollFirstEntry();
    return reliableSequence;
  }

  public int reliableSequence() {
    return reliableSequence;
  }

  /** Datagrams discarded as stale or out of order since the session started. */
  public long droppedPackets() {
    return droppedPackets;
  }

  public int reliableAcknowledge() {
    return reliableAcknowledge;
  }

  public int clientCommandSequence() {
    return clientSequence;
  }

  public int messageAcknowledge() {
    return messageAcknowledge;
  }

  public int nextMessageSequence() {
    return channel.outgoingSequence();
  }

  public int gameMessageSequence() {
    return gameMessageSequence;
  }

  /** Fast restart changes the server ID while retaining this level's baselines and history. */
  public void serverId(int value) {
    serverId = value;
  }

  public boolean hasPendingPacket() {
    return channel.hasPendingPacket();
  }

  /** The host supplies current configstrings/baselines; this connection owns reliable numbering. */
  public void queueGameState(GameState state, int id) {
    Objects.requireNonNull(state);
    var initial =
        new GameState(
            reliableSequence,
            state.configstrings(),
            state.baselines(),
            state.clientNumber(),
            state.checksumFeed());
    queue(List.of(initial), state.baselines());
    game = initial;
    serverId = id;
    gameMessageSequence = channel.outgoingSequence();
    snapshots.clear();
    delta = false;
    lastUserTime = Integer.MIN_VALUE;
  }

  /**
   * Uses an acknowledged snapshot only while its complete state remains in this level's history.
   */
  public void queueSnapshot(
      int time, int flags, byte[] areaMask, byte[] player, List<byte[]> entities) {
    if (game == null) throw new IllegalStateException("Send a gamestate before snapshots");
    int sequence = channel.outgoingSequence();
    var current = new Snapshot(sequence, time, flags, areaMask, player, entities);
    Snapshot previous = delta ? snapshots.get(messageAcknowledge) : null;
    if (previous != null
        && (sequence - previous.sequence() >= Protocol68.PACKET_BACKUP
            || sequence <= previous.sequence())) previous = null;
    queue(List.of(new Frame(current, previous)), game.baselines());
    snapshots.put(sequence, current);
    snapshots.headMap(sequence - Protocol68.PACKET_BACKUP, true).clear();
  }

  public void queueDownload(ServerMessageCodec.Download download) {
    queue(List.of(download), game == null ? SnapshotDeltaCodec.Baselines.EMPTY : game.baselines());
  }

  public void queueKeepalive() {
    queue(List.of(), game == null ? SnapshotDeltaCodec.Baselines.EMPTY : game.baselines());
  }

  private void queue(List<Operation> tail, SnapshotDeltaCodec.Baselines baselines) {
    if (channel.hasPendingPacket())
      throw new IllegalStateException("Drain pending datagrams first");
    var operations = new ArrayList<Operation>();
    reliable
        .tailMap(reliableAcknowledge, false)
        .forEach((number, text) -> operations.add(new Command(number, text)));
    operations.addAll(tail);
    writer.reset();
    ServerMessageCodec.write(writer, new Message(clientSequence, operations), baselines);
    channel.queue(
        LegacyPayloadXor.server(
            writer.bytes(), challenge, channel.outgoingSequence(), lastClientCommand));
    pendingReliable = reliableSequence;
  }

  public Optional<byte[]> pollPacket() {
    var packet = channel.pollPacket();
    if (packet.isPresent() && !channel.hasPendingPacket()) transmittedReliable = pendingReliable;
    return packet;
  }

  public Received receive(byte[] datagram) {
    var packet = channel.receive(datagram);
    if (packet.status() == Protocol68Channel.Status.PARTIAL) return result(Status.PARTIAL, "");
    if (packet.status().isDrop()) return dropped(packet);
    if (packet.status() != Protocol68Channel.Status.COMPLETE)
      return result(Status.IGNORED, packet.status().name());
    try {
      byte[] encrypted = packet.payload();
      reader.reset(encrypted, 0, encrypted.length);
      int id = reader.intValue(), acknowledge = reader.intValue(), commandAck = reader.intValue();
      if (acknowledge < 0) throw new NetFormatException("Negative server message acknowledgement");
      if (commandAck < 0
          || commandAck > transmittedReliable
          || (long) commandAck <= (long) reliableSequence - Protocol68.MAX_RELIABLE_COMMANDS)
        throw new NetFormatException(
            "Reliable server acknowledgement outside retained/sent window");
      String key = reliable.get(commandAck);
      if (key == null) throw new NetFormatException("Missing acknowledged server command key");
      if (game == null || id != serverId) {
        messageAcknowledge = acknowledge;
        reliableAcknowledge = Math.max(reliableAcknowledge, commandAck);
        delta = false;
        return result(Status.WRONG_GAME, "Client has not acknowledged the current game");
      }
      byte[] payload = LegacyPayloadXor.client(encrypted, challenge, id, acknowledge, key);
      reader.reset(payload, 0, payload.length);
      var message = ClientMessageCodec.read(reader, game.checksumFeed(), reliable::get);
      var commands = new ArrayList<ClientMessageCodec.Command>();
      int nextClient = clientSequence;
      String nextKey = lastClientCommand;
      for (var command : message.commands()) {
        if (command.sequence() <= nextClient) continue;
        if ((long) command.sequence() != (long) nextClient + 1)
          throw new NetFormatException("Lost reliable client commands");
        commands.add(command);
        nextClient = command.sequence();
        nextKey = command.text();
      }
      var movement = new ArrayList<byte[]>();
      int nextTime = lastUserTime;
      if (message.movement() != null) {
        var incoming = message.movement().commands();
        int finalTime = userTime(incoming.getLast());
        for (byte[] command : incoming) {
          int time = userTime(command);
          if (time > finalTime || time <= nextTime) continue;
          movement.add(command);
          nextTime = time;
        }
      }
      clientSequence = nextClient;
      lastClientCommand = nextKey;
      lastUserTime = nextTime;
      messageAcknowledge = acknowledge;
      reliableAcknowledge = Math.max(reliableAcknowledge, commandAck);
      if (message.movement() != null) delta = message.movement().requestDelta();
      return new Received(Status.ACCEPTED, message, commands, movement, "");
    } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
      return result(Status.REJECTED, failure.getMessage());
    }
  }

  private Received dropped(Protocol68Channel.Received packet) {
    droppedPackets++;
    LOG.debug(
        "Dropped "
            + packet.status()
            + " client datagram "
            + packet.sequence()
            + " ("
            + droppedPackets
            + " dropped)");
    return result(Status.DROPPED, packet.status().name());
  }

  private static int userTime(byte[] command) {
    return (command[0] & 255)
        | (command[1] & 255) << 8
        | (command[2] & 255) << 16
        | command[3] << 24;
  }

  private static Received result(Status status, String diagnostic) {
    return new Received(status, null, List.of(), List.of(), diagnostic);
  }
}
