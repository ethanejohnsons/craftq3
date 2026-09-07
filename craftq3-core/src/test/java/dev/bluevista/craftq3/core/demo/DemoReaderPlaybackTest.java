package dev.bluevista.craftq3.core.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DemoReaderPlaybackTest {
  private static byte[] header(int sequence, int length) {
    return ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(sequence)
        .putInt(length)
        .array();
  }

  @Test
  void playbackAcceptsAnySequenceWithNativeNegativeOneLengthMarker() throws Exception {
    for (int sequence : new int[] {Integer.MIN_VALUE, -1, 0, 42, Integer.MAX_VALUE}) {
      var input = new ByteArrayInputStream(header(sequence, -1));
      try (var reader = new DemoReader(input, DemoReader.Policy.PLAYBACK)) {
        assertTrue(reader.next().isEmpty());
        assertEquals(DemoReader.End.MARKER, reader.end());
        assertTrue(reader.next().isEmpty());
        assertEquals(0, reader.recordsRead());
      }
    }
    try (var strict = new DemoReader(new ByteArrayInputStream(header(42, -1)))) {
      assertThrows(IOException.class, strict::next);
    }
  }

  @Test
  void shortHeadersCompleteWithTheirOwnReasonAndDoNotPublishARecord() throws Exception {
    for (int length = 0; length < 8; length++) {
      try (var reader =
          new DemoReader(new ByteArrayInputStream(new byte[length]), DemoReader.Policy.PLAYBACK)) {
        assertTrue(reader.next().isEmpty());
        assertEquals(
            length == 0 ? DemoReader.End.PHYSICAL_EOF : DemoReader.End.TRUNCATED_HEADER,
            reader.end());
        assertEquals(0, reader.recordsRead());
        assertTrue(reader.next().isEmpty());
      }
    }
  }

  @Test
  void truncatedPayloadCompletesAfterPriorRecordsWithoutPublishingPartialBytes() throws Exception {
    for (int available : new int[] {0, 1, 7, 16383}) {
      var bytes = new ByteArrayOutputStream();
      bytes.write(header(17, 1));
      bytes.write(91);
      bytes.write(header(19, 16384));
      bytes.write(new byte[available]);
      try (var reader =
          new DemoReader(
              new ByteArrayInputStream(bytes.toByteArray()), DemoReader.Policy.PLAYBACK)) {
        var first = reader.next().orElseThrow();
        assertEquals(17, first.sequence());
        assertArrayEquals(new byte[] {91}, first.payload());
        assertTrue(reader.next().isEmpty());
        assertEquals(DemoReader.End.TRUNCATED_PAYLOAD, reader.end());
        assertEquals(1, reader.recordsRead());
        assertTrue(reader.next().isEmpty());
      }
    }
  }

  @Test
  void playbackStillRejectsUnsafeLengthsBeforeReadingTheirPayloadAndPoisonsRetries()
      throws Exception {
    for (int length : new int[] {Integer.MIN_VALUE, -2, 16385, Integer.MAX_VALUE}) {
      var bytes = new ByteArrayOutputStream();
      bytes.write(header(-1, length));
      bytes.write(91);
      var input = new ByteArrayInputStream(bytes.toByteArray());
      try (var reader = new DemoReader(input, DemoReader.Policy.PLAYBACK)) {
        assertThrows(IOException.class, reader::next);
        assertEquals(1, input.available());
        assertEquals(DemoReader.End.NONE, reader.end());
        assertThrows(IOException.class, reader::next);
        assertEquals(1, input.available());
      }
    }
  }

  @Test
  void validEmptyAndMaximumRecordsStillReachDistinctPhysicalEof() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(-1, 0));
    bytes.write(header(300, 16384));
    bytes.write(new byte[16384]);
    try (var reader =
        new DemoReader(new ByteArrayInputStream(bytes.toByteArray()), DemoReader.Policy.PLAYBACK)) {
      assertEquals(-1, reader.next().orElseThrow().sequence());
      assertEquals(16384, reader.next().orElseThrow().payload().length);
      assertTrue(reader.next().isEmpty());
      assertEquals(DemoReader.End.PHYSICAL_EOF, reader.end());
      assertEquals(2, reader.recordsRead());
    }
  }

  @Test
  void realIoErrorsRemainFailuresAndTheReaderStillOwnsItsStream() throws Exception {
    var input =
        new InputStream() {
          boolean closed;

          @Override
          public int read() throws IOException {
            throw new IOException("fixture I/O failure");
          }

          @Override
          public void close() {
            closed = true;
          }
        };
    var reader = new DemoReader(input, DemoReader.Policy.PLAYBACK);
    assertEquals("fixture I/O failure", assertThrows(IOException.class, reader::next).getMessage());
    assertThrows(IOException.class, reader::next);
    reader.close();
    reader.close();
    assertTrue(input.closed);
    assertThrows(IOException.class, reader::next);
  }
}
