package dev.bluevista.craftq3.core.net;

import dev.bluevista.craftq3.core.net.ServerMessageCodec.Command;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Message;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Operation;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
  public enum Status {
    ACCEPTED,
    PARTIAL,
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

  public Protocol68ServerSession(int challenge, int qport) {
    this.challenge = challenge;
    channel = new Protocol68Channel(Protocol68Channel.Endpoint.SERVER, qport);
    reliable.put(0, "");
  }

  public int command(String text) {
    Objects.requireNonNull(text);
    if (reliableSequence == Integer.MAX_VALUE || reliableSequence - reliableAcknowledge >= 64)
      throw new IllegalStateException("Reliable server command window exhausted");
    new Command(reliableSequence + 1, text);
    reliable.put(++reliableSequence, text);
    while (reliable.size() > 65) reliable.pollFirstEntry();
    return reliableSequence;
  }

  public int reliableSequence() {
    return reliableSequence;
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
        && (sequence - previous.sequence() >= 32 || sequence <= previous.sequence()))
      previous = null;
    queue(List.of(new Frame(current, previous)), game.baselines());
    snapshots.put(sequence, current);
    snapshots.headMap(sequence - 32, true).clear();
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
    var encoded = new MessageWriter();
    ServerMessageCodec.write(encoded, new Message(clientSequence, operations), baselines);
    channel.queue(
        LegacyPayloadXor.server(
            encoded.bytes(), challenge, channel.outgoingSequence(), lastClientCommand));
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
    if (packet.status() != Protocol68Channel.Status.COMPLETE)
      return result(Status.IGNORED, packet.status().name());
    try {
      byte[] encrypted = packet.payload();
      var header = new MessageReader(encrypted);
      int id = header.intValue(), acknowledge = header.intValue(), commandAck = header.intValue();
      if (acknowledge < 0)
        throw new IllegalArgumentException("Negative server message acknowledgement");
      if (commandAck < 0
          || commandAck > transmittedReliable
          || (long) commandAck <= (long) reliableSequence - 64)
        throw new IllegalArgumentException(
            "Reliable server acknowledgement outside retained/sent window");
      String key = reliable.get(commandAck);
      if (key == null)
        throw new IllegalArgumentException("Missing acknowledged server command key");
      if (game == null || id != serverId) {
        messageAcknowledge = acknowledge;
        reliableAcknowledge = Math.max(reliableAcknowledge, commandAck);
        delta = false;
        return result(Status.WRONG_GAME, "Client has not acknowledged the current game");
      }
      byte[] payload = LegacyPayloadXor.client(encrypted, challenge, id, acknowledge, key);
      var message =
          ClientMessageCodec.read(new MessageReader(payload), game.checksumFeed(), reliable::get);
      var commands = new ArrayList<ClientMessageCodec.Command>();
      int nextClient = clientSequence;
      String nextKey = lastClientCommand;
      for (var command : message.commands()) {
        if (command.sequence() <= nextClient) continue;
        if ((long) command.sequence() != (long) nextClient + 1)
          throw new IllegalArgumentException("Lost reliable client commands");
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

  private static int userTime(byte[] command) {
    return ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static Received result(Status status, String diagnostic) {
    return new Received(status, null, List.of(), List.of(), diagnostic);
  }
}
