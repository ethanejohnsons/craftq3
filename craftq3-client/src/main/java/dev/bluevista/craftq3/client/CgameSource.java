package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.server.UserCommand;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Borrowed engine state and command transport for one cgame connection. Implementations own their
 * history and scheduling; this interface has no socket, VM memory or presentation responsibilities.
 * Calls are serialized by the host. Q3Client never closes its source.
 */
public interface CgameSource {
  default Optional<dev.bluevista.craftq3.server.ExternalWorld> externalWorld() {
    return Optional.empty();
  }

  /**
   * CG_INIT baselines and local input seed. A source without a usable initial player state supplies
   * selectedWeapon zero, rather than manufacturing a snapshot. Times remain signed engine integers.
   */
  record Initialization(
      int clientNumber,
      int serverMessageSequence,
      int serverCommandSequence,
      int milliseconds,
      int selectedWeapon) {
    public Initialization {
      if (clientNumber < 0
          || clientNumber >= 64
          || serverMessageSequence < 0
          || serverCommandSequence < 0
          || selectedWeapon < 0
          || selectedWeapon > 255)
        throw new IllegalArgumentException("Invalid cgame initialization metadata");
    }
  }

  /** Original message number and time; number zero denotes no available snapshot. */
  record SnapshotNumber(int number, int time) {
    public SnapshotNumber {
      if (number < 0) throw new IllegalArgumentException("Invalid cgame snapshot number");
    }
  }

  /**
   * Owned canonical state; the common guest writer performs retail/1.32 layout conversion.
   * serverCommandCount -1 preserves the guest's existing count field, matching a native network
   * snapshot read which writes only serverCommandSequence. Local snapshots supply a known count.
   */
  record Snapshot(
      int time,
      int flags,
      int ping,
      byte[] player,
      List<byte[]> entities,
      byte[] areaMask,
      int serverCommandSequence,
      int serverCommandCount) {
    public Snapshot {
      if (flags < 0
          || flags > 255
          || ping < 0
          || player.length != 468
          || entities.size() > 256
          || areaMask.length > 32
          || serverCommandSequence < 0
          || serverCommandCount < -1
          || serverCommandCount > serverCommandSequence)
        throw new IllegalArgumentException("Invalid canonical cgame snapshot");
      player = player.clone();
      areaMask = Arrays.copyOf(areaMask, 32);
      entities =
          entities.stream()
              .map(
                  entity -> {
                    if (entity.length != 208)
                      throw new IllegalArgumentException("Invalid canonical entity state");
                    return entity.clone();
                  })
              .toList();
    }

    @Override
    public byte[] player() {
      return player.clone();
    }

    @Override
    public List<byte[]> entities() {
      return entities.stream().map(byte[]::clone).toList();
    }

    @Override
    public byte[] areaMask() {
      return areaMask.clone();
    }
  }

  /** Original CG_DRAW_ACTIVE_FRAME flag; recorded presentation uses guest demo behavior. */
  default boolean demoPlayback() {
    return false;
  }

  /** Selects/validates the requested client and prepares the initial presentation configstrings. */
  Initialization initialize(int requestedClientNumber);

  /** Makes completed engine state available without generating synthetic remote packet numbers. */
  void refresh();

  SnapshotNumber currentSnapshot();

  /** Missing/expired numbers return empty; a future number is an invalid guest request. */
  Optional<Snapshot> snapshot(int number);

  /** Immutable cgame-visible strings, which may lag the wire state until commands are consumed. */
  Map<Integer, String> configStrings();

  /**
   * Consumes one reliable command for presentation. A remote source may apply configstrings and
   * assemble large-string fragments here; hidden fragments return empty. A remote source may fail
   * expired history while the local source retains its historical empty result. A future number is
   * an invalid guest request. Returned text is tokenized, never executed as an engine command
   * buffer by Q3Client.
   */
  Optional<String> serverCommand(int sequence);

  void userCommand(UserCommand command);

  void clientCommand(String text);

  /** Local game-console fallback only. Returning false forwards the same text to clientCommand. */
  default boolean consoleCommand(CommandParser.Command command) {
    return false;
  }

  /** Optional local server engine buffer needing the same play handler as the client buffer. */
  default Optional<CommandSystem> additionalEngineCommands() {
    return Optional.empty();
  }

  /**
   * Called after refresh for a renderer restart. Prepare coherent configstrings and CG_INIT
   * baselines, retaining this connection's client number. Q3Client keeps its presentation time and
   * current weapon across this restart; only the returned sequence/client fields seed CG_INIT.
   */
  Initialization restart();
}
