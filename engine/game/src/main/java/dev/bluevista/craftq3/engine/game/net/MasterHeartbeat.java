package dev.bluevista.craftq3.engine.game.net;

import dev.bluevista.craftq3.engine.console.CvarTable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code SV_MasterHeartbeat}: an internet server ({@code dedicated 2}) sends {@code heartbeat
 * QuakeArena-1} to every configured {@code sv_master1}..{@code sv_master5} every {@link
 * #INTERVAL_MILLIS}. Off by default: {@code dedicated} is 1 and no master is configured. Name
 * resolution is injected so tests never touch the network.
 */
public final class MasterHeartbeat {
  /** {@code HEARTBEAT_MSEC}. */
  public static final int INTERVAL_MILLIS = 300 * 1000;

  /** {@code HEARTBEAT_GAME}. */
  public static final String GAME = "QuakeArena-1";

  /** {@code PORT_MASTER}. */
  public static final int MASTER_PORT = 27950;

  /** Masters the original consults. */
  public static final int MASTERS = 5;

  private final Function<String, Optional<SocketAddress>> resolver;
  private long next = Long.MIN_VALUE;

  public MasterHeartbeat(Function<String, Optional<SocketAddress>> resolver) {
    this.resolver = Objects.requireNonNull(resolver);
  }

  /** Resolves {@code host[:port]} with the platform resolver (blocking DNS). */
  public static Optional<SocketAddress> resolve(String name) {
    String host = name.strip();
    int port = MASTER_PORT;
    int colon = host.lastIndexOf(':');
    if (colon > 0 && host.indexOf(':') == colon) {
      try {
        port = Integer.parseInt(host.substring(colon + 1));
      } catch (NumberFormatException invalid) {
        return Optional.empty();
      }
      host = host.substring(0, colon);
    }
    InetSocketAddress address = new InetSocketAddress(host, port);
    return address.isUnresolved() ? Optional.empty() : Optional.of(address);
  }

  /** Masters due a heartbeat at {@code now}; empty when it is not time or heartbeats are off. */
  public List<SocketAddress> due(CvarTable cvars, long now) {
    if (cvars.integer("dedicated") != 2) return List.of();
    if (next != Long.MIN_VALUE && now < next) return List.of();
    next = now + INTERVAL_MILLIS;
    List<SocketAddress> masters = new ArrayList<>();
    for (int i = 1; i <= MASTERS; i++) {
      String name = cvars.string("sv_master" + i);
      if (!name.isBlank()) resolver.apply(name).ifPresent(masters::add);
    }
    return masters;
  }

  /** Sends the next heartbeat at the next {@link #due} call ({@code heartbeat} command). */
  public void force() {
    next = Long.MIN_VALUE;
  }
}
