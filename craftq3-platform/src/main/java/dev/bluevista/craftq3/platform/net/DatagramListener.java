package dev.bluevista.craftq3.platform.net;

import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.Optional;

/** Explicitly bound, nonblocking UDP host transport. It performs no DNS or packet dispatch. */
public final class DatagramListener implements AutoCloseable {
  public record Packet(InetSocketAddress peer, byte[] payload) {
    public Packet {
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  private final DatagramChannel channel;
  private final boolean ipv6;
  private final ByteBuffer input = ByteBuffer.allocate(Protocol68Channel.MAX_MESSAGE + 1);

  public DatagramListener(InetSocketAddress bind) throws IOException {
    if (bind.isUnresolved() || bind.getAddress().isMulticastAddress())
      throw new IllegalArgumentException(
          "Listener requires a resolved unicast/wildcard bind address");
    ipv6 = bind.getAddress() instanceof Inet6Address;
    channel =
        DatagramChannel.open(ipv6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
    try {
      channel.configureBlocking(false);
      channel.bind(bind);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        channel.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  public InetSocketAddress localAddress() throws IOException {
    return (InetSocketAddress) channel.getLocalAddress();
  }

  /** Oversize or empty datagrams are returned empty and consume just one poll's work. */
  public Optional<Packet> poll() throws IOException {
    input.clear();
    var peer = (InetSocketAddress) channel.receive(input);
    if (peer == null || input.position() == 0 || input.position() > Protocol68Channel.MAX_MESSAGE)
      return Optional.empty();
    return Optional.of(new Packet(peer, Arrays.copyOf(input.array(), input.position())));
  }

  public boolean send(InetSocketAddress peer, byte[] payload) throws IOException {
    if (peer.isUnresolved()
        || peer.getPort() == 0
        || peer.getAddress().isAnyLocalAddress()
        || peer.getAddress().isMulticastAddress()
        || (peer.getAddress() instanceof Inet6Address) != ipv6
        || payload.length == 0
        || payload.length > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Invalid hosted datagram");
    int sent = channel.send(ByteBuffer.wrap(payload), peer);
    if (sent != 0 && sent != payload.length) throw new IOException("Partial UDP datagram write");
    return sent != 0;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
