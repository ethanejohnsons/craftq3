package dev.bluevista.craftq3.client.demo;

import dev.bluevista.craftq3.core.demo.DemoRecord;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Command;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Message;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Operation;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.server.ConfigStrings;
import dev.bluevista.craftq3.server.Q3Server;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical protocol-68 demo messages from completed local server frames. This is an authored local
 * transport, not a model of native packet scheduling; original qagame remains responsible for all
 * state, visibility, player movement and commands.
 */
public final class LocalDemoFeed {
  public static final int MAX_FRAME_COMMANDS = 64;

  /** Owned raw payload and matching decoded metadata for Protocol68DemoRecorder.accept. */
  public record Emission(DemoRecord record, Message message) {
    public Emission {
      Objects.requireNonNull(record);
      Objects.requireNonNull(message);
    }
  }

  private final Q3Server server;
  private final int client;
  private final GameState initial;
  private Map<Integer, String> strings;
  private Snapshot previous;
  private int lastFrame = -1, sequence, commandSequence, serverCommandCursor;

  /**
   * Borrows an initialized server and connected client. A new recording starts from current game
   * state and skips old transient commands; commands emitted after construction are captured.
   */
  public LocalDemoFeed(Q3Server server, int client, int checksumFeed) {
    this.server = Objects.requireNonNull(server);
    // The public player-state boundary validates connection, client index and guest state now.
    server.playerState(client);
    this.client = client;
    strings = server.configstrings().snapshot();
    initial = new GameState(0, strings, Baselines.EMPTY, client, checksumFeed);
    serverCommandCursor = server.currentServerCommandSequence();
  }

  public GameState initialGameState() {
    return initial;
  }

  /** Starts at one, so recorder.start produces the initial gamestate record at sequence zero. */
  public int nextSequence() {
    return Math.addExact(sequence, 1);
  }

  /**
   * First capture emits a full snapshot; each newer completed server frame emits a delta from the
   * previous capture. Repeated calls in one frame return empty. Encoding failure publishes none of
   * the feed's string, command, sequence or snapshot state, so the host can report/stop cleanly.
   */
  public Optional<Emission> capture() {
    int frame = server.frameNumber();
    if (frame == lastFrame) return Optional.empty();
    if (frame < lastFrame)
      throw new IllegalStateException("Local demo server frame moved backwards");
    var current = server.configstrings().snapshot();
    var operations = new ArrayList<Operation>();
    int nextCommand = commandSequence;
    for (int index = 0; index < ConfigStrings.MAX_STRINGS; index++) {
      String value = current.getOrDefault(index, "");
      if (!value.equals(strings.getOrDefault(index, ""))) {
        for (String command : configstringCommands(index, value))
          operations.add(new Command(nextCommand = Math.addExact(nextCommand, 1), command));
      }
    }
    int cursor = serverCommandCursor;
    for (var command : server.commandsSince(serverCommandCursor)) {
      cursor = command.sequence();
      if (command.client() == -1 || command.client() == client)
        operations.add(new Command(nextCommand = Math.addExact(nextCommand, 1), command.text()));
    }
    if (operations.size() > MAX_FRAME_COMMANDS)
      throw new IllegalStateException("Local demo frame exceeds 64 reliable commands");
    int next = nextSequence();
    var entities = server.entitySnapshot(client);
    var snapshot =
        new Snapshot(
            next,
            server.time(),
            server.snapshotFlags(),
            entities.areaMask().copy(),
            server.playerState(client),
            entities.entities());
    operations.add(new Frame(snapshot, previous));
    var message = new Message(0, operations);
    var encoded = new MessageWriter();
    ServerMessageCodec.write(encoded, message, Baselines.EMPTY);
    var emitted = new Emission(new DemoRecord(next, encoded.bytes()), message);
    strings = current;
    previous = snapshot;
    sequence = next;
    commandSequence = nextCommand;
    serverCommandCursor = cursor;
    lastFrame = frame;
    return Optional.of(emitted);
  }

  /**
   * Native-observed SV_SendConfigstring text: cs below 1000 bytes, otherwise 999-byte bcs chunks.
   * Contents remain literal inside quotes, including quotes, control characters and percent signs;
   * the protocol's message-string codec performs its own subsequent byte conversion.
   */
  public static List<String> configstringCommands(int index, String value) {
    return dev.bluevista.craftq3.core.net.ConfigstringCommands.outbound(index, value);
  }
}
