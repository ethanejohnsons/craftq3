package dev.bluevista.craftq3.engine.net.transport;

import dev.bluevista.craftq3.engine.net.Protocol68;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Objects;

/**
 * A nonblocking UDP {@link PacketTransport} over one {@link DatagramChannel}, IPv4 or IPv6. It is
 * caller-driven: no threads, selectors or DNS lookups. Three shapes cover every Quake III use:
 *
 * <ul>
 *   <li>{@link #bind}: a server listener on an explicit address and port, replying to any peer;
 *   <li>{@link #ephemeral}: a browser or discovery socket on an OS-assigned port, optionally with
 *       IPv4 broadcast;
 *   <li>{@link #connect}: a client socket pinned to one server, so the OS drops other sources.
 * </ul>
 *
 * <p>Confined to its owner thread: the receive buffer is shared between polls.
 */
public final class UdpTransport implements PacketTransport {
  /** Address family of a socket. */
  public enum Family {
    IPV4,
    IPV6
  }

  private final DatagramChannel channel;
  private final Family family;
  private final boolean broadcast;
  private final InetSocketAddress connected;
  // The extra byte distinguishes an oversized UDP datagram from a valid full-sized message.
  private final ByteBuffer receive = ByteBuffer.allocateDirect(Protocol68.MAX_MSGLEN + 1);
  private SocketAddress lastSource;

  private UdpTransport(
      DatagramChannel channel, Family family, boolean broadcast, InetSocketAddress connected) {
    this.channel = channel;
    this.family = family;
    this.broadcast = broadcast;
    this.connected = connected;
  }

  /** A listener bound to a resolved unicast or wildcard address (port 0 picks one). */
  public static UdpTransport bind(InetSocketAddress local) throws IOException {
    Objects.requireNonNull(local, "local");
    if (local.isUnresolved() || local.getAddress().isMulticastAddress())
      throw new IllegalArgumentException(
          "Listener requires a resolved unicast/wildcard bind address");
    Family family = familyOf(local.getAddress());
    return open(family, false, channel -> channel.bind(local), null);
  }

  /** An OS-assigned local port on the wildcard address. Broadcast is an IPv4-only capability. */
  public static UdpTransport ephemeral(Family family, boolean broadcast) throws IOException {
    Objects.requireNonNull(family, "family");
    if (broadcast && family != Family.IPV4)
      throw new IllegalArgumentException("Broadcast requires IPv4");
    byte[] wildcard = new byte[family == Family.IPV4 ? 4 : 16];
    return open(
        family,
        broadcast,
        channel -> {
          if (broadcast) channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
          channel.bind(new InetSocketAddress(InetAddress.getByAddress(wildcard), 0));
        },
        null);
  }

  /**
   * A socket connected to one resolved unicast peer; datagrams from any other source or port are
   * filtered by the operating system before {@link #poll}.
   */
  public static UdpTransport connect(InetSocketAddress peer) throws IOException {
    checkPeer(peer);
    return open(familyOf(peer.getAddress()), false, channel -> channel.connect(peer), peer);
  }

  private interface Setup {
    void apply(DatagramChannel channel) throws IOException;
  }

  private static UdpTransport open(
      Family family, boolean broadcast, Setup setup, InetSocketAddress connected)
      throws IOException {
    ProtocolFamily protocol =
        family == Family.IPV4 ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6;
    DatagramChannel channel = DatagramChannel.open(protocol);
    try {
      channel.configureBlocking(false);
      setup.apply(channel);
      return new UdpTransport(channel, family, broadcast, connected);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        channel.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public Family family() {
    return family;
  }

  /** The pinned peer of a {@link #connect connected} transport, or null. */
  public InetSocketAddress peer() {
    return connected;
  }

  /** Sends to the pinned peer of a connected transport. */
  public boolean send(byte[] data) throws IOException {
    if (connected == null) throw new IllegalStateException("Transport is not connected");
    return send(connected, data, 0, data.length);
  }

  @Override
  public boolean send(SocketAddress peer, byte[] data, int offset, int length) throws IOException {
    Objects.requireNonNull(data, "data");
    Objects.checkFromIndexSize(offset, length, data.length);
    if (length == 0 || length > Protocol68.MAX_MSGLEN)
      throw new IllegalArgumentException("UDP payload must contain 1..16384 bytes");
    if (!(peer instanceof InetSocketAddress address))
      throw new IllegalArgumentException("UDP peer must be an InetSocketAddress");
    checkPeer(address);
    if (familyOf(address.getAddress()) != family)
      throw new IllegalArgumentException("UDP peer must match the socket family");
    if (connected != null && !connected.equals(address))
      throw new IllegalArgumentException("Connected transport sends only to its peer");
    if (!broadcast && isLimitedBroadcast(address.getAddress()))
      throw new IllegalArgumentException("Broadcast is not enabled on this transport");
    ByteBuffer view = ByteBuffer.wrap(data, offset, length);
    int sent = connected != null ? channel.write(view) : channel.send(view, address);
    if (sent == 0) return false;
    if (sent != length) throw new IOException("Partial UDP datagram write");
    return true;
  }

  @Override
  public int poll(byte[] buffer) throws IOException {
    receive.clear();
    SocketAddress source = channel.receive(receive);
    if (source == null) return NONE;
    lastSource = source;
    int length = receive.position();
    if (length > Protocol68.MAX_MSGLEN || length > buffer.length) return OVERSIZE;
    receive.flip();
    receive.get(buffer, 0, length);
    return length;
  }

  @Override
  public SocketAddress lastSource() {
    return lastSource;
  }

  @Override
  public InetSocketAddress localAddress() throws IOException {
    return (InetSocketAddress) channel.getLocalAddress();
  }

  @Override
  public boolean isOpen() {
    return channel.isOpen();
  }

  /** Safe to repeat; no worker, selector or background task is owned. */
  @Override
  public void close() throws IOException {
    channel.close();
  }

  private static void checkPeer(InetSocketAddress peer) {
    Objects.requireNonNull(peer, "peer");
    if (peer.isUnresolved()
        || peer.getPort() == 0
        || peer.getAddress().isAnyLocalAddress()
        || peer.getAddress().isMulticastAddress())
      throw new IllegalArgumentException("UDP peer must be a resolved unicast address and port");
  }

  private static Family familyOf(InetAddress address) {
    return address instanceof Inet6Address ? Family.IPV6 : Family.IPV4;
  }

  private static boolean isLimitedBroadcast(InetAddress address) {
    if (!(address instanceof Inet4Address)) return false;
    for (byte value : address.getAddress()) if (value != -1) return false;
    return true;
  }
}
