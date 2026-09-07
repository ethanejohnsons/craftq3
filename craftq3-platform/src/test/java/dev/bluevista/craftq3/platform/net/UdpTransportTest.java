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

final class UdpTransportTest {
  @Test
  void resolvedIpv4PeerReceivesAndSendsOwnedMessages() throws Exception {
    try (var peer = peer(false);
        var transport = UdpTransport.open((InetSocketAddress) peer.getLocalAddress())) {
      assertEquals(peer.getLocalAddress(), transport.peer());
      assertTrue(transport.localAddress().getPort() > 0);
      assertTrue(transport.isOpen());
      assertEquals(UdpTransport.Status.EMPTY, transport.poll().status());
      byte[] sent = {1, 2, 3};
      send(transport, sent);
      sent[0] = 99;
      assertArrayEquals(new byte[] {1, 2, 3}, receive(peer));
      peer.send(ByteBuffer.wrap(new byte[] {4, 5, 6}), transport.localAddress());
      var first = poll(transport);
      assertEquals(UdpTransport.Status.PACKET, first.status());
      byte[] copied = first.payload();
      copied[0] = 99;
      peer.send(ByteBuffer.wrap(new byte[] {7}), transport.localAddress());
      assertArrayEquals(new byte[] {7}, poll(transport).payload());
      assertArrayEquals(new byte[] {4, 5, 6}, first.payload());
    }
  }

  @Test
  void connectedEndpointDropsAnInjectedWrongSourcePort() throws Exception {
    try (var peer = peer(false);
        var foreign = peer(false);
        var transport = UdpTransport.open((InetSocketAddress) peer.getLocalAddress())) {
      for (int index = 0; index < 8; index++)
        foreign.send(ByteBuffer.wrap(new byte[] {99}), transport.localAddress());
      peer.send(ByteBuffer.wrap(new byte[] {42}), transport.localAddress());
      assertArrayEquals(new byte[] {42}, poll(transport).payload());
      long until = System.nanoTime() + 50_000_000L;
      while (System.nanoTime() < until) {
        assertEquals(UdpTransport.Status.EMPTY, transport.poll().status());
        LockSupport.parkNanos(1_000_000);
      }
    }
  }

  @Test
  void emptyOversizedAndMaximumMessagesRemainDistinct() throws Exception {
    try (var peer = peer(false);
        var transport = UdpTransport.open((InetSocketAddress) peer.getLocalAddress())) {
      peer.send(ByteBuffer.allocate(0), transport.localAddress());
      assertEquals(UdpTransport.Status.EMPTY_DATAGRAM, poll(transport).status());
      for (int extra : new int[] {1, 4096}) {
        peer.send(
            ByteBuffer.allocate(Protocol68Channel.MAX_MESSAGE + extra), transport.localAddress());
        var result = poll(transport);
        assertEquals(UdpTransport.Status.OVERSIZE, result.status());
        assertEquals(0, result.payload().length);
      }
      byte[] maximum = new byte[Protocol68Channel.MAX_MESSAGE];
      Arrays.fill(maximum, (byte) 0x87);
      peer.send(ByteBuffer.wrap(maximum), transport.localAddress());
      assertArrayEquals(maximum, poll(transport).payload());
      send(transport, maximum);
      assertArrayEquals(maximum, receive(peer));
      assertEquals(UdpTransport.Status.EMPTY, transport.poll().status());
    }
  }

  @Test
  void ipv6UsesTheExplicitResolvedLoopbackEndpoint() throws Exception {
    try (var peer = peer(true);
        var transport = UdpTransport.open((InetSocketAddress) peer.getLocalAddress())) {
      send(transport, new byte[] {6});
      assertArrayEquals(new byte[] {6}, receive(peer));
      peer.send(ByteBuffer.wrap(new byte[] {8}), transport.localAddress());
      assertArrayEquals(new byte[] {8}, poll(transport).payload());
    }
  }

  @Test
  void invalidEndpointsPayloadsAndClosedLifecycleAreExplicit() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> UdpTransport.open(InetSocketAddress.createUnresolved("unresolved.invalid", 27960)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UdpTransport.open(new InetSocketAddress(InetAddress.getByAddress(new byte[4]), 27960)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UdpTransport.open(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0)));
    try (var peer = peer(false)) {
      var transport = UdpTransport.open((InetSocketAddress) peer.getLocalAddress());
      try {
        assertThrows(IllegalArgumentException.class, () -> transport.send(new byte[0]));
        assertThrows(
            IllegalArgumentException.class,
            () -> transport.send(new byte[Protocol68Channel.MAX_MESSAGE + 1]));
      } finally {
        transport.close();
        transport.close();
      }
      assertFalse(transport.isOpen());
      assertThrows(ClosedChannelException.class, transport::poll);
      assertThrows(ClosedChannelException.class, () -> transport.send(new byte[] {1}));
    }
    byte[] bytes = {1};
    var value = new UdpTransport.Poll(UdpTransport.Status.PACKET, bytes);
    bytes[0] = 2;
    assertArrayEquals(new byte[] {1}, value.payload());
    assertThrows(
        IllegalArgumentException.class,
        () -> new UdpTransport.Poll(UdpTransport.Status.OVERSIZE, new byte[] {1}));
  }

  private static DatagramChannel peer(boolean ipv6) throws Exception {
    byte[] address = ipv6 ? new byte[16] : new byte[] {127, 0, 0, 1};
    if (ipv6) address[15] = 1;
    var channel =
        DatagramChannel.open(ipv6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
    try {
      channel.configureBlocking(false);
      channel.bind(new InetSocketAddress(InetAddress.getByAddress(address), 0));
      return channel;
    } catch (Exception | Error failure) {
      channel.close();
      throw failure;
    }
  }

  private static void send(UdpTransport transport, byte[] payload) throws Exception {
    long until = System.nanoTime() + 2_000_000_000L;
    while (!transport.send(payload)) {
      if (System.nanoTime() >= until) fail("UDP send remained unavailable");
      LockSupport.parkNanos(1_000_000);
    }
  }

  private static UdpTransport.Poll poll(UdpTransport transport) throws Exception {
    long until = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < until) {
      var result = transport.poll();
      if (result.status() != UdpTransport.Status.EMPTY) return result;
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("No UDP datagram arrived");
  }

  private static byte[] receive(DatagramChannel channel) throws Exception {
    ByteBuffer data = ByteBuffer.allocate(Protocol68Channel.MAX_MESSAGE + 1);
    long until = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < until) {
      if (channel.receive(data) != null) return Arrays.copyOf(data.array(), data.position());
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("Peer received no UDP datagram");
  }
}
