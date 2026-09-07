package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.client.demo.DemoPlayback;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.net.ConfigstringCommands;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One recorded level's original cgame view, with native demo command/configstring behavior. */
public final class DemoCgameSource implements CgameSource {
  private static final java.util.regex.Pattern INTEGER_PREFIX =
      java.util.regex.Pattern.compile("^[\\s]*([+-]?[0-9]+)");
  private final DemoPlayback playback;
  private ConfigstringCommands config;
  private int generation, clientNumber, lastExecuted, serverId;

  public DemoCgameSource(DemoPlayback playback) {
    this.playback = Objects.requireNonNull(playback);
  }

  @Override
  public boolean demoPlayback() {
    return true;
  }

  @Override
  public Initialization initialize(int requestedClientNumber) {
    if (config != null) throw new IllegalStateException("Demo cgame source already initialized");
    var game =
        playback
            .gameState()
            .orElseThrow(() -> new IllegalStateException("Demo requires gamestate"));
    if (requestedClientNumber != game.clientNumber())
      throw new IllegalArgumentException("Demo client differs from recorded client");
    generation = playback.generation();
    clientNumber = game.clientNumber();
    lastExecuted = game.commandSequence();
    config = new ConfigstringCommands(game.configstrings());
    systemInfo();
    return initialization(playback.gameMessageSequence());
  }

  @Override
  public void refresh() {
    checkLevel();
  }

  @Override
  public SnapshotNumber currentSnapshot() {
    checkLevel();
    return playback
        .snapshot()
        .map(s -> new SnapshotNumber(s.sequence(), s.time()))
        .orElseGet(() -> new SnapshotNumber(0, 0));
  }

  @Override
  public Optional<Snapshot> snapshot(int number) {
    int current = currentSnapshot().number();
    if (number > current) throw new IllegalArgumentException("Future demo snapshot");
    if (number < 1 || number <= current - 32) return Optional.empty();
    return playback
        .snapshot(number)
        .map(
            state -> {
              var s = state.snapshot();
              return new Snapshot(
                  s.time(),
                  s.flags(),
                  0,
                  s.player(),
                  s.entities(),
                  s.areaMask(),
                  state.commandSequence(),
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
    if (sequence > playback.commandSequence())
      throw new IllegalArgumentException("Future demo command");
    if (sequence <= playback.commandSequence() - 64) return Optional.empty();
    String text = playback.command(sequence);
    lastExecuted = sequence;
    var command = CommandParser.tokenize(text);
    if (command.argument(0).equals("disconnect"))
      throw new IllegalStateException("Recorded server disconnected: " + command.argument(1));
    var result = config.consume(text);
    systemInfo();
    return result;
  }

  @Override
  public void userCommand(UserCommand command) {
    checkLevel();
    Objects.requireNonNull(command);
  }

  @Override
  public void clientCommand(String text) {
    checkLevel();
    Objects.requireNonNull(text);
  }

  @Override
  public Initialization restart() {
    checkLevel();
    lastExecuted = Math.max(lastExecuted, playback.commandSequence() - 64);
    while (lastExecuted < playback.commandSequence()) {
      int requested = lastExecuted + 1;
      serverCommand(requested);
      if (lastExecuted < requested) lastExecuted = requested;
    }
    int snapshot = currentSnapshot().number();
    return initialization(snapshot == 0 ? playback.gameMessageSequence() : snapshot - 1);
  }

  public int serverId() {
    checkLevel();
    return serverId;
  }

  public int lastExecutedCommand() {
    checkLevel();
    return lastExecuted;
  }

  private Initialization initialization(int message) {
    var latest = playback.snapshot();
    int time = latest.map(s -> s.time()).orElse(0);
    int weapon =
        latest
            .map(s -> ByteBuffer.wrap(s.player()).order(ByteOrder.LITTLE_ENDIAN).getInt(144))
            .orElse(0);
    return new Initialization(clientNumber, message, lastExecuted, time, weapon);
  }

  private void checkLevel() {
    if (config == null) throw new IllegalStateException("Demo cgame is not initialized");
    if (generation != playback.generation())
      throw new IllegalStateException("Demo gamestate changed; replace cgame source");
  }

  private void systemInfo() {
    String value =
        InfoString.parse(config.strings().getOrDefault(1, ""), 8192)
            .getOrDefault("sv_serverid", "");
    var match = INTEGER_PREFIX.matcher(value);
    serverId =
        match.find()
            ? new java.math.BigInteger(match.group(1))
                .max(java.math.BigInteger.valueOf(Long.MIN_VALUE))
                .min(java.math.BigInteger.valueOf(Long.MAX_VALUE))
                .intValue()
            : 0;
  }
}
