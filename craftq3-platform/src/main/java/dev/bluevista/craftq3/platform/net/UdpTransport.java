package dev.bluevista.craftq3.platform.net;

import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.Objects;

/** A caller-driven, nonblocking UDP socket pinned to one explicitly resolved remote endpoint. */
public final class UdpTransport implements AutoCloseable {
  public enum Status {
    EMPTY,
    PACKET,
    OVERSIZE,
    EMPTY_DATAGRAM
  }

  /** Received bytes are owned; discarded or absent datagrams have no payload. */
  public record Poll(Status status, byte[] payload) {
    public Poll {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(payload, "payload");
      if (status == Status.PACKET) {
        if (payload.length == 0 || payload.length > Protocol68Channel.MAX_MESSAGE)
          throw new IllegalArgumentException("Invalid received packet size");
      } else if (payload.length != 0) {
        throw new IllegalArgumentException("Non-packet result contains a payload");
      }
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  private static final Poll EMPTY = new Poll(Status.EMPTY, new byte[0]);
  private static final Poll OVERSIZE = new Poll(Status.OVERSIZE, new byte[0]);
  private static final Poll EMPTY_DATAGRAM = new Poll(Status.EMPTY_DATAGRAM, new byte[0]);
  private final InetSocketAddress peer;
  private final DatagramChannel channel;
  // The extra byte distinguishes an oversized UDP datagram from a valid full-sized message.
  private final ByteBuffer receive = ByteBuffer.allocate(Protocol68Channel.MAX_MESSAGE + 1);

  private UdpTransport(InetSocketAddress peer, DatagramChannel channel) {
    this.peer = peer;
    this.channel = channel;
  }

  /** Performs no DNS lookup. The OS assigns a local address/ephemeral port when connecting. */
  public static UdpTransport open(InetSocketAddress peer) throws IOException {
    Objects.requireNonNull(peer, "peer");
    if (peer.isUnresolved()
        || peer.getPort() == 0
        || peer.getAddress().isAnyLocalAddress()
        || peer.getAddress().isMulticastAddress())
      throw new IllegalArgumentException("UDP peer must be a resolved unicast address and port");
    var family =
        peer.getAddress() instanceof Inet6Address
            ? StandardProtocolFamily.INET6
            : StandardProtocolFamily.INET;
    DatagramChannel channel = DatagramChannel.open(family);
    try {
      channel.configureBlocking(false);
      channel.connect(peer);
      return new UdpTransport(peer, channel);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        channel.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public InetSocketAddress peer() {
    return peer;
  }

  public InetSocketAddress localAddress() throws IOException {
    return (InetSocketAddress) channel.getLocalAddress();
  }

  public boolean isOpen() {
    return channel.isOpen();
  }

  /**
   * Sends one datagram, retaining no caller buffer. False means the socket would block; callers
   * decide whether and when to retry. Success is local acceptance, not acknowledgement of delivery.
   */
  public boolean send(byte[] payload) throws IOException {
    Objects.requireNonNull(payload, "payload");
    if (payload.length == 0 || payload.length > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("UDP payload must contain 1..16384 bytes");
    int sent = channel.write(ByteBuffer.wrap(payload));
    if (sent == 0) return false;
    if (sent != payload.length) throw new IOException("Partial UDP datagram write");
    return true;
  }

  /**
   * Polls at most one datagram without waiting. A connected channel filters other source addresses
   * and ports before delivery. The shared receive buffer makes this object caller-thread confined.
   */
  public Poll poll() throws IOException {
    receive.clear();
    if (channel.receive(receive) == null) return EMPTY;
    int length = receive.position();
    if (length > Protocol68Channel.MAX_MESSAGE) return OVERSIZE;
    if (length == 0) return EMPTY_DATAGRAM;
    return new Poll(Status.PACKET, Arrays.copyOf(receive.array(), length));
  }

  /** Safe to repeat; no worker, selector or background task is owned. */
  @Override
  public void close() throws IOException {
    channel.close();
  }
}
