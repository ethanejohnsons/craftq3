package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BrowserStatusTest {
  @Test
  void pendingQueryEmitsExactRequestAndRetriesOnlyAfterTheStrictDeadline() {
    var sent = new ArrayList<byte[]>();
    var status = new BrowserStatus((peer, packet) -> sent.add(packet));
    assertTrue(status.query(peer(0), 1024, 1000, 750).isEmpty());
    assertArrayEquals(ConnectionlessPacket.text("getstatus"), sent.getFirst());
    assertTrue(status.query(peer(0), 1024, 1750, 750).isEmpty());
    assertEquals(1, sent.size());
    assertTrue(status.query(peer(0), 1024, 1751, 750).isEmpty());
    assertEquals(2, sent.size());
  }

  @Test
  void retrySubtractsBeforeComparingAcrossSignedClockWrap() {
    var sent = new ArrayList<InetSocketAddress>();
    var status = new BrowserStatus((peer, packet) -> sent.add(peer));
    status.query(peer(0), 1, Integer.MAX_VALUE - 5, 10);
    status.query(peer(0), 1, Integer.MIN_VALUE + 10, 10);
    assertEquals(1, sent.size());
    status.resetAll();
    status.query(peer(0), 1, Integer.MIN_VALUE + 10, 100);
    status.query(peer(0), 1, Integer.MIN_VALUE + 15, 100);
    assertEquals(3, sent.size());
  }

  @Test
  void keepsStatusTextInertAndPreservesNativeLineAndByteRules() {
    var status = new BrowserStatus((peer, packet) -> {});
    status.query(peer(0), 1024, 1000, 750);
    assertFalse(status.receive(peer(1), bytes("ignored\n")));
    assertTrue(status.receive(peer(0), bytes("\\hostname\\100%\u00ff\r\n1 25 \"a%\u0080\"\r\n")));
    assertEquals(
        "\\hostname\\100..\r\\\\1 25 \"a..\"\r\\",
        status.query(peer(0), 1024, 1000, 750).orElseThrow());
    assertEquals("", status.query(peer(0), 1, 1000, 750).orElseThrow());
    assertEquals("\\hostna", status.query(peer(0), 8, 1000, 750).orElseThrow());
  }

  @Test
  void lineLimitConsumesOneExtraByteAndAnEmptyPlayerLineEndsTheResult() {
    var status = new BrowserStatus((peer, packet) -> {});
    status.query(peer(0), 8192, 1000, 750);
    status.receive(peer(0), bytes("a".repeat(1024) + "\nb\n"));
    assertEquals("a".repeat(1023) + "\\\\", status.query(peer(0), 8192, 1000, 750).orElseThrow());
    status.receive(peer(0), bytes("info\n\nignored\n"));
    assertEquals("info\\\\", status.query(peer(0), 8192, 1000, 750).orElseThrow());
    status.receive(peer(0), bytes("info\0next\nend\n"));
    assertEquals("info\\\\next\\end\\", status.query(peer(0), 8192, 1000, 750).orElseThrow());
  }

  @Test
  void storedTextIsBoundedAndCachedRepliesCanBeUpdatedWithoutARequest() {
    var sent = new ArrayList<InetSocketAddress>();
    var status = new BrowserStatus((peer, packet) -> sent.add(peer));
    status.query(peer(0), 8192, 1000, 750);
    status.receive(peer(0), bytes("info\n" + "x\n".repeat(6000)));
    assertEquals(8191, status.query(peer(0), 8192, 1000, 750).orElseThrow().length());
    status.reset(peer(0));
    status.receive(peer(0), bytes("later\n"));
    assertEquals("later\\\\", status.query(peer(0), 8192, 9999, 750).orElseThrow());
    assertEquals(1, sent.size());
    status.resetAll();
    assertFalse(status.receive(peer(0), bytes("stale\n")));
    assertTrue(status.query(peer(0), 8192, 9999, 750).isEmpty());
    assertEquals(2, sent.size());
  }

  @Test
  void unretrievedResultsOccupySlotsAndCompletedRetrievalAllowsReuse() {
    var sent = new ArrayList<InetSocketAddress>();
    var status = full(sent);
    status.receive(peer(5), bytes("five\n"));
    assertTrue(status.query(peer(16), 32, 9999, 750).isEmpty());
    assertEquals(16, sent.size());
    assertEquals("five\\\\", status.query(peer(5), 32, 9999, 750).orElseThrow());
    status.query(peer(16), 32, 9999, 750);
    assertEquals(17, sent.size());
    assertFalse(status.receive(peer(5), bytes("replaced\n")));
    assertTrue(status.receive(peer(16), bytes("sixteen\n")));
  }

  @Test
  void unknownResetReleasesTheOldestRequestAndSpecificResetAllowsItsLateReply() {
    var sent = new ArrayList<InetSocketAddress>();
    var status = full(sent);
    status.reset(peer(99));
    status.query(peer(16), 32, 1100, 750);
    assertEquals(17, sent.size());
    assertFalse(status.receive(peer(0), bytes("oldest replaced\n")));
    status.reset(peer(1));
    assertTrue(status.receive(peer(1), bytes("late\n")));
    assertEquals("late\\\\", status.query(peer(1), 32, 1100, 750).orElseThrow());
  }

  @Test
  void invalidBoundsAndUnresolvedEndpointsHaveNoOutboundEffect() {
    var sent = new ArrayList<InetSocketAddress>();
    var status = new BrowserStatus((peer, packet) -> sent.add(peer));
    assertThrows(IllegalArgumentException.class, () -> status.query(peer(0), 0, 0, 750));
    assertThrows(IllegalArgumentException.class, () -> status.query(peer(0), 8193, 0, 750));
    assertThrows(IllegalArgumentException.class, () -> status.query(peer(0), 8, 0, -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            status.query(InetSocketAddress.createUnresolved("example.invalid", 27960), 8, 0, 750));
    assertThrows(
        IllegalArgumentException.class,
        () -> status.receive(peer(0), new byte[ConnectionlessPacket.MAX_PAYLOAD + 1]));
    assertTrue(sent.isEmpty());
  }

  private static BrowserStatus full(List<InetSocketAddress> sent) {
    var status = new BrowserStatus((peer, packet) -> sent.add(peer));
    for (int i = 0; i < 16; i++) status.query(peer(i), 32, 1000 + i, 750);
    return status;
  }

  private static InetSocketAddress peer(int index) {
    try {
      return new InetSocketAddress(
          InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 28000 + index);
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }
}
