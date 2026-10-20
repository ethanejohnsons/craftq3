package dev.bluevista.craftq3.platform.net;

import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.Objects;

/**
 * Caller-driven nonblocking discovery socket. The browser owns request tracking, reply filtering
 * and rate limits; this transport retains each received source address and performs no DNS.
 */
public final class DiscoveryTransport implements AutoCloseable {
  public enum Family {
    IPV4,
    IPV6
  }

  public enum Status {
    EMPTY,
    PACKET,
    OVERSIZE,
    EMPTY_DATAGRAM
  }

  public record Poll(Status status, InetSocketAddress source, byte[] payload) {
    public Poll {
      Objects.requireNonNull(status);
      Objects.requireNonNull(payload);
      if ((status == Status.EMPTY) != (source == null))
        throw new IllegalArgumentException("Received datagrams require their source address");
      if (source != null && source.isUnresolved())
        throw new IllegalArgumentException("Received source must be resolved");
      if (status == Status.PACKET) {
        if (payload.length == 0 || payload.length > Protocol68Channel.MAX_MESSAGE)
          throw new IllegalArgumentException("Invalid discovery packet size");
      } else if (payload.length != 0)
        throw new IllegalArgumentException("Discarded or absent datagrams cannot carry payloads");
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  private static final Poll EMPTY = new Poll(Status.EMPTY, null, new byte[0]);
  private final Family family;
  private final boolean broadcast;
  private final DatagramChannel channel;
  private final ByteBuffer receive = ByteBuffer.allocate(Protocol68Channel.MAX_MESSAGE + 1);

  private DiscoveryTransport(Family family, boolean broadcast, DatagramChannel channel) {
    this.family = family;
    this.broadcast = broadcast;
    this.channel = channel;
  }

  /** Opens an ephemeral local socket. Broadcast is an explicit IPv4-only capability. */
  public static DiscoveryTransport open(Family family, boolean broadcast) throws IOException {
    Objects.requireNonNull(family);
    if (broadcast && family != Family.IPV4)
      throw new IllegalArgumentException("Discovery broadcast requires IPv4");
    DatagramChannel channel =
        DatagramChannel.open(
            family == Family.IPV4 ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6);
    try {
      channel.configureBlocking(false);
      if (broadcast) channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
      channel.bind(
          new InetSocketAddress(
              InetAddress.getByAddress(new byte[family == Family.IPV4 ? 4 : 16]), 0));
      return new DiscoveryTransport(family, broadcast, channel);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        channel.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public InetSocketAddress localAddress() throws IOException {
    return (InetSocketAddress) channel.getLocalAddress();
  }

  public boolean isOpen() {
    return channel.isOpen();
  }

  /** False means would-block; no caller buffer or retry is retained. */
  public boolean send(InetSocketAddress peer, byte[] payload) throws IOException {
    Objects.requireNonNull(peer);
    Objects.requireNonNull(payload);
    if (peer.isUnresolved()
        || peer.getPort() == 0
        || peer.getAddress().isAnyLocalAddress()
        || peer.getAddress().isMulticastAddress()
        || (peer.getAddress() instanceof Inet6Address) != (family == Family.IPV6))
      throw new IllegalArgumentException(
          "Discovery peer must be resolved and match the socket family");
    byte[] address = peer.getAddress().getAddress();
    if (!broadcast
        && address.length == 4
        && address[0] == -1
        && address[1] == -1
        && address[2] == -1
        && address[3] == -1)
      throw new IllegalArgumentException("Discovery broadcast is not enabled");
    if (payload.length == 0 || payload.length > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Discovery payload must contain 1..16384 bytes");
    int sent = channel.send(ByteBuffer.wrap(payload), peer);
    if (sent == 0) return false;
    if (sent != payload.length) throw new IOException("Partial discovery datagram write");
    return true;
  }

  /** Polls at most one datagram. This object is confined to the caller's networking thread. */
  public Poll poll() throws IOException {
    receive.clear();
    InetSocketAddress source = (InetSocketAddress) channel.receive(receive);
    if (source == null) return EMPTY;
    int length = receive.position();
    if (length > Protocol68Channel.MAX_MESSAGE)
      return new Poll(Status.OVERSIZE, source, new byte[0]);
    if (length == 0) return new Poll(Status.EMPTY_DATAGRAM, source, new byte[0]);
    return new Poll(Status.PACKET, source, Arrays.copyOf(receive.array(), length));
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
