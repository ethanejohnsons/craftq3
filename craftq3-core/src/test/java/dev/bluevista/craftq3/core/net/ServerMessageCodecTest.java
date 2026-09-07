package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.core.net.delta.EntityDeltaCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServerMessageCodecTest {
  private static final Baselines EMPTY = Baselines.EMPTY;

  @Test
  void levelMetadataAndFullFrameUseTheNewBaselinesInOrder() {
    var baseline = new Baselines(List.of(entity(1, 5), entity(8, 9)));
    var game =
        new GameState(
            43, Map.of(0, "\\mapname\\oracle", 99, "50% \u0080", 100, ""), baseline, 3, 0x12345678);
    var message =
        new Message(
            17,
            List.of(
                NoOp.INSTANCE,
                game,
                new Command(44, "print %"),
                new Frame(snapshot(15, List.of(entity(1, 5))), null),
                new Command(45, "after")));
    var writer = new MessageWriter();
    ServerMessageCodec.write(writer, message, EMPTY);
    var reader = new MessageReader(writer.bytes());
    var decoded =
        ServerMessageCodec.read(
            reader,
            15,
            EMPTY,
            ignored -> {
              throw new AssertionError("Full snapshot consulted history");
            });
    assertEquals(17, decoded.reliableAcknowledge());
    assertEquals(5, decoded.operations().size());
    var level = (GameState) decoded.operations().get(1);
    assertEquals(43, level.commandSequence());
    assertEquals(3, level.clientNumber());
    assertEquals(0x12345678, level.checksumFeed());
    assertEquals(Map.of(0, "\\mapname\\oracle", 99, "50. ."), level.configstrings());
    assertArrayEquals(entity(8, 9), level.baselines().state(8));
    assertEquals(new Command(44, "print ."), decoded.operations().get(2));
    assertArrayEquals(
        entity(1, 5), ((Frame) decoded.operations().get(3)).current().entities().getFirst());
    assertEquals(new Command(45, "after"), decoded.operations().get(4));
    assertEquals(writer.bitPosition(), reader.bitPosition());
  }

  @Test
  void deltaUsesExactMessageHistoryAndResetRejectsOldLevelHistory() {
    var before = snapshot(10, List.of(entity(4, 5)));
    var next = snapshot(12, List.of(entity(4, 6)));
    var writer = new MessageWriter();
    ServerMessageCodec.write(writer, new Message(0, List.of(new Frame(next, before))), EMPTY);
    var lookup = new AtomicInteger();
    var read =
        ServerMessageCodec.read(
            new MessageReader(writer.bytes()),
            12,
            EMPTY,
            sequence -> {
              lookup.set(sequence);
              return before;
            });
    assertEquals(10, lookup.get());
    assertSame(before, ((Frame) read.operations().getFirst()).previous());
    var missing = new MessageReader(writer.bytes());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServerMessageCodec.read(missing, 12, EMPTY, ignored -> null));
    assertEquals(0, missing.bitPosition());
    var reset =
        new Message(0, List.of(new GameState(1, Map.of(), EMPTY, 0, 0), new Frame(next, before)));
    var target = new MessageWriter();
    target.bits(5, 3);
    assertThrows(
        IllegalArgumentException.class, () -> ServerMessageCodec.write(target, reset, EMPTY));
    assertEquals(3, target.bitPosition());
    // A malicious packet cannot bypass that check by emitting the same operations directly.
    var raw = gamePrefix();
    raw.byteValue(8);
    raw.intValue(0);
    raw.intValue(0);
    raw.byteValue(7);
    SnapshotDeltaCodec.write(raw, before, next, EMPTY);
    raw.byteValue(8);
    var reader = new MessageReader(raw.bytes());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServerMessageCodec.read(reader, 12, EMPTY, ignored -> before));
    assertEquals(0, reader.bitPosition());
  }

  @Test
  void duplicateConfigstringsAndBaselinesKeepTheFinalValue() {
    var writer = gamePrefix();
    config(writer, 12, "before");
    config(writer, 3, "other");
    config(writer, 12, "after");
    config(writer, 3, "");
    writer.byteValue(4);
    EntityDeltaCodec.write(writer, null, entity(7, 2), true);
    writer.byteValue(4);
    EntityDeltaCodec.write(writer, null, entity(7, 3), true);
    finishGame(writer);
    var level = (GameState) read(writer).operations().getFirst();
    assertEquals(Map.of(12, "after"), level.configstrings());
    assertEquals(1, level.baselines().entities().size());
    assertArrayEquals(entity(7, 3), level.baselines().state(7));
  }

  @Test
  void allTruncatedBitsAndUnknownOperationsRollBackTheWholeMessage() {
    var writer = new MessageWriter();
    writer.bits(7, 3);
    ServerMessageCodec.write(
        writer,
        new Message(
            11,
            List.of(
                new GameState(0, Map.of(33, "content"), EMPTY, 0, 1),
                new Command(1, "print hello"))),
        EMPTY);
    for (int limit = 3; limit < writer.bitPosition(); limit++) {
      var reader = new MessageReader(writer.bytes(), limit);
      reader.bits(3);
      assertThrows(
          IllegalArgumentException.class,
          () -> ServerMessageCodec.read(reader, 1, EMPTY, ignored -> null));
      assertEquals(3, reader.bitPosition());
    }
    for (int opcode : new int[] {0, 3, 4, 6, 9, 10, 255}) {
      var invalid = new MessageWriter();
      invalid.intValue(0);
      invalid.byteValue(opcode);
      invalid.byteValue(8);
      var reader = new MessageReader(invalid.bytes());
      assertThrows(
          IllegalArgumentException.class,
          () -> ServerMessageCodec.read(reader, 1, EMPTY, ignored -> null));
      assertEquals(0, reader.bitPosition());
    }
    var limited = new MessageWriter(4);
    limited.bits(5, 3);
    byte[] bytes = limited.bytes();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ServerMessageCodec.write(
                limited, new Message(42, List.of(new Command(1, "hello"))), EMPTY));
    assertEquals(3, limited.bitPosition());
    assertArrayEquals(bytes, limited.bytes());
  }

  @Test
  void rejectsMalformedLevelRecordsAndChargesAllReceivedStringBytes() {
    var invalidIndex = gamePrefix();
    config(invalidIndex, -1, "hello");
    finishGame(invalidIndex);
    assertThrows(IllegalArgumentException.class, () -> read(invalidIndex));
    var invalidBaseline = gamePrefix();
    invalidBaseline.byteValue(4);
    EntityDeltaCodec.writeEnd(invalidBaseline);
    finishGame(invalidBaseline);
    assertThrows(IllegalArgumentException.class, () -> read(invalidBaseline));
    var removedBaseline = gamePrefix();
    removedBaseline.byteValue(4);
    EntityDeltaCodec.write(removedBaseline, entity(2, 1), null, false);
    finishGame(removedBaseline);
    assertThrows(IllegalArgumentException.class, () -> read(removedBaseline));
    var unknown = gamePrefix();
    unknown.byteValue(7);
    finishGame(unknown);
    assertThrows(IllegalArgumentException.class, () -> read(unknown));
    // Duplicate indices still consume native gamestate storage while parsing.
    var duplicate = gamePrefix();
    config(duplicate, 3, "a".repeat(7999));
    config(duplicate, 3, "a".repeat(7999));
    finishGame(duplicate);
    assertThrows(IllegalArgumentException.class, () -> read(duplicate));
    assertDoesNotThrow(
        () -> new GameState(0, Map.of(0, "a".repeat(7900), 1, "a".repeat(8097)), EMPTY, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GameState(0, Map.of(0, "a".repeat(7900), 1, "a".repeat(8098)), EMPTY, 0, 0));
  }

  @Test
  void publicValuesOwnCollectionsAndBoundCountsAndByteStrings() {
    var strings = new TreeMap<Integer, String>();
    strings.put(1, "value");
    var level = new GameState(0, strings, EMPTY, 0, 0);
    strings.clear();
    assertEquals("value", level.configstrings().get(1));
    assertThrows(UnsupportedOperationException.class, () -> level.configstrings().clear());
    var operations = new ArrayList<Operation>(List.of(level));
    var message = new Message(0, operations);
    operations.clear();
    assertEquals(1, message.operations().size());
    assertThrows(UnsupportedOperationException.class, () -> message.operations().clear());
    assertThrows(IllegalArgumentException.class, () -> new Command(1, "x".repeat(1024)));
    assertThrows(IllegalArgumentException.class, () -> new Command(1, "x\0tail"));
    assertThrows(IllegalArgumentException.class, () -> new Command(1, "\u2603"));
    assertThrows(
        IllegalArgumentException.class, () -> new GameState(0, Map.of(1024, "x"), EMPTY, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GameState(0, Map.of(0, "x".repeat(8192)), EMPTY, 0, 0));
    for (int i = 0; i < 4097; i++) operations.add(NoOp.INSTANCE);
    assertThrows(IllegalArgumentException.class, () -> new Message(0, operations));
    var raw = new MessageWriter();
    raw.intValue(0);
    for (int i = 0; i < 4097; i++) raw.byteValue(1);
    raw.byteValue(8);
    assertThrows(IllegalArgumentException.class, () -> read(raw));
  }

  private static Message read(MessageWriter writer) {
    return ServerMessageCodec.read(new MessageReader(writer.bytes()), 1, EMPTY, ignored -> null);
  }

  private static MessageWriter gamePrefix() {
    var writer = new MessageWriter();
    writer.intValue(0);
    writer.byteValue(2);
    writer.intValue(0);
    return writer;
  }

  private static void config(MessageWriter writer, int index, String text) {
    writer.byteValue(3);
    writer.shortValue(index);
    writer.bigStringValue(text);
  }

  private static void finishGame(MessageWriter writer) {
    writer.byteValue(8);
    writer.intValue(0);
    writer.intValue(0);
    writer.byteValue(8);
  }

  private static Snapshot snapshot(int sequence, List<byte[]> entities) {
    return new Snapshot(sequence, sequence * 50, 0, new byte[0], new byte[468], entities);
  }

  private static byte[] entity(int id, int type) {
    byte[] result = new byte[208];
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(0, id).putInt(4, type);
    return result;
  }
}
