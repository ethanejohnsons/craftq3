package dev.bluevista.craftq3.core;

import static dev.bluevista.craftq3.core.net.Protocol68Channel.Endpoint.*;
import static dev.bluevista.craftq3.core.net.Protocol68Channel.Status.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Protocol68ChannelTest {
  @Test
  void publishedLittleEndianHeadersAndFragmentTerminator() {
    var sender = new Protocol68Channel(CLIENT, 0x1234);
    sender.queue(new byte[] {5, 6});
    assertArrayEquals(
        HexFormat.of().parseHex("0100000034120506"), sender.pollPacket().orElseThrow());
    assertTrue(sender.pollPacket().isEmpty());
    sender.queue(new byte[1300]);
    byte[] first = sender.pollPacket().orElseThrow();
    assertArrayEquals(
        HexFormat.of().parseHex("02000080341200001405"), java.util.Arrays.copyOf(first, 10));
    assertEquals(2, sender.outgoingSequence());
    assertTrue(sender.hasPendingPacket());
    assertArrayEquals(
        HexFormat.of().parseHex("02000080341214050000"), sender.pollPacket().orElseThrow());
    assertEquals(3, sender.outgoingSequence());
  }

  @Test
  void fragmentBoundariesAndMaximumMessageInBothDirections() {
    var random = new Random(68);
    for (var endpoint : Protocol68Channel.Endpoint.values()) {
      var sender = new Protocol68Channel(endpoint, 65535);
      var receiver = new Protocol68Channel(endpoint == CLIENT ? SERVER : CLIENT, 65535);
      int sequence = 1;
      for (int size : new int[] {0, 1, 1299, 1300, 1301, 2599, 2600, 2601, 15600, 16384}) {
        byte[] message = new byte[size];
        random.nextBytes(message);
        List<byte[]> packets = packets(sender, message);
        assertEquals(size < 1300 ? 1 : size / 1300 + 1, packets.size());
        for (int i = 0; i < packets.size(); i++) {
          assertTrue(packets.get(i).length <= 1310);
          var result = receiver.receive(packets.get(i));
          assertEquals(sequence, result.sequence());
          if (i < packets.size() - 1) {
            assertEquals(PARTIAL, result.status());
            assertEquals(sequence - 1, receiver.incomingSequence());
          } else {
            assertEquals(COMPLETE, result.status());
            assertArrayEquals(message, result.payload());
            assertEquals(sequence, receiver.incomingSequence());
            assertEquals(0, result.dropped());
          }
        }
        sequence++;
      }
    }
  }

  @Test
  void packetLossReorderingDuplicatesAndSequenceGaps() {
    var sender = new Protocol68Channel(SERVER, 1);
    var receiver = new Protocol68Channel(CLIENT, 1);
    packets(sender, new byte[] {0}); // One complete message lost.
    byte[] message = new byte[3000];
    new Random(2).nextBytes(message);
    List<byte[]> packets = packets(sender, message);
    assertEquals(OUT_OF_ORDER, receiver.receive(packets.get(1)).status());
    assertEquals(PARTIAL, receiver.receive(packets.get(0)).status());
    assertEquals(OUT_OF_ORDER, receiver.receive(packets.get(0)).status());
    assertEquals(OUT_OF_ORDER, receiver.receive(packets.get(2)).status());
    assertEquals(PARTIAL, receiver.receive(packets.get(1)).status());
    var complete = receiver.receive(packets.get(2));
    assertEquals(COMPLETE, complete.status());
    assertEquals(1, complete.dropped());
    assertArrayEquals(message, complete.payload());
    assertEquals(STALE, receiver.receive(packets.get(0)).status());
  }

  @Test
  void abandonedFragmentsCannotEraseNewerAssembly() {
    var sender = new Protocol68Channel(SERVER, 0);
    var receiver = new Protocol68Channel(CLIENT, 0);
    var old = packets(sender, new byte[1301]);
    var next = packets(sender, new byte[1302]);
    assertEquals(PARTIAL, receiver.receive(next.getFirst()).status());
    assertEquals(STALE, receiver.receive(old.getFirst()).status());
    assertEquals(COMPLETE, receiver.receive(next.getLast()).status());
    assertEquals(2, receiver.incomingSequence());
  }

  @Test
  void malformedOrWrongChannelPacketsDoNotPoisonAssembly() {
    var sender = new Protocol68Channel(CLIENT, 42);
    var receiver = new Protocol68Channel(SERVER, 42);
    var packets = packets(sender, new byte[1301]);
    byte[] wrong = packets.getFirst().clone();
    wrong[4]++;
    assertEquals(WRONG_QPORT, receiver.receive(wrong).status());
    assertEquals(PARTIAL, receiver.receive(packets.getFirst()).status());
    byte[] finalPacket = packets.getLast();
    for (int cut = 0; cut < finalPacket.length; cut++)
      assertEquals(MALFORMED, receiver.receive(java.util.Arrays.copyOf(finalPacket, cut)).status());
    byte[] tooLong = java.util.Arrays.copyOf(finalPacket, finalPacket.length + 1);
    assertEquals(MALFORMED, receiver.receive(tooLong).status());
    byte[] overflow = finalPacket.clone();
    ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putShort(6, (short) 16384);
    assertEquals(MALFORMED, receiver.receive(overflow).status());
    assertEquals(COMPLETE, receiver.receive(finalPacket).status());
    assertEquals(MALFORMED, receiver.receive(new byte[1401]).status());
    assertEquals(MALFORMED, receiver.receive(new byte[4]).status());
  }

  @Test
  void boundedOwnedPayloadsAndPendingMessageDiscipline() {
    assertThrows(IllegalArgumentException.class, () -> new Protocol68Channel(CLIENT, -1));
    assertThrows(IllegalArgumentException.class, () -> new Protocol68Channel(CLIENT, 65536));
    var sender = new Protocol68Channel(SERVER, 0);
    assertThrows(IllegalArgumentException.class, () -> sender.queue(new byte[16385]));
    byte[] message = {4, 5};
    sender.queue(message);
    message[0] = 99;
    assertThrows(IllegalStateException.class, () -> sender.queue(new byte[0]));
    byte[] packet = sender.pollPacket().orElseThrow();
    var result = new Protocol68Channel(CLIENT, 0).receive(packet);
    packet[4] = 55;
    result.payload()[0] = 66;
    assertArrayEquals(new byte[] {4, 5}, result.payload());
  }

  @Test
  void connectionlessBinaryAndTextFraming() {
    byte[] packet = ConnectionlessPacket.text("getchallenge 42\n");
    assertEquals(CONNECTIONLESS, new Protocol68Channel(CLIENT, 0).receive(packet).status());
    assertEquals(
        "getchallenge 42\n",
        new String(
            ConnectionlessPacket.payload(packet), java.nio.charset.StandardCharsets.ISO_8859_1));
    byte[] binary = {0, -1, 5};
    assertArrayEquals(binary, ConnectionlessPacket.payload(ConnectionlessPacket.encode(binary)));
    assertThrows(IllegalArgumentException.class, () -> ConnectionlessPacket.payload(new byte[3]));
    assertThrows(IllegalArgumentException.class, () -> ConnectionlessPacket.text("x\0y"));
    assertThrows(IllegalArgumentException.class, () -> ConnectionlessPacket.text("λ"));
    assertThrows(
        IllegalArgumentException.class, () -> ConnectionlessPacket.encode(new byte[16381]));
  }

  @Test
  void arbitraryDatagramsStayBoundedWithoutThrowing() {
    var receiver = new Protocol68Channel(SERVER, 42);
    var random = new Random(0x68);
    for (int i = 0; i < 5000; i++) {
      byte[] data = new byte[random.nextInt(1500)];
      random.nextBytes(data);
      var result = receiver.receive(data);
      assertTrue(result.payload().length <= Protocol68Channel.MAX_MESSAGE);
    }
  }

  private static List<byte[]> packets(Protocol68Channel channel, byte[] payload) {
    channel.queue(payload);
    List<byte[]> result = new ArrayList<>();
    while (channel.hasPendingPacket()) result.add(channel.pollPacket().orElseThrow());
    return result;
  }
}
