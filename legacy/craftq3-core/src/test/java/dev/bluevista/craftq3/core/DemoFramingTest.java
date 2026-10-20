package dev.bluevista.craftq3.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.demo.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DemoFramingTest {
  @Test
  void byteExactHeaderPayloadAndMarker() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var writer = new DemoWriter(bytes)) {
      writer.append(new DemoRecord(0x12345678, new byte[] {1, 2, 3}));
      writer.finish();
      writer.finish();
      assertThrows(IOException.class, () -> writer.append(new DemoRecord(2, new byte[0])));
    }
    assertEquals(
        "7856341203000000010203ffffffffffffffff", HexFormat.of().formatHex(bytes.toByteArray()));
    try (var reader = new DemoReader(new ByteArrayInputStream(bytes.toByteArray()))) {
      var record = reader.next().orElseThrow();
      assertEquals(0x12345678, record.sequence());
      assertArrayEquals(new byte[] {1, 2, 3}, record.payload());
      assertTrue(reader.next().isEmpty());
      assertTrue(reader.next().isEmpty());
      assertEquals(DemoReader.End.MARKER, reader.end());
      assertEquals(1, reader.recordsRead());
    }
  }

  @Test
  void boundaryEofDiffersFromInterruptedHeaderOrPayload() throws Exception {
    try (var reader = new DemoReader(new ByteArrayInputStream(new byte[0]))) {
      assertTrue(reader.next().isEmpty());
      assertEquals(DemoReader.End.PHYSICAL_EOF, reader.end());
    }
    for (int size = 1; size < 8; size++) {
      try (var reader = new DemoReader(new ByteArrayInputStream(new byte[size]))) {
        assertThrows(EOFException.class, reader::next);
        assertThrows(IOException.class, reader::next);
      }
    }
    byte[] shortPayload =
        ByteBuffer.allocate(9)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1)
            .putInt(2)
            .put((byte) 0)
            .array();
    try (var reader = new DemoReader(new ByteArrayInputStream(shortPayload))) {
      assertThrows(EOFException.class, reader::next);
    }
  }

  @Test
  void lengthIsCheckedBeforeAllocationOrPayloadRead() throws Exception {
    for (int length : new int[] {-2, -1, 16385, Integer.MAX_VALUE}) {
      byte[] header =
          ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(length).array();
      try (var reader = new DemoReader(new ByteArrayInputStream(header))) {
        assertThrows(IOException.class, reader::next);
      }
    }
    assertThrows(IllegalArgumentException.class, () -> new DemoRecord(0, new byte[16385]));
  }

  @Test
  void emptyAndMaximumMessagesAndNonconsecutiveSequences() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var writer = new DemoWriter(bytes)) {
      writer.append(new DemoRecord(0, new byte[0]));
      writer.append(new DemoRecord(1400, new byte[16384]));
    }
    try (var reader = new DemoReader(new ByteArrayInputStream(bytes.toByteArray()))) {
      assertEquals(0, reader.next().orElseThrow().payload().length);
      var record = reader.next().orElseThrow();
      assertEquals(1400, record.sequence());
      assertEquals(16384, record.payload().length);
      assertTrue(reader.next().isEmpty());
    }
  }

  @Test
  void ownedRecordsAndFailedWritesDoNotProduceFalseEndMarker() throws Exception {
    byte[] data = {1};
    var record = new DemoRecord(1, data);
    data[0] = 2;
    record.payload()[0] = 3;
    assertEquals(1, record.payload()[0]);
    var sink =
        new OutputStream() {
          int writes;
          boolean closed;

          @Override
          public void write(int value) throws IOException {
            writes++;
            throw new IOException("fixture failure");
          }

          @Override
          public void close() {
            closed = true;
          }
        };
    var writer = new DemoWriter(sink);
    assertThrows(IOException.class, () -> writer.append(record));
    writer.close();
    assertTrue(sink.closed);
    assertEquals(1, sink.writes);
  }
}
