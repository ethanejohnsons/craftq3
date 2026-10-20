package dev.bluevista.craftq3.engine.net.transport;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.net.Protocol68;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LoopbackTransportTest {
  @Test
  void endsExchangeOwnedCopiesInOrderAndNameEachOther() throws Exception {
    var pair = LoopbackTransport.pair();
    var client = pair.client();
    var server = pair.server();
    byte[] buffer = new byte[Protocol68.MAX_MSGLEN];
    assertEquals(PacketTransport.NONE, server.poll(buffer));
    byte[] sent = {1, 2, 3};
    assertTrue(client.send(sent));
    sent[0] = 9;
    assertTrue(client.send(server.localAddress(), new byte[] {7, 8, 4, 5}, 2, 2));
    assertEquals(3, server.poll(buffer));
    assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOf(buffer, 3));
    assertEquals(client.localAddress(), server.lastSource());
    assertEquals(2, server.poll(buffer));
    assertArrayEquals(new byte[] {4, 5}, Arrays.copyOf(buffer, 2));
    assertEquals(PacketTransport.NONE, server.poll(buffer));
    assertTrue(server.send(new byte[] {6}));
    assertEquals(1, client.poll(buffer));
    assertEquals(6, buffer[0]);
  }

  @Test
  void boundedQueueRefusesWhenFullAndOversizeIsDiscarded() throws Exception {
    var pair = LoopbackTransport.pair(4);
    for (int i = 0; i < 4; i++) assertTrue(pair.client().send(new byte[] {(byte) i}));
    assertFalse(pair.client().send(new byte[] {4}));
    byte[] small = new byte[1];
    assertEquals(1, pair.server().poll(small));
    assertTrue(pair.client().send(new byte[Protocol68.MAX_MSGLEN]));
    for (int i = 1; i < 4; i++) assertEquals(1, pair.server().poll(small));
    assertEquals(PacketTransport.OVERSIZE, pair.server().poll(small));
    assertEquals(PacketTransport.NONE, pair.server().poll(small));
  }

  @Test
  void invalidDatagramsDestinationsAndClosedEndsAreExplicit() throws Exception {
    var pair = LoopbackTransport.pair();
    var other = LoopbackTransport.pair();
    assertThrows(IllegalArgumentException.class, () -> pair.client().send(new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> pair.client().send(new byte[Protocol68.MAX_MSGLEN + 1]));
    assertThrows(
        IllegalArgumentException.class,
        () -> pair.client().send(other.server().localAddress(), new byte[1], 0, 1));
    assertThrows(IllegalArgumentException.class, () -> LoopbackTransport.pair(0));
    pair.server().close();
    assertTrue(pair.client().send(new byte[] {1}), "datagrams to a closed end vanish, as on UDP");
    assertFalse(pair.server().isOpen());
    assertThrows(ClosedChannelException.class, () -> pair.server().poll(new byte[16]));
    pair.client().close();
    pair.client().close();
    assertThrows(ClosedChannelException.class, () -> pair.client().send(new byte[] {1}));
  }

  @Test
  void producerAndConsumerThreadsSeeEveryDatagramIntactAndInOrder() throws Exception {
    var pair = LoopbackTransport.pair(8);
    int count = 20_000;
    var failure = new AtomicReference<Throwable>();
    var producer =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    byte[] data = new byte[64];
                    for (int i = 0; i < count; i++) {
                      int length = 1 + i % data.length;
                      Arrays.fill(data, 0, length, (byte) i);
                      while (!pair.client().send(pair.server().localAddress(), data, 0, length))
                        Thread.onSpinWait();
                    }
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    byte[] buffer = new byte[Protocol68.MAX_MSGLEN];
    long deadline = System.nanoTime() + 20_000_000_000L;
    for (int i = 0; i < count; ) {
      int length = pair.server().poll(buffer);
      if (length == PacketTransport.NONE) {
        assertTrue(System.nanoTime() < deadline, "consumer timed out");
        Thread.onSpinWait();
        continue;
      }
      assertEquals(1 + i % 64, length);
      for (int b = 0; b < length; b++) assertEquals((byte) i, buffer[b]);
      i++;
    }
    producer.join();
    assertNull(failure.get());
  }
}
