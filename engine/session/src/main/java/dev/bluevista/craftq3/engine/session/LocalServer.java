package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.console.CommandInbox;
import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * The server half of a session as the session sees it: a Quake III server with its own console and
 * cvar table, reached only through packets (a loopback link for the local player, UDP for others)
 * and console text posted to its {@link #inbox()}. Everything but {@link #inbox()} is called on the
 * server's owner thread ({@code cq3-sim}, or the client thread in synchronous mode).
 */
public interface LocalServer extends AutoCloseable {
  /**
   * A console command every implementation handles: {@code session_sync <n>} publishes {@code n} as
   * {@link Status#sync()}. The session posts it after a command to learn that the command ran (for
   * example that a {@code map} did not spawn a level).
   */
  String SYNC_COMMAND = "session_sync";

  /** Level lifecycle as the session needs it. */
  enum Phase {
    /** No level is loaded. */
    IDLE,
    /** A level runs and admits clients. */
    RUNNING,
    /** The level was dropped by an error ({@link Status#dropReason()}). */
    DROPPED,
    /** {@link #close()} ran. */
    CLOSED
  }

  /**
   * An immutable view of the server, published to the client thread after server frames.
   *
   * @param spawns levels spawned so far (a new value means a new level)
   * @param drops levels dropped by errors so far
   * @param cvarNames the lower-case names of every server cvar (for cvar forwarding)
   * @param degradations syscall degradations of every game module run so far
   * @param sync the argument of the newest {@link #SYNC_COMMAND} executed
   */
  record Status(
      Phase phase,
      String map,
      int spawns,
      int drops,
      String dropReason,
      int humans,
      int bots,
      long degradations,
      long frames,
      int sync,
      Set<String> cvarNames) {
    public Status {
      Objects.requireNonNull(phase);
      map = Objects.requireNonNullElse(map, "");
      dropReason = Objects.requireNonNullElse(dropReason, "");
      cvarNames = Set.copyOf(cvarNames);
    }

    /** Before the server ran a frame. */
    public static final Status INITIAL =
        new Status(Phase.IDLE, "", 0, 0, "", 0, 0, 0, 0, 0, Set.of());
  }

  /**
   * What a server is built from.
   *
   * @param owner the (unclaimed) owner of the server's console; the runner claims it
   * @param output receives the server's console lines; thread-safe
   * @param home the game's writable home, or null
   * @param dedicated true for a dedicated server (no local player)
   */
  record Context(
      SearchPath searchPath,
      HomeStore home,
      Path installRoot,
      String game,
      ThreadOwner owner,
      Clock clock,
      java.time.Clock wallClock,
      long seed,
      ConsoleOutput output,
      boolean dedicated) {
    public Context {
      Objects.requireNonNull(searchPath);
      Objects.requireNonNull(game);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(clock);
      Objects.requireNonNull(wallClock);
      Objects.requireNonNull(output);
    }
  }

  /** Builds servers; called on the client thread, the server is then handed to its thread. */
  @FunctionalInterface
  interface Factory {
    LocalServer create(Context context) throws IOException;
  }

  /** The owner of the server's console and tables. */
  ThreadOwner owner();

  /** The thread-safe entry for console text addressed to the server. Any thread. */
  CommandInbox inbox();

  /** Serves clients arriving on {@code transport} ({@code local} for loopback links). */
  void addTransport(PacketTransport transport, boolean local);

  /**
   * Runs one server frame at {@code nowMillis}: executes posted commands, receives packets, runs
   * every due game frame at {@code sv_fps} and transmits. Never throws for game faults; they show
   * as {@link Phase#DROPPED}.
   */
  void frame(long nowMillis);

  /** The current status; may return the same instance while nothing changed. */
  Status status();

  /** Disconnects every client, shuts the level down and closes the transports. */
  @Override
  void close();
}
