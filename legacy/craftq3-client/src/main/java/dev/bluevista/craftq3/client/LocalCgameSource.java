package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Existing dense local snapshots and direct game callbacks behind the shared cgame boundary. */
final class LocalCgameSource implements CgameSource {
  private final Q3Server server;
  private int client;
  private LocalSnapshots snapshots;

  LocalCgameSource(Q3Server server) {
    this.server = Objects.requireNonNull(server);
  }

  @Override
  public Optional<dev.bluevista.craftq3.server.ExternalWorld> externalWorld() {
    return server.externalWorld();
  }

  @Override
  public Initialization initialize(int requestedClientNumber) {
    if (snapshots != null)
      throw new IllegalStateException("Local cgame source already initialized");
    int weapon = weapon(requestedClientNumber);
    var initial = new Initialization(requestedClientNumber, 0, 0, server.time(), weapon);
    client = requestedClientNumber;
    snapshots = new LocalSnapshots(server, client);
    snapshots.capture();
    return initial;
  }

  @Override
  public void refresh() {
    snapshots().capture();
  }

  @Override
  public SnapshotNumber currentSnapshot() {
    return new SnapshotNumber(snapshots().number(), snapshots().time());
  }

  @Override
  public Optional<Snapshot> snapshot(int number) {
    return snapshots().snapshot(number);
  }

  @Override
  public Map<Integer, String> configStrings() {
    return snapshots().strings();
  }

  @Override
  public Optional<String> serverCommand(int sequence) {
    return Optional.ofNullable(snapshots().command(sequence));
  }

  @Override
  public void userCommand(UserCommand command) {
    server.userCommand(client, command);
  }

  @Override
  public void clientCommand(String text) {
    server.clientCommand(client, text);
  }

  @Override
  public boolean consoleCommand(CommandParser.Command command) {
    return server.consoleCommand(command);
  }

  @Override
  public Optional<CommandSystem> additionalEngineCommands() {
    return Optional.of(server.commands());
  }

  @Override
  public Initialization restart() {
    return new Initialization(
        client,
        snapshots().number() - 1,
        snapshots().commandSequence(),
        server.time(),
        weapon(client));
  }

  private int weapon(int clientNumber) {
    return ByteBuffer.wrap(server.playerState(clientNumber))
        .order(ByteOrder.LITTLE_ENDIAN)
        .getInt(144);
  }

  private LocalSnapshots snapshots() {
    if (snapshots == null) throw new IllegalStateException("Local cgame source not initialized");
    return snapshots;
  }
}
