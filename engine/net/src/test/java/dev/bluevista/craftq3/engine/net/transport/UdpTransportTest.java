package dev.bluevista.craftq3.engine.net.transport;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.net.Protocol68;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/** Ported from the legacy UdpTransport, DatagramListener and DiscoveryTransport tests. */
final class UdpTransportTest {
  private final byte[] buffer = new byte[Protocol68.MAX_MSGLEN];

  @Test
  void connectedIpv4TransportExchangesOwnedDatagrams() throws Exception {
    try (var peer = peer(false);
        var transport = UdpTransport.connect((InetSocketAddress) peer.getLocalAddress())) {
      assertEquals(peer.getLocalAddress(), transport.peer());
      assertTrue(transport.localAddress().getPort() > 0);
      assertTrue(transport.isOpen());
      assertEquals(PacketTransport.NONE, transport.poll(buffer));
      byte[] sent = {1, 2, 3};
      send(transport, transport.peer(), sent);
      sent[0] = 99;
      assertArrayEquals(new byte[] {1, 2, 3}, receive(peer));
      peer.send(ByteBuffer.wrap(new byte[] {4, 5, 6}), transport.localAddress());
      assertArrayEquals(new byte[] {4, 5, 6}, poll(transport));
      assertEquals(peer.getLocalAddress(), transport.lastSource());
    }
  }

  @Test
  void connectedTransportDropsAnInjectedWrongSourcePort() throws Exception {
    try (var peer = peer(false);
        var foreign = peer(false);
        var transport = UdpTransport.connect((InetSocketAddress) peer.getLocalAddress())) {
      for (int index = 0; index < 8; index++)
        foreign.send(ByteBuffer.wrap(new byte[] {99}), transport.localAddress());
      peer.send(ByteBuffer.wrap(new byte[] {42}), transport.localAddress());
      assertArrayEquals(new byte[] {42}, poll(transport));
      long until = System.nanoTime() + 50_000_000L;
      while (System.nanoTime() < until) {
        assertEquals(PacketTransport.NONE, transport.poll(buffer));
        LockSupport.parkNanos(1_000_000);
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> transport.send(foreign.getLocalAddress(), new byte[] {1}, 0, 1));
    }
  }

  @Test
  void emptyOversizedAndMaximumDatagramsRemainDistinctWithTheirSource() throws Exception {
    try (var peer = peer(false);
        var transport = UdpTransport.ephemeral(UdpTransport.Family.IPV4, false)) {
      var local = loopback(transport.localAddress().getPort(), false);
      peer.send(ByteBuffer.allocate(0), local);
      assertEquals(0, pollStatus(transport));
      assertEquals(peer.getLocalAddress(), transport.lastSource());
      for (int extra : new int[] {1, 4096}) {
        peer.send(ByteBuffer.allocate(Protocol68.MAX_MSGLEN + extra), local);
        assertEquals(PacketTransport.OVERSIZE, pollStatus(transport));
        assertEquals(peer.getLocalAddress(), transport.lastSource());
      }
      byte[] maximum = new byte[Protocol68.MAX_MSGLEN];
      Arrays.fill(maximum, (byte) 0x87);
      peer.send(ByteBuffer.wrap(maximum), local);
      assertArrayEquals(maximum, poll(transport));
      send(transport, peer.getLocalAddress(), maximum);
      assertArrayEquals(maximum, receive(peer));
      peer.send(ByteBuffer.wrap(new byte[] {1, 2}), local);
      assertEquals(PacketTransport.OVERSIZE, pollInto(transport, new byte[1]));
    }
  }

