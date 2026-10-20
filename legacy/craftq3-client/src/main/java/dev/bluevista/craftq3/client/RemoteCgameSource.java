package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.net.ConfigstringCommands;
import dev.bluevista.craftq3.core.net.Protocol68ClientSession;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * One remote level's cgame view of a borrowed protocol-68 connection. The host receives packets and
 * schedules input outside this object. Configstrings advance only when cgame fetches their reliable
 * commands, independently of the session's newest wire state.
 */
public final class RemoteCgameSource implements CgameSource {
  /** A newly received gamestate requires the host to replace this level's cgame and map. */
  public static final class LevelChangedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private LevelChangedException() {
      super("Remote gamestate changed; replace the cgame source");
    }
  }

  /** Server-requested disconnect, consumed at the same boundary as native GetServerCommand. */
  public static final class DisconnectedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private DisconnectedException(String reason) {
      super(reason.isEmpty() ? "Server disconnected" : "Server disconnected - " + reason);
    }
  }

  private final Protocol68ClientSession session;
  private final Consumer<UserCommand> input;
  private final IntUnaryOperator snapshotPing;
  private final Consumer<String> systemInfo;
  private ConfigstringCommands config;
  private int gameMessageSequence, clientNumber, lastExecuted;

  /** Ping zero is appropriate for recorded/replayed packets without transport timing metadata. */
  public RemoteCgameSource(Protocol68ClientSession session, Consumer<UserCommand> input) {
    this(session, input, ignored -> 0);
  }

  /** The ping provider receives the original snapshot message number and returns milliseconds. */
  public RemoteCgameSource(
      Protocol68ClientSession session, Consumer<UserCommand> input, IntUnaryOperator snapshotPing) {
    this(session, input, snapshotPing, ignored -> {});
  }

  /**
   * The systeminfo callback runs at initialization and on consumed changes, before guest access.
   */
  public RemoteCgameSource(
      Protocol68ClientSession session,
      Consumer<UserCommand> input,
      IntUnaryOperator snapshotPing,
      Consumer<String> systemInfo) {
    this.session = Objects.requireNonNull(session);
    this.input = Objects.requireNonNull(input);
    this.snapshotPing = Objects.requireNonNull(snapshotPing);
    this.systemInfo = Objects.requireNonNull(systemInfo);
  }

  @Override
  public Initialization initialize(int requestedClientNumber) {
    if (config != null) throw new IllegalStateException("Remote cgame source already initialized");
    var game =
        session
            .initialGameState()
            .orElseThrow(() -> new IllegalStateException("Remote cgame requires a gamestate"));
    if (requestedClientNumber != game.clientNumber())
      throw new IllegalArgumentException("Remote cgame client differs from the server assignment");
    gameMessageSequence = session.gameStateMessageSequence();
    clientNumber = game.clientNumber();
    lastExecuted = game.commandSequence();
    config = new ConfigstringCommands(game.configstrings());
    applySystemInfo();
    return initialization(gameMessageSequence, lastExecuted);
  }

  @Override
  public void refresh() {
    checkLevel();
  }

  @Override
  public SnapshotNumber currentSnapshot() {
    checkLevel();
    return session
        .snapshot()
        .map(snapshot -> new SnapshotNumber(snapshot.sequence(), snapshot.time()))
        .orElseGet(() -> new SnapshotNumber(0, 0));
  }

  @Override
  public Optional<Snapshot> snapshot(int number) {
    int current = currentSnapshot().number();
    if (number > current) throw new IllegalArgumentException("Future remote cgame snapshot");
    if (number < 1 || number <= current - Protocol68ClientSession.SNAPSHOT_HISTORY)
      return Optional.empty();
    return session
        .snapshotState(number)
        .map(
            state -> {
              var snapshot = state.snapshot();
              return new Snapshot(
                  snapshot.time(),
                  snapshot.flags(),
                  snapshotPing.applyAsInt(number),
                  snapshot.player(),
                  snapshot.entities(),
                  snapshot.areaMask(),
                  state.serverCommandSequence(),
                  -1);
            });
  }

  @Override
  public Map<Integer, String> configStrings() {
    checkLevel();
    return config.strings();
  }

  @Override
  public Optional<String> serverCommand(int sequence) {
    checkLevel();
    int latest = session.serverCommandSequence();
    if (sequence > latest) throw new IllegalArgumentException("Future remote server command");
    if (sequence < 0 || sequence <= latest - Protocol68ClientSession.RELIABLE_WINDOW)
      throw new IllegalStateException("Remote server command cycled out");
    String text =
        session
            .serverCommand(sequence)
            .orElseThrow(
                () -> new IllegalStateException("Missing remote server command " + sequence));
    // Native retrieval may execute a retained command repeatedly or move this cursor backward.
    lastExecuted = sequence;
    var command = CommandParser.tokenize(text);
    if (command.argument(0).equals("disconnect"))
      throw new DisconnectedException(command.argument(1));
    String previous = config.strings().get(1);
    var result = config.consume(text);
    if (!Objects.equals(previous, config.strings().get(1))) applySystemInfo();
    return result;
  }

  @Override
  public void userCommand(UserCommand command) {
    checkLevel();
    input.accept(Objects.requireNonNull(command));
  }

  @Override
  public void clientCommand(String text) {
    checkLevel();
    session.command(text);
  }

  @Override
  public Initialization restart() {
    checkLevel();
    while (lastExecuted < session.serverCommandSequence()) serverCommand(lastExecuted + 1);
    // Let the new VM fetch the newest retained snapshot again after CG_INIT.
    int snapshot = currentSnapshot().number();
    return initialization(snapshot == 0 ? gameMessageSequence : snapshot - 1, lastExecuted);
  }

  private Initialization initialization(int messageSequence, int commandSequence) {
    var snapshot = session.snapshot();
    int time = snapshot.map(value -> value.time()).orElse(0);
    int weapon =
        snapshot
            .map(
                value -> ByteBuffer.wrap(value.player()).order(ByteOrder.LITTLE_ENDIAN).getInt(144))
            .orElse(0);
    return new Initialization(clientNumber, messageSequence, commandSequence, time, weapon);
  }

  private void checkLevel() {
    if (config == null) throw new IllegalStateException("Remote cgame source not initialized");
    if (session.gameStateMessageSequence() != gameMessageSequence)
      throw new LevelChangedException();
  }

  private void applySystemInfo() {
    String info = config.strings().getOrDefault(1, "");
    session.usePresentedServerId(Integer.parseInt(InfoString.parse(info, 8192).get("sv_serverid")));
    systemInfo.accept(info);
  }
}
