package dev.bluevista.craftq3.client.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.demo.DemoReader;
import dev.bluevista.craftq3.core.demo.DemoRecord;
import dev.bluevista.craftq3.core.demo.Protocol68DemoReader;
import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Protocol68DemoRecorderTest {
  private static final GameState GAME =
      new GameState(7, Map.of(0, "fixture", 1, "\\sv_serverid\\42"), Baselines.EMPTY, 3, 99);

  @Test
  void initialStateUsesCurrentSequencesAndOmitsNativeZeroNumberBaseline() throws Exception {
    var data = new ByteArrayOutputStream();
    var game =
        new GameState(
            19,
            Map.of(2, "second", 0, "first", 9, ""),
            new Baselines(List.of(entity(0, 7), entity(4, 8))),
            5,
            -77);
    try (var recorder = new Protocol68DemoRecorder(data, 4096)) {
      recorder.start(game, 125, 211);
      assertEquals(Protocol68DemoRecorder.State.WAITING, recorder.state());
      assertEquals(1, recorder.recordsWritten());
      assertEquals(data.size(), recorder.bytesWritten());
      recorder.finish();
      recorder.finish();
      assertEquals(Protocol68DemoRecorder.State.FINISHED, recorder.state());
    }
    try (var reader = new Protocol68DemoReader(new ByteArrayInputStream(data.toByteArray()))) {
      var first = reader.next().orElseThrow();
      assertEquals(124, first.sequence());
      assertEquals(211, first.message().reliableAcknowledge());
      var level = reader.gameState().orElseThrow();
      assertEquals(19, level.commandSequence());
      assertEquals(5, level.clientNumber());
      assertEquals(-77, level.checksumFeed());
      assertEquals(Map.of(0, "first", 2, "second"), level.configstrings());
      assertArrayEquals(entity(4, 8), level.baselines().entities().getFirst());
      assertEquals(1, level.baselines().entities().size());
      assertTrue(reader.next().isEmpty());
      assertEquals(DemoReader.End.MARKER, reader.end());
    }
  }

  @Test
  void waitsForFullSnapshotAndRetainsItsWholePacketIncludingCommandsAndTrailingBytes()
      throws Exception {
    var data = new ByteArrayOutputStream();
    var old = snapshot(10);
    var current = snapshot(11);
    var command = new Message(3, List.of(new Command(8, "cs 5 before")));
    var delta = new Message(3, List.of(new Frame(current, old)));
    var full =
        new Message(
            4,
            List.of(
                new Command(9, "print first"),
                new Frame(snapshot(12), null),
                new Command(10, "print last")));
    var raw = record(12, full);
    byte[] bytes = Arrays.copyOf(raw.payload(), raw.payload().length + 3);
    bytes[bytes.length - 3] = (byte) 0xfe;
    bytes[bytes.length - 2] = 37;
    bytes[bytes.length - 1] = 91;
    raw = new DemoRecord(12, bytes);
    try (var recorder = new Protocol68DemoRecorder(data, 4096)) {
      recorder.start(GAME, 9, 22);
      int initial = data.size();
      assertFalse(recorder.accept(record(10, command), command));
      assertFalse(recorder.accept(record(11, delta), delta));
      assertEquals(initial, data.size());
      assertEquals(1, recorder.recordsWritten());
      assertTrue(recorder.accept(raw, full));
      assertEquals(Protocol68DemoRecorder.State.RECORDING, recorder.state());
      assertTrue(recorder.accept(record(13, command), command));
      assertEquals(3, recorder.recordsWritten());
    }
    try (var reader = new DemoReader(new ByteArrayInputStream(data.toByteArray()))) {
      reader.next();
      var saved = reader.next().orElseThrow();
      assertEquals(12, saved.sequence());
      assertArrayEquals(bytes, saved.payload());
      var decoded =
          ServerMessageCodec.read(
              new MessageReader(saved.payload()), 12, Baselines.EMPTY, ignored -> null);
      assertEquals(3, decoded.operations().size());
      assertEquals("print first", ((Command) decoded.operations().getFirst()).text());
      assertEquals("print last", ((Command) decoded.operations().getLast()).text());
      assertEquals(13, reader.next().orElseThrow().sequence());
      assertTrue(reader.next().isEmpty());
    }
  }

  @Test
  void laterGamestatePreservesActiveRecordingAndWaitingGamestateDoesNotUnlockIt() throws Exception {
    var data = new ByteArrayOutputStream();
    var level =
        new Message(1, List.of(new GameState(33, Map.of(0, "next"), Baselines.EMPTY, 2, 77)));
    var full = new Message(1, List.of(new Frame(snapshot(22), null)));
    try (var recorder = new Protocol68DemoRecorder(data, 4096)) {
      recorder.start(GAME, 20, 1);
      assertFalse(recorder.accept(record(21, level), level));
      assertEquals(Protocol68DemoRecorder.State.WAITING, recorder.state());
      recorder.accept(record(22, full), full);
      assertTrue(recorder.accept(record(23, level), level));
      assertEquals(Protocol68DemoRecorder.State.RECORDING, recorder.state());
      var command = new Message(1, List.of(new Command(34, "map_restart")));
      assertTrue(recorder.accept(record(24, command), command));
      assertEquals(4, recorder.recordsWritten());
    }
  }

  @Test
  void byteBoundRejectsBeforeWritingAndReservesCanonicalEndMarker() throws Exception {
    var initial = new ByteArrayOutputStream();
    try (var recorder = new Protocol68DemoRecorder(initial, 4096)) {
      recorder.start(GAME, 9, 7);
    }
    int exact = initial.size();
    var data = new ByteArrayOutputStream();
    try (var recorder = new Protocol68DemoRecorder(data, exact)) {
      recorder.start(GAME, 9, 7);
      int prefix = data.size();
      var full = new Message(1, List.of(new Frame(snapshot(10), null)));
      assertThrows(IOException.class, () -> recorder.accept(record(10, full), full));
      assertEquals(prefix, data.size());
      assertEquals(Protocol68DemoRecorder.State.WAITING, recorder.state());
      recorder.finish();
      assertEquals(exact, recorder.bytesWritten());
    }
    assertArrayEquals(initial.toByteArray(), data.toByteArray());
    var tooSmall = new ByteArrayOutputStream();
    try (var recorder = new Protocol68DemoRecorder(tooSmall, exact - 1)) {
      assertThrows(IOException.class, () -> recorder.start(GAME, 9, 7));
      assertEquals(Protocol68DemoRecorder.State.NEW, recorder.state());
    }
    assertEquals(0, tooSmall.size());
  }

  @Test
  void ioFailurePoisonsRecordingAndCloseDoesNotInventAnEndMarker() throws Exception {
    class Failing extends OutputStream {
      final ByteArrayOutputStream data = new ByteArrayOutputStream();
      int writes, closes, flushes;

      public void write(int value) {
        throw new AssertionError("Unexpected single-byte write");
      }

      public void write(byte[] bytes, int offset, int length) throws IOException {
        if (++writes == 4) throw new IOException("authored disk full");
        data.write(bytes, offset, length);
      }

      public void flush() {
        flushes++;
      }

      public void close() {
        closes++;
      }
    }
    var output = new Failing();
    var recorder = new Protocol68DemoRecorder(output, 4096);
    recorder.start(GAME, 9, 2);
    long initial = recorder.bytesWritten();
    var full = new Message(1, List.of(new Frame(snapshot(10), null)));
    assertThrows(IOException.class, () -> recorder.accept(record(10, full), full));
    assertEquals(Protocol68DemoRecorder.State.FAILED, recorder.state());
    assertEquals(initial, recorder.bytesWritten());
    assertEquals(1, recorder.recordsWritten());
    assertThrows(IOException.class, recorder::finish);
    assertThrows(IOException.class, () -> recorder.accept(record(10, full), full));
    int size = output.data.size();
    recorder.close();
    recorder.close();
    assertEquals(size, output.data.size());
    assertEquals(0, output.flushes);
    assertEquals(1, output.closes);
    assertEquals(Protocol68DemoRecorder.State.CLOSED, recorder.state());
  }

  @Test
  void validatesOwnershipLifecycleAndMatchingDecodedSequence() throws Exception {
    class Tracked extends ByteArrayOutputStream {
      int closes;

      public void close() {
        closes++;
      }
    }
    var output = new Tracked();
    var recorder = new Protocol68DemoRecorder(output, 4096);
    var full = new Message(1, List.of(new Frame(snapshot(10), null)));
    assertThrows(IllegalStateException.class, () -> recorder.accept(record(10, full), full));
    recorder.finish();
    assertEquals(0, output.size());
    recorder.start(GAME, 9, 1);
    int length = output.size();
    assertThrows(IllegalStateException.class, () -> recorder.start(GAME, 9, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> recorder.accept(new DemoRecord(11, record(10, full).payload()), full));
    assertEquals(length, output.size());
    assertEquals(Protocol68DemoRecorder.State.WAITING, recorder.state());
    recorder.close();
    recorder.close();
    recorder.finish();
    assertEquals(1, output.closes);
    assertThrows(IllegalStateException.class, () -> recorder.start(GAME, 9, 1));
    assertThrows(IllegalStateException.class, () -> recorder.accept(record(10, full), full));
    assertThrows(IllegalArgumentException.class, () -> new Protocol68DemoRecorder(output, 7));
  }

  @Test
  void neverStartedRecorderClosesWithoutWritingOrFlushing() throws Exception {
    class Tracked extends ByteArrayOutputStream {
      int closes, flushes;

      public void close() {
        closes++;
      }

      public void flush() {
        flushes++;
      }
    }
    var output = new Tracked();
    var recorder = new Protocol68DemoRecorder(output, 8);
    recorder.close();
    recorder.close();
    assertEquals(0, output.size());
    assertEquals(0, output.flushes);
    assertEquals(1, output.closes);
  }

  private static DemoRecord record(int sequence, Message message) {
    var writer = new MessageWriter();
    ServerMessageCodec.write(writer, message, Baselines.EMPTY);
    return new DemoRecord(sequence, writer.bytes());
  }

  private static Snapshot snapshot(int sequence) {
    return new Snapshot(sequence, sequence * 50, 0, new byte[0], new byte[468], List.of());
  }

  private static byte[] entity(int number, int type) {
    byte[] bytes = new byte[208];
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, number).putInt(4, type);
    return bytes;
  }
}
