package dev.bluevista.craftq3.engine.net.chan;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.net.Protocol68;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Loss and reordering surface as a counted, debug-logged DROPPED result in both sessions. */
final class SessionDropTest {
  @Test
  void clientCountsOutOfOrderAndStaleDatagrams() {
    var session = new Protocol68ClientSession(42, 7);
    var logged =
        capture(
            () -> {
              var outOfOrder = session.receive(fragment(5, -1, 1300));
              assertEquals(Protocol68ClientSession.Status.DROPPED, outOfOrder.status());
              assertEquals("OUT_OF_ORDER", outOfOrder.diagnostic());
              assertEquals(1, session.droppedPackets());
            });
    assertTrue(logged.stream().anyMatch(line -> line.startsWith("DEBUG Dropped OUT_OF_ORDER")));
    assertEquals(
        Protocol68ClientSession.Status.REJECTED, session.receive(new byte[] {1, 0, 0, 0}).status());
    assertEquals(1, session.droppedPackets(), "a rejected payload is not a drop");
  }

  @Test
  void serverCountsStaleAndOutOfOrderDatagrams() {
    var session = new Protocol68ServerSession(42, 7);
    // A complete but unacceptable message still advances the channel sequence.
    assertEquals(Protocol68ServerSession.Status.REJECTED, session.receive(plain(3, 7)).status());
    var logged =
        capture(
            () -> {
              assertEquals(
                  Protocol68ServerSession.Status.DROPPED, session.receive(plain(2, 7)).status());
              assertEquals(
                  Protocol68ServerSession.Status.DROPPED,
                  session.receive(fragment(9, 7, 1300)).status());
            });
    assertEquals(2, session.droppedPackets());
    assertEquals(2, logged.size());
    assertEquals(Protocol68ServerSession.Status.IGNORED, session.receive(plain(9, 8)).status());
    assertEquals(2, session.droppedPackets(), "a wrong qport is not a drop");
  }

  private static List<String> capture(Runnable action) {
    var lines = new ArrayList<String>();
    var previous =
        Log.install(
            (name, level, message, error) -> {
              if (name.startsWith("dev.bluevista.craftq3.engine.net.chan"))
                lines.add(level + " " + message);
            });
    try {
      action.run();
    } finally {
      Log.install(previous);
    }
    return lines;
  }

  /** A non-fragmented datagram with a four-byte payload; qport < 0 omits the qport field. */
  private static byte[] plain(int sequence, int qport) {
    var packet = ByteBuffer.allocate(qport < 0 ? 8 : 10).order(ByteOrder.LITTLE_ENDIAN);
    packet.putInt(sequence);
    if (qport >= 0) packet.putShort((short) qport);
    packet.putInt(0);
    return packet.array();
  }

  /** A ten-byte fragment starting at {@code start}; qport < 0 omits the qport field. */
  private static byte[] fragment(int sequence, int qport, int start) {
    var packet = ByteBuffer.allocate((qport < 0 ? 8 : 10) + 10).order(ByteOrder.LITTLE_ENDIAN);
    packet.putInt(sequence | Protocol68.FRAGMENT_BIT);
    if (qport >= 0) packet.putShort((short) qport);
    packet.putShort((short) start).putShort((short) 10).put(new byte[10]);
    return packet.array();
  }
}
