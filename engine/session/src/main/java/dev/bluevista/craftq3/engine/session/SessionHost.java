package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.client.ClientHostServices;
import dev.bluevista.craftq3.engine.common.time.Clock;
import java.nio.file.Path;

/**
 * What the platform (the Fabric mod, the headless runner, tests) provides to an {@link
 * EngineSession}, on top of the client's {@link ClientHostServices}: the installation and the
 * writable home, the monotonic clock, sockets, and callbacks for state changes and for the host's
 * own user interface. Every method is called on the client thread (the thread that calls {@link
 * EngineSession#frame}).
 */
public interface SessionHost extends ClientHostServices {
  /** The Quake III installation (the directory containing {@code baseq3}). */
  Path installRoot();

  /** The game directory to mount: {@code baseq3} or a mod. */
  default String game() {
    return "baseq3";
  }

  /**
   * The writable home root; each game writes under {@code <home>/<game>/} (configs, demos,
   * screenshots). Null runs without a home: nothing is written.
   */
  Path homeDirectory();

  /** The monotonic millisecond clock {@link EngineSession#frame} times are measured against. */
  default Clock clock() {
    return Clock.system();
  }

  /** The time-of-day clock for {@code trap_RealTime}. */
  default java.time.Clock wallClock() {
    return java.time.Clock.systemDefaultZone();
  }

  /** Datagram sockets for hosting, remote play and rcon. */
  default TransportFactory transports() {
    return TransportFactory.UDP;
  }

  /** Every console line the session prints (the host's log). */
  default void log(String line) {}

  /** The session moved from {@code previous} to {@code current}. */
  default void onStateChanged(SessionState previous, SessionState current) {}

  /**
   * The session asks the host to show ({@code true}) or hide its own user interface: shown when the
   * ui VM cannot run at all (so the user is never left on an empty screen) and by the {@code
   * host_ui} command; hidden again once the ui runs.
   */
  default void requestHostUi(boolean visible) {}
}
