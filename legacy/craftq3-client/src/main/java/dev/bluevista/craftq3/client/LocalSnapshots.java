package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A bounded local transport; only completed server frames create a newer snapshot number. */
final class LocalSnapshots {
  private final Q3Server server;
  private final int client;
  private final Map<Integer, CgameSource.Snapshot> snapshots = new LinkedHashMap<>();
  private final Map<Integer, String> commands = new LinkedHashMap<>();
  private Map<Integer, String> strings;
  private int lastFrame = -1, number, commandSequence, serverSequence;

  LocalSnapshots(Q3Server server, int client) {
    this.server = server;
    this.client = client;
    strings = server.configstrings().snapshot();
  }

  int number() {
    return number;
  }

  int commandSequence() {
    return commandSequence;
  }

  int time() {
    return snapshots.get(number).time();
  }

  Map<Integer, String> strings() {
    return strings;
  }

  void capture() {
    if (server.frameNumber() == lastFrame) return;
    int previous = commandSequence;
    Map<Integer, String> current = server.configstrings().snapshot();
    for (int i = 0; i < 1024; i++)
      if (!strings.getOrDefault(i, "").equals(current.getOrDefault(i, ""))) append("cs " + i);
    strings = current;
    for (var command : server.commandsSince(serverSequence)) {
      serverSequence = command.sequence();
      if (command.client() == -1 || command.client() == client) append(command.text());
    }
    var entities = server.entitySnapshot(client);
    snapshots.put(
        ++number,
        new CgameSource.Snapshot(
            server.time(),
            server.snapshotFlags(),
            0,
            server.playerState(client),
            entities.entities(),
            entities.areaMask().copy(),
            commandSequence,
            commandSequence - previous));
    if (snapshots.size() > 32) snapshots.remove(snapshots.keySet().iterator().next());
    lastFrame = server.frameNumber();
  }

  private void append(String text) {
    commands.put(++commandSequence, text);
    if (commands.size() > 256) commands.remove(commands.keySet().iterator().next());
  }

  String command(int sequence) {
    if (sequence > commandSequence)
      throw new IllegalArgumentException("Future reliable server command");
    return commands.get(sequence);
  }

  Optional<CgameSource.Snapshot> snapshot(int sequence) {
    if (sequence > number) throw new IllegalArgumentException("Future cgame snapshot");
    return Optional.ofNullable(snapshots.get(sequence));
  }

  int read(QvmMemory memory, ClientAbi abi, int sequence, int pointer) {
    var snapshot = snapshot(sequence);
    if (snapshot.isEmpty()) return 0;
    CgameSnapshotWriter.write(memory, abi, snapshot.orElseThrow(), pointer);
    return 1;
  }
}
