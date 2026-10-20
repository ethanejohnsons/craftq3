package dev.bluevista.craftq3.engine.net.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Message;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DemoProtocolTest {
  @Test
  void extensionsNameTheirProtocols() {
    assertEquals(DemoProtocol.Q3_1_32, DemoProtocol.fromFileName("demos/four.dm_68").orElseThrow());
    assertEquals(DemoProtocol.Q3_1_30, DemoProtocol.fromFileName("FREEFALL.DM_66").orElseThrow());
    assertEquals(DemoProtocol.Q3_1_31, DemoProtocol.fromFileName("x.dm_67").orElseThrow());
    assertEquals(DemoProtocol.Q3_1_27, DemoProtocol.fromFileName("demo000.dm_48").orElseThrow());
    assertTrue(DemoProtocol.fromFileName("ql.dm_73").isEmpty());
    assertTrue(DemoProtocol.fromFileName("demo.dm3").isEmpty());
    assertTrue(DemoProtocol.fromFileName("four.dm_68.txt").isEmpty());
    for (var protocol : DemoProtocol.values()) assertTrue(protocol.supported());
  }

  /** Protocols 66, 67 and 68 share the message layout: one recording decodes identically. */
  @Test
  void protocols66To68DecodeTheSameMessageStream() throws Exception {
    byte[] demo = synthetic();
    List<String> expected = null;
    for (int number : new int[] {66, 67, 68}) {
      var decoded = new ArrayList<String>();
      try (var reader =
          DemoReader.open(
              "demo.dm_" + number,
              new ByteArrayInputStream(demo),
              DemoRecordReader.Policy.STRICT)) {
        assertEquals(number, reader.protocol().number());
        while (true) {
          var record = reader.next();
          if (record.isEmpty()) break;
          decoded.add(record.get().sequence() + ":" + record.get().message().operations().size());
        }
        assertEquals(68, reader.announcedProtocol().orElseThrow());
        assertEquals(3, reader.latestSnapshot().orElseThrow().time() / 100);
      }
      if (expected == null) expected = decoded;
      assertEquals(expected, decoded);
    }
    assertEquals(List.of("1:1", "2:1", "3:1"), expected);
  }

  @Test
  void unknownProtocolIsRefusedAndClosesTheStream() {
    var closed = new boolean[1];
    var input =
        new ByteArrayInputStream(new byte[0]) {
          @Override
          public void close() {
            closed[0] = true;
          }
        };
    var failure =
        assertThrows(
            UnsupportedDemoProtocolException.class,
            () -> DemoReader.open("demo000.dm_45", input, DemoRecordReader.Policy.PLAYBACK));
    assertEquals(-1, failure.protocol());
    assertTrue(closed[0]);
    assertThrows(
        UnsupportedDemoProtocolException.class,
        () ->
            DemoReader.open(
                "ql.dm_73", new ByteArrayInputStream(new byte[0]), DemoRecordReader.Policy.STRICT));
  }

  @Test
  void sniffingTellsHuffmanFromRawGamestates() throws Exception {
    var writer = new MessageWriter();
    ServerMessageCodec.write(
        writer,
        new Message(0, List.of(new GameState(1, Map.of(), Baselines.EMPTY, 0, 0))),
        Baselines.EMPTY);
    assertEquals(DemoProtocol.Q3_1_32, DemoProtocol.sniff(writer.bytes()).orElseThrow());
    // The first bytes of a protocol-48 gamestate: ack 14, svc_gamestate, command sequence 117.
    byte[] raw = HexFormat.of().parseHex("0e0000000275000000030000");
    assertEquals(DemoProtocol.Q3_1_27, DemoProtocol.sniff(raw).orElseThrow());
    assertTrue(DemoProtocol.sniff(new byte[3]).isEmpty());
  }

  @Test
  void unknownExtensionIsRefused() {
    assertThrows(
        UnsupportedDemoProtocolException.class,
        () ->
            DemoReader.open(
                "notes.txt",
                new ByteArrayInputStream(new byte[0]),
                DemoRecordReader.Policy.STRICT));
  }

  private static byte[] synthetic() throws IOException {
    var bytes = new ByteArrayOutputStream();
    var game =
        new GameState(
            3,
            Map.of(0, "\\protocol\\68\\mapname\\q3dm17", 1, "\\sv_serverid\\5"),
            Baselines.EMPTY,
            0,
            9);
    var first = snapshot(2, 200);
    try (var writer = new DemoWriter(bytes)) {
      writer.append(record(1, new Message(0, List.of(game))));
      writer.append(record(2, new Message(0, List.of(new Frame(first, null)))));
      writer.append(record(3, new Message(0, List.of(new Frame(snapshot(3, 300), first)))));
    }
    return bytes.toByteArray();
  }

  private static Snapshot snapshot(int sequence, int time) {
    byte[] entity = new byte[208];
    entity[0] = 5;
    entity[4] = (byte) time;
    return new Snapshot(sequence, time, 0, new byte[] {1}, new byte[468], List.of(entity));
  }

  private static DemoRecord record(int sequence, Message message) {
    var writer = new MessageWriter();
    ServerMessageCodec.write(writer, message, Baselines.EMPTY);
    return new DemoRecord(sequence, writer.bytes());
  }
}
