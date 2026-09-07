package dev.bluevista.craftq3.core.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Protocol68DemoReaderTest {
  private static final Baselines EMPTY = Baselines.EMPTY;

  @Test
  void decodesLevelDeltaHistoryAndCommandsWithoutExecutingThem() throws Exception {
    var data = new ByteArrayOutputStream();
    var base = new Baselines(List.of(entity(3, 9)));
    var first = snapshot(11, 100, 9);
    var second = snapshot(12, 150, 10);
    try (var output = new DemoWriter(data)) {
      output.append(
          record(
              10, new Message(0, List.of(new GameState(7, Map.of(0, "map"), base, 1, 42))), EMPTY));
      output.append(record(11, new Message(0, List.of(new Frame(first, null))), base));
      output.append(
          record(
              12,
              new Message(0, List.of(new Command(8, "cs 0 changed"), new Frame(second, first))),
              base));
    }
    try (var input = new Protocol68DemoReader(new ByteArrayInputStream(data.toByteArray()))) {
      assertTrue(input.gameState().isEmpty());
      assertTrue(input.latestSnapshot().isEmpty());
      assertEquals(10, input.next().orElseThrow().sequence());
      assertEquals("map", input.gameState().orElseThrow().configstrings().get(0));
      input.next();
      assertEquals(100, input.latestSnapshot().orElseThrow().time());
      var last = input.next().orElseThrow();
      assertEquals(12, last.sequence());
      assertInstanceOf(Command.class, last.message().operations().getFirst());
      assertArrayEquals(entity(3, 10), input.latestSnapshot().orElseThrow().entities().getFirst());
      assertEquals("map", input.gameState().orElseThrow().configstrings().get(0));
      assertTrue(input.next().isEmpty());
      assertEquals(DemoReader.End.MARKER, input.end());
      assertEquals(3, input.recordsRead());
      assertTrue(input.next().isEmpty());
    }
  }

  @Test
  void levelResetClearsPriorSnapshotsAndUnsupportedMessagesPublishNothing() throws Exception {
    var data = new ByteArrayOutputStream();
    var first = snapshot(1, 100, 9);
    try (var output = new DemoWriter(data)) {
      output.append(record(1, new Message(0, List.of(new Frame(first, null))), EMPTY));
      output.append(
          record(
              2, new Message(0, List.of(new GameState(4, Map.of(0, "new"), EMPTY, 0, 7))), EMPTY));
      var partial = new MessageWriter();
      ServerMessageCodec.write(
          partial, new Message(0, List.of(new GameState(9, Map.of(0, "bad"), EMPTY, 0, 8))), EMPTY);
      // Truncate the complete message so the apparent new level can never be committed.
      byte[] bytes = partial.bytes();
      output.append(new DemoRecord(3, java.util.Arrays.copyOf(bytes, bytes.length - 2)));
    }
    try (var input = new Protocol68DemoReader(new ByteArrayInputStream(data.toByteArray()))) {
      input.next();
      assertTrue(input.latestSnapshot().isPresent());
      input.next();
      assertTrue(input.latestSnapshot().isEmpty());
      assertThrows(IOException.class, input::next);
      assertEquals("new", input.gameState().orElseThrow().configstrings().get(0));
      assertEquals(4, input.gameState().orElseThrow().commandSequence());
      assertThrows(IOException.class, input::next);
    }
  }

  @Test
  void missingAndExpiredHistoryFailInsteadOfInventingPlayerState() throws Exception {
    var old = snapshot(1, 50, 1);
    var far = snapshot(34, 100, 2);
    var data = new ByteArrayOutputStream();
    try (var output = new DemoWriter(data)) {
      output.append(record(1, new Message(0, List.of(new Frame(old, null))), EMPTY));
      output.append(record(34, new Message(0, List.of(new Frame(far, null))), EMPTY));
      output.append(
          record(35, new Message(0, List.of(new Frame(snapshot(35, 150, 3), old))), EMPTY));
    }
    try (var input = new Protocol68DemoReader(new ByteArrayInputStream(data.toByteArray()))) {
      input.next();
      input.next();
      assertThrows(IOException.class, input::next);
      assertEquals(34, input.latestSnapshot().orElseThrow().sequence());
      assertThrows(IOException.class, input::next);
    }
  }

  @Test
  void distinguishesPhysicalEofAndOwnsTheInputStream() throws Exception {
    class TrackedInput extends ByteArrayInputStream {
      boolean closed;

      TrackedInput() {
        super(new byte[0]);
      }

      @Override
      public void close() {
        closed = true;
      }
    }
    var stream = new TrackedInput();
    var reader = new Protocol68DemoReader(stream);
    assertTrue(reader.next().isEmpty());
    assertEquals(DemoReader.End.PHYSICAL_EOF, reader.end());
    reader.close();
    reader.close();
    assertTrue(stream.closed);
    assertThrows(IOException.class, reader::next);
  }

  private static DemoRecord record(int sequence, Message message, Baselines baselines) {
    var output = new MessageWriter();
    ServerMessageCodec.write(output, message, baselines);
    return new DemoRecord(sequence, output.bytes());
  }

  private static Snapshot snapshot(int sequence, int time, int type) {
    return new Snapshot(sequence, time, 0, new byte[0], new byte[468], List.of(entity(3, type)));
  }

  private static byte[] entity(int id, int type) {
    byte[] result = new byte[208];
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(0, id).putInt(4, type);
    return result;
  }
}
