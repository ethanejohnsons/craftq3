package dev.bluevista.craftq3.engine.net.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.net.RawBitWriter;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Command;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.GameState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Synthetic protocol-48 demos through {@link DemoReader}. */
final class LegacyDemoTest {
  private static final int PLAYER_FIELDS = 42, PLAYER_CLIENT_NUM = 31;

  @Test
  void decodesIntoTheProtocol68StructuresAndResolvesTheClientNumber() throws Exception {
    byte[] demo = demo(46, false);
    var operations = new ArrayList<ServerMessageCodec.Operation>();
    try (var reader =
        DemoReader.open(
            "demos/demo000.dm_48",
            new ByteArrayInputStream(demo),
            DemoRecordReader.Policy.STRICT)) {
      assertEquals(DemoProtocol.Q3_1_27, reader.protocol());
      for (var record = reader.next(); record.isPresent(); record = reader.next())
        operations.addAll(record.get().message().operations());
      assertEquals(DemoRecordReader.End.MARKER, reader.end());
      assertEquals(46, reader.announcedProtocol().orElseThrow());
      assertEquals(3, reader.gameState().orElseThrow().clientNumber());
      assertEquals(1050, reader.latestSnapshot().orElseThrow().time());
    }
    var game = (GameState) operations.getFirst();
    assertEquals(3, game.clientNumber(), "taken from the first snapshot's player state");
    assertEquals(1, game.baselines().entities().size());
    assertEquals(new Command(9, "print hi"), operations.get(1));
    var first = (Frame) operations.get(2);
    assertNull(first.previous());
    assertEquals(2, first.current().entities().size());
    var second = (Frame) operations.get(3);
    assertEquals(first.current().sequence(), second.previous().sequence());
    assertEquals(1, second.current().entities().size());
  }

  @Test
  void protocol48ServersSendSixTrailingPlayerFields() throws Exception {
    try (var reader =
        DemoReader.open(
            "demo127.dm_48",
            new ByteArrayInputStream(demo(48, false)),
            DemoRecordReader.Policy.STRICT)) {
      while (reader.next().isPresent()) {}
      assertEquals(48, reader.announcedProtocol().orElseThrow());
      assertEquals(2, reader.snapshot(3).orElseThrow().entities().size());
    }
  }

  @Test
  void nonZeroPaddingFailsTheMessage() throws Exception {
    try (var reader =
        DemoReader.open(
            "x.dm_48", new ByteArrayInputStream(demo(46, true)), DemoRecordReader.Policy.STRICT)) {
      reader.next();
      var failure = assertThrows(IOException.class, reader::next);
      assertTrue(failure.getMessage().contains("padding"), failure.getMessage());
    }
  }

  @Test
  void protocol43HasNoLeadingAcknowledgeAndAWordAfterTheSnapshotOpcode() throws Exception {
    var game = new RawBitWriter().byteValue(2).intValue(56);
    game.byteValue(3).shortValue(0).string("\\protocol\\43\\mapname\\q3dm7");
    game.byteValue(4).bits(70, 10).bit(false).bit(true).bits(31, 5).bits(1, 50).bits(2, 8);
    game.byteValue(0);
    var snapshot = new RawBitWriter().byteValue(5).intValue(57).string("cs 6 \"11\"");
    snapshot.byteValue(7).intValue(3).intValue(663450).byteValue(0).byteValue(4).byteValue(0);
    for (int i = 0; i < PLAYER_FIELDS; i++) {
      snapshot.bit(i == PLAYER_CLIENT_NUM);
      if (i == PLAYER_CLIENT_NUM) snapshot.bits(2, 8);
    }
    for (int i = 0; i < 3 + 4; i++) snapshot.bit(false);
    snapshot.bits(5, 10).bit(false).bit(true).bits(1, 5).floatValue(1805).floatValue(-1040);
    snapshot.bits(1023, 10);
    var bytes = new ByteArrayOutputStream();
    try (var writer = new DemoWriter(bytes)) {
      writer.append(new DemoRecord(8380, game.toByteArray()));
      writer.append(new DemoRecord(8382, snapshot.toByteArray()));
    }
    try (var reader =
        DemoReader.open(
            "demos/demo001.dm3",
            new ByteArrayInputStream(bytes.toByteArray()),
            DemoRecordReader.Policy.STRICT)) {
      assertEquals(DemoProtocol.Q3_1_09, reader.protocol());
      var level = reader.next().orElseThrow().message();
      assertEquals(2, ((GameState) level.operations().getFirst()).clientNumber());
      var message = reader.next().orElseThrow().message();
      assertEquals(3, message.reliableAcknowledge());
      assertEquals(new Command(57, "cs 6 \"11\""), message.operations().getFirst());
      var frame = (Frame) message.operations().get(1);
      assertEquals(663450, frame.current().time());
      assertEquals(1, frame.current().entities().size());
      assertTrue(reader.next().isEmpty());
      assertEquals(43, reader.announcedProtocol().orElseThrow());
    }
  }

  private static byte[] demo(int announced, boolean dirtyPadding) throws IOException {
    int trailing = announced == 46 ? 5 : 6;
    var game = new RawBitWriter().intValue(0).byteValue(2).intValue(4);
    game.byteValue(3).shortValue(0).string("\\protocol\\" + announced + "\\mapname\\q3dm1");
    game.byteValue(4).bits(70, 10).bit(false).bit(true).bits(31, 5).bits(1, 51).bits(2, 8);
    game.byteValue(0);

    var full = new RawBitWriter().intValue(0);
    full.byteValue(5).intValue(9).string("print hi");
    full.byteValue(7).intValue(1000).byteValue(0).byteValue(0).byteValue(1).byteValue(255);
    for (int i = 0; i < PLAYER_FIELDS; i++) {
      full.bit(i == PLAYER_CLIENT_NUM);
      if (i == PLAYER_CLIENT_NUM) full.bits(3, 8);
    }
    for (int i = 0; i < trailing + 4; i++) full.bit(false);
    full.bits(5, 10).bit(false).bit(true).bits(7, 5).bits(1, 10);
    full.bits(70, 10).bit(false).bit(false);
    full.bits(1023, 10);
    if (dirtyPadding) full.bits(1, 1);

    var delta = new RawBitWriter().intValue(0);
    delta.byteValue(7).intValue(1050).byteValue(1).byteValue(0).byteValue(0);
    for (int i = 0; i < PLAYER_FIELDS + trailing + 4; i++) delta.bit(false);
    delta.bits(5, 10).bit(true).bits(1023, 10);

    var bytes = new ByteArrayOutputStream();
    try (var writer = new DemoWriter(bytes)) {
      writer.append(new DemoRecord(1, game.toByteArray()));
      writer.append(new DemoRecord(3, full.toByteArray()));
      writer.append(new DemoRecord(4, delta.toByteArray()));
    }
    return bytes.toByteArray();
  }
}
