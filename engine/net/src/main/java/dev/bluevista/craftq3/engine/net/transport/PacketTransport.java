package dev.bluevista.craftq3.engine.net.transport;

import java.io.IOException;
import java.net.SocketAddress;

/**
 * A caller-driven datagram endpoint: send one datagram to an address, poll received datagrams into
 * a caller buffer, close. Implementations own no threads and never block; codecs and sessions never
 * open sockets themselves but are fed through one of these. An instance is confined to one thread
 * (its owner); {@link LoopbackTransport} ends may be owned by two different threads.
 */
public interface PacketTransport extends AutoCloseable {
  /** {@link #poll} result: no datagram is waiting. */
  int NONE = -1;

  /** {@link #poll} result: a datagram larger than the caller buffer was discarded. */
  int OVERSIZE = -2;

  /**
   * Sends {@code data[offset, offset + length)} as one datagram. Returns false when the transport
   * would block or its queue is full; the caller decides whether to retry. Success means local
   * acceptance, not delivery.
   *
   * @throws IllegalArgumentException for an empty or oversized datagram or an unusable peer
   */
  boolean send(SocketAddress peer, byte[] data, int offset, int length) throws IOException;

  /**
   * Polls at most one datagram into {@code buffer} without waiting. Returns its length, {@link
   * #NONE} or {@link #OVERSIZE}; after a datagram (or an oversize discard) {@link #lastSource()}
   * names its sender.
   */
  int poll(byte[] buffer) throws IOException;

  /** The sender of the datagram most recently returned or discarded by {@link #poll}, or null. */
  SocketAddress lastSource();

  SocketAddress localAddress() throws IOException;

  boolean isOpen();

  /** Idempotent. */
  @Override
  void close() throws IOException;
}
