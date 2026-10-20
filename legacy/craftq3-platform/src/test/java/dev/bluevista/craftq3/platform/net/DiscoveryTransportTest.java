package dev.bluevista.craftq3.platform.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

final class DiscoveryTransportTest {
  @Test
  void preservesIndependentReplySourcesAndOwnsPayloadsOnOneSocket() throws Exception {
    try (var first = peer(false);
        var second = peer(false);
        var transport = DiscoveryTransport.open(DiscoveryTransport.Family.IPV4, false)) {
      assertEquals(DiscoveryTransport.Status.EMPTY, transport.poll().status());
      assertTrue(transport.localAddress().getPort() > 0);
      byte[] request = {1, 2};
      send(transport, (InetSocketAddress) first.getLocalAddress(), request);
      request[0] = 99;
      assertArrayEquals(new byte[] {1, 2}, receive(first));
      first.send(ByteBuffer.wrap(new byte[] {3}), destination(transport, false));
      var one = poll(transport);
      assertEquals(first.getLocalAddress(), one.source());
      second.send(ByteBuffer.wrap(new byte[] {4}), destination(transport, false));
      var two = poll(transport);
      assertEquals(second.getLocalAddress(), two.source());
      assertNotEquals(one.source(), two.source());
      two.payload()[0] = 99;
      assertArrayEquals(new byte[] {4}, two.payload());
      assertArrayEquals(new byte[] {3}, one.payload());
      send(transport, two.source(), new byte[] {5});
      assertArrayEquals(new byte[] {5}, receive(second));
    }
  }

  @Test
  void distinguishesEmptyOversizedAndMaximumDatagramsWithoutLosingSource() throws Exception {
    try (var peer = peer(false);
        var transport = DiscoveryTransport.open(DiscoveryTransport.Family.IPV4, false)) {
      peer.send(ByteBuffer.allocate(0), destination(transport, false));
      var empty = poll(transport);
      assertEquals(DiscoveryTransport.Status.EMPTY_DATAGRAM, empty.status());
      assertEquals(peer.getLocalAddress(), empty.source());
      peer.send(
          ByteBuffer.allocate(Protocol68Channel.MAX_MESSAGE + 100), destination(transport, false));
      var oversized = poll(transport);
      assertEquals(DiscoveryTransport.Status.OVERSIZE, oversized.status());
      assertEquals(peer.getLocalAddress(), oversized.source());
      assertEquals(0, oversized.payload().length);
      byte[] maximum = new byte[Protocol68Channel.MAX_MESSAGE];
      Arrays.fill(maximum, (byte) 0xc7);
      peer.send(ByteBuffer.wrap(maximum), destination(transport, false));
      assertArrayEquals(maximum, poll(transport).payload());
      send(transport, (InetSocketAddress) peer.getLocalAddress(), maximum);
      assertArrayEquals(maximum, receive(peer));
    }
  }

  @Test
  void explicitIpv6SocketExchangesOnlyResolvedIpv6Endpoints() throws Exception {
    try (var peer = peer(true);
        var transport = DiscoveryTransport.open(DiscoveryTransport.Family.IPV6, false)) {
      send(transport, (InetSocketAddress) peer.getLocalAddress(), new byte[] {6});
      assertArrayEquals(new byte[] {6}, receive(peer));
      peer.send(ByteBuffer.wrap(new byte[] {8}), destination(transport, true));
      assertArrayEquals(new byte[] {8}, poll(transport).payload());
      assertThrows(
          IllegalArgumentException.class,
          () -> transport.send(new InetSocketAddress(loopback(false), 27960), new byte[] {1}));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscoveryTransport.open(DiscoveryTransport.Family.IPV6, true));
  }

  @Test
  void enforcesEndpointPayloadAndLifecycleContractsWithoutSendingBroadcast() throws Exception {
    try (var peer = peer(false)) {
      var transport = DiscoveryTransport.open(DiscoveryTransport.Family.IPV4, false);
      var address = (InetSocketAddress) peer.getLocalAddress();
      try {
        for (InetSocketAddress invalid :
            new InetSocketAddress[] {
              InetSocketAddress.createUnresolved("unresolved.invalid", 27960),
              new InetSocketAddress(loopback(false), 0),
              new InetSocketAddress(InetAddress.getByAddress(new byte[4]), 27960),
              new InetSocketAddress(InetAddress.getByAddress(new byte[] {-32, 0, 0, 1}), 27960),
              new InetSocketAddress(InetAddress.getByAddress(new byte[] {-1, -1, -1, -1}), 27960)
            })
          assertThrows(
              IllegalArgumentException.class, () -> transport.send(invalid, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> transport.send(address, new byte[0]));
        assertThrows(
            IllegalArgumentException.class,
            () -> transport.send(address, new byte[Protocol68Channel.MAX_MESSAGE + 1]));
      } finally {
        transport.close();
        transport.close();
      }
      assertFalse(transport.isOpen());
      assertThrows(ClosedChannelException.class, transport::poll);
      assertThrows(ClosedChannelException.class, () -> transport.send(address, new byte[] {1}));
      byte[] bytes = {1};
      var owned = new DiscoveryTransport.Poll(DiscoveryTransport.Status.PACKET, address, bytes);
      bytes[0] = 2;
      assertArrayEquals(new byte[] {1}, owned.payload());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DiscoveryTransport.Poll(DiscoveryTransport.Status.PACKET, null, new byte[] {1}));
    }
    // Enabling the local-discovery capability itself emits no network traffic.
    try (var transport = DiscoveryTransport.open(DiscoveryTransport.Family.IPV4, true)) {
      assertEquals(DiscoveryTransport.Status.EMPTY, transport.poll().status());
    }
  }

  private static InetAddress loopback(boolean ipv6) throws Exception {
    byte[] bytes = ipv6 ? new byte[16] : new byte[] {127, 0, 0, 1};
    if (ipv6) bytes[15] = 1;
    return InetAddress.getByAddress(bytes);
  }

  private static InetSocketAddress destination(DiscoveryTransport transport, boolean ipv6)
      throws Exception {
    return new InetSocketAddress(loopback(ipv6), transport.localAddress().getPort());
  }

  private static DatagramChannel peer(boolean ipv6) throws Exception {
    var peer =
        DatagramChannel.open(ipv6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
    try {
      peer.configureBlocking(false);
      peer.bind(new InetSocketAddress(loopback(ipv6), 0));
      return peer;
    } catch (Exception | Error failure) {
      peer.close();
      throw failure;
    }
  }

  private static void send(DiscoveryTransport transport, InetSocketAddress peer, byte[] bytes)
      throws Exception {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (!transport.send(peer, bytes)) {
      if (System.nanoTime() > deadline) fail("Discovery send remained unavailable");
      LockSupport.parkNanos(1_000_000);
    }
  }

  private static DiscoveryTransport.Poll poll(DiscoveryTransport transport) throws Exception {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < deadline) {
      var value = transport.poll();
      if (value.status() != DiscoveryTransport.Status.EMPTY) return value;
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("No discovery reply arrived");
  }

  private static byte[] receive(DatagramChannel peer) throws Exception {
    var buffer = ByteBuffer.allocate(Protocol68Channel.MAX_MESSAGE + 1);
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (peer.receive(buffer) != null) return Arrays.copyOf(buffer.array(), buffer.position());
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("No discovery request arrived");
  }
}
