package dev.bluevista.craftq3.platform.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

final class DatagramListenerTest {
  @Test
  void multiplePeersKeepTheirSourceAddressesAndOwnedPayloads() throws Exception {
    try (var listener = new DatagramListener(loopback(false));
        var first = new DatagramListener(loopback(false));
        var second = new DatagramListener(loopback(false))) {
      assertTrue(listener.poll().isEmpty());
      for (var peer : new DatagramListener[] {first, second}) {
        assertTrue(peer.send(listener.localAddress(), new byte[] {1, 2, 3}));
        var packet = receive(listener);
        assertEquals(peer.localAddress(), packet.peer());
        packet.payload()[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, packet.payload());
        assertTrue(listener.send(packet.peer(), packet.payload()));
        assertArrayEquals(new byte[] {1, 2, 3}, receive(peer).payload());
      }
    }
  }

  @Test
  void explicitIpv6LoopbackSupportsMaximumMessage() throws Exception {
    try (var listener = new DatagramListener(loopback(true));
        var peer = new DatagramListener(loopback(true))) {
      byte[] maximum = new byte[Protocol68Channel.MAX_MESSAGE];
      maximum[maximum.length - 1] = 42;
      assertTrue(peer.send(listener.localAddress(), maximum));
      assertArrayEquals(maximum, receive(listener).payload());
    }
  }

  @Test
  void invalidDestinationsAndClosedListenerAreExplicit() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DatagramListener(InetSocketAddress.createUnresolved("invalid.test", 0)));
    var listener = new DatagramListener(loopback(false));
    try {
      assertThrows(
          IllegalArgumentException.class, () -> listener.send(loopback(false), new byte[] {1}));
      assertThrows(
          IllegalArgumentException.class,
          () -> listener.send(listener.localAddress(), new byte[0]));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              listener.send(listener.localAddress(), new byte[Protocol68Channel.MAX_MESSAGE + 1]));
    } finally {
      listener.close();
      listener.close();
    }
    assertThrows(ClosedChannelException.class, listener::poll);
  }

  private static InetSocketAddress loopback(boolean ipv6) throws Exception {
    byte[] bytes = ipv6 ? new byte[16] : new byte[] {127, 0, 0, 1};
    if (ipv6) bytes[15] = 1;
    return new InetSocketAddress(InetAddress.getByAddress(bytes), 0);
  }

  private static DatagramListener.Packet receive(DatagramListener listener) throws Exception {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (System.nanoTime() < deadline) {
      var packet = listener.poll();
      if (packet.isPresent()) return packet.orElseThrow();
      LockSupport.parkNanos(1_000_000);
    }
    throw new AssertionError("No private UDP datagram arrived");
  }
}