  @Test
  void listenerKeepsEachPeersSourceAndRepliesToIt() throws Exception {
    var bind = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0);
    try (var listener = UdpTransport.bind(bind);
        var first = UdpTransport.ephemeral(UdpTransport.Family.IPV4, false);
        var second = UdpTransport.ephemeral(UdpTransport.Family.IPV4, false)) {
      assertEquals(PacketTransport.NONE, listener.poll(buffer));
      send(first, listener.localAddress(), new byte[] {1});
      assertArrayEquals(new byte[] {1}, poll(listener));
      SocketAddress one = listener.lastSource();
      send(second, listener.localAddress(), new byte[] {2});
      assertArrayEquals(new byte[] {2}, poll(listener));
      SocketAddress two = listener.lastSource();
      assertNotEquals(one, two);
      send(listener, one, new byte[] {3});
      send(listener, two, new byte[] {4});
      assertArrayEquals(new byte[] {3}, poll(first));
      assertArrayEquals(new byte[] {4}, poll(second));
    }
  }

  @Test
  void ipv6LoopbackExchangesOnlyIpv6Endpoints() throws Exception {
    try (var peer = peer(true);
        var transport = UdpTransport.ephemeral(UdpTransport.Family.IPV6, false)) {
      send(transport, peer.getLocalAddress(), new byte[] {6});
      assertArrayEquals(new byte[] {6}, receive(peer));
      peer.send(
          ByteBuffer.wrap(new byte[] {8}), loopback(transport.localAddress().getPort(), true));
      assertArrayEquals(new byte[] {8}, poll(transport));
      var ipv4 = loopback(27960, false);
      assertThrows(IllegalArgumentException.class, () -> transport.send(ipv4, new byte[1], 0, 1));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> UdpTransport.ephemeral(UdpTransport.Family.IPV6, true));
  }

  @Test
  void invalidEndpointsPayloadsAndClosedLifecycleAreExplicit() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UdpTransport.connect(InetSocketAddress.createUnresolved("unresolved.invalid", 27960)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UdpTransport.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[4]), 1)));
    assertThrows(IllegalArgumentException.class, () -> UdpTransport.connect(loopback(0, false)));
    var broadcast =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {-1, -1, -1, -1}), 27960);
    try (var peer = peer(false)) {
      var transport = UdpTransport.ephemeral(UdpTransport.Family.IPV4, false);
      var target = peer.getLocalAddress();
      try {
        assertThrows(
            IllegalArgumentException.class, () -> transport.send(target, new byte[0], 0, 0));
        assertThrows(
            IllegalArgumentException.class,
            () -> transport.send(target, new byte[Protocol68.MAX_MSGLEN + 1], 0, 16385));
        assertThrows(
            IllegalArgumentException.class, () -> transport.send(broadcast, new byte[1], 0, 1));
        assertThrows(IllegalStateException.class, () -> transport.send(new byte[1]));
      } finally {
        transport.close();
        transport.close();
      }
      assertFalse(transport.isOpen());
      assertThrows(ClosedChannelException.class, () -> transport.poll(buffer));
      assertThrows(ClosedChannelException.class, () -> transport.send(target, new byte[1], 0, 1));
    }
  }

  private static InetSocketAddress loopback(int port, boolean ipv6) throws Exception {
    byte[] address = ipv6 ? new byte[16] : new byte[] {127, 0, 0, 1};
    if (ipv6) address[15] = 1;
    return new InetSocketAddress(InetAddress.getByAddress(address), port);
  }

  private static DatagramChannel peer(boolean ipv6) throws Exception {
    var channel =
        DatagramChannel.open(ipv6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
    try {
      channel.configureBlocking(false);
      channel.bind(loopback(0, ipv6));
      return channel;
    } catch (Exception | Error failure) {
      channel.close();
      throw failure;
    }
  }

  private static void send(PacketTransport transport, SocketAddress peer, byte[] payload)
      throws Exception {
    long until = System.nanoTime() + 2_000_000_000L;
    while (!transport.send(peer, payload, 0, payload.length)) {
      if (System.nanoTime() >= until) fail("UDP send remained unavailable");
      LockSupport.parkNanos(1_000_000);
    }
  }

  private byte[] poll(PacketTransport transport) throws Exception {
    int length = pollInto(transport, buffer);
    assertTrue(length >= 0, "unexpected poll status " + length);
    return Arrays.copyOf(buffer, length);
  }

  private int pollStatus(PacketTransport transport) throws Exception {
    return pollInto(transport, buffer);
  }

  private static int pollInto(PacketTransport transport, byte[] target) throws Exception {
    long until = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < until) {
      int result = transport.poll(target);
      if (result != PacketTransport.NONE) return result;
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("No UDP datagram arrived");
  }

  private static byte[] receive(DatagramChannel channel) throws Exception {
    ByteBuffer data = ByteBuffer.allocate(Protocol68.MAX_MSGLEN + 1);
    long until = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < until) {
      if (channel.receive(data) != null) return Arrays.copyOf(data.array(), data.position());
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("Peer received no UDP datagram");
  }
}
