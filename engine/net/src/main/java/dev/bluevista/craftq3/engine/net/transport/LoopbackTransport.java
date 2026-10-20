package dev.bluevista.craftq3.engine.net.transport;

import dev.bluevista.craftq3.engine.net.Protocol68;
import java.io.IOException;
import java.io.Serial;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/**
 * One end of an in-process datagram link, the CraftQ3 form of Quake III's loopback address: a
 * client and a server in the same JVM exchange packets without sockets. Each direction is a
 * bounded, lock-free single-producer/single-consumer ring of preallocated slots, so one thread may
 * own each end (the render thread and the {@code cq3-sim} thread). Datagrams are copied in and out;
 * no caller buffer is retained.
 */
public final class LoopbackTransport implements PacketTransport {
  /** Datagrams buffered per direction by {@link #pair()}. */
  public static final int DEFAULT_CAPACITY = 64;

  /** The two ends of a link. */
  public record Pair(LoopbackTransport client, LoopbackTransport server) {}

  /** The synthetic address of one loopback end. */
  public static final class Address extends SocketAddress {
    @Serial private static final long serialVersionUID = 1L;
    private final String name;

    private Address(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "loopback:" + name;
    }
  }

  private final Address local;
  private final Address remote;
  private final Ring incoming;
  private final Ring outgoing;
  private volatile boolean closed;
  private LoopbackTransport peer;

  private LoopbackTransport(Address local, Address remote, Ring incoming, Ring outgoing) {
    this.local = local;
    this.remote = remote;
    this.incoming = incoming;
    this.outgoing = outgoing;
  }

  /** A connected client/server pair with {@link #DEFAULT_CAPACITY} datagrams per direction. */
  public static Pair pair() {
    return pair(DEFAULT_CAPACITY);
  }

  /** A connected pair; {@code capacity} is rounded up to a power of two. */
  public static Pair pair(int capacity) {
    if (capacity < 1 || capacity > 1 << 16)
      throw new IllegalArgumentException("Loopback capacity must be 1..65536");
    var clientAddress = new Address("client");
    var serverAddress = new Address("server");
    var toServer = new Ring(capacity);
    var toClient = new Ring(capacity);
    var client = new LoopbackTransport(clientAddress, serverAddress, toClient, toServer);
    var server = new LoopbackTransport(serverAddress, clientAddress, toServer, toClient);
    client.peer = server;
    server.peer = client;
    return new Pair(client, server);
  }

  /** The other end's address, the only valid destination. */
  public Address remoteAddress() {
    return remote;
  }

  /** Sends to the other end. */
  public boolean send(byte[] data) throws IOException {
    return send(remote, data, 0, data.length);
  }

  /**
   * Copies one datagram into the other end's queue. Returns false when that queue is full. A
   * datagram sent to a closed end is accepted and discarded, as UDP would.
   */
  @Override
  public boolean send(SocketAddress destination, byte[] data, int offset, int length)
      throws IOException {
    Objects.requireNonNull(data, "data");
    Objects.checkFromIndexSize(offset, length, data.length);
    if (closed) throw new ClosedChannelException();
    if (destination != remote)
      throw new IllegalArgumentException("Loopback sends only to " + remote);
    if (length == 0 || length > Protocol68.MAX_MSGLEN)
      throw new IllegalArgumentException("Loopback payload must contain 1..16384 bytes");
    if (peer.closed) return true;
    return outgoing.offer(data, offset, length);
  }

  @Override
  public int poll(byte[] buffer) throws IOException {
    if (closed) throw new ClosedChannelException();
    return incoming.poll(buffer);
  }

  @Override
  public SocketAddress lastSource() {
    return remote;
  }

  @Override
  public Address localAddress() {
    return local;
  }

  @Override
  public boolean isOpen() {
    return !closed;
  }

  @Override
  public void close() {
    closed = true;
  }

  /** Bounded SPSC ring: the producer owns {@code tail}, the consumer owns {@code head}. */
  private static final class Ring {
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    static {
      try {
        var lookup = MethodHandles.lookup();
        HEAD = lookup.findVarHandle(Ring.class, "head", long.class);
        TAIL = lookup.findVarHandle(Ring.class, "tail", long.class);
      } catch (ReflectiveOperationException impossible) {
        throw new ExceptionInInitializerError(impossible);
      }
    }

    private final byte[][] slots;
    private final int[] lengths;
    private final int mask;

    @SuppressWarnings("unused") // accessed through HEAD
    private volatile long head;

    @SuppressWarnings("unused") // accessed through TAIL
    private volatile long tail;

    Ring(int capacity) {
      int size = capacity == 1 ? 1 : Integer.highestOneBit(capacity - 1) << 1;
      slots = new byte[size][];
      lengths = new int[size];
      mask = size - 1;
    }

    boolean offer(byte[] data, int offset, int length) {
      long t = (long) TAIL.getOpaque(this);
      long h = (long) HEAD.getAcquire(this);
      if (t - h == slots.length) return false;
      int index = (int) t & mask;
      byte[] slot = slots[index];
      // Slots grow to the largest datagram seen; steady-state traffic allocates nothing.
      if (slot == null || slot.length < length) slots[index] = slot = new byte[length];
      System.arraycopy(data, offset, slot, 0, length);
      lengths[index] = length;
      TAIL.setRelease(this, t + 1);
      return true;
    }

    int poll(byte[] buffer) {
      long h = (long) HEAD.getOpaque(this);
      long t = (long) TAIL.getAcquire(this);
      if (h == t) return NONE;
      int index = (int) h & mask;
      int length = lengths[index];
      int result = length > buffer.length ? OVERSIZE : length;
      if (result != OVERSIZE) System.arraycopy(slots[index], 0, buffer, 0, length);
      HEAD.setRelease(this, h + 1);
      return result;
    }
  }
}
