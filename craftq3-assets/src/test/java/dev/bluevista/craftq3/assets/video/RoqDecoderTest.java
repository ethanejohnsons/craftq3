package dev.bluevista.craftq3.assets.video;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoqDecoderTest {
  private static byte[] chunk(int id, int argument, byte... payload) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(id);
    out.write(id >>> 8);
    int size = payload.length;
    for (int i = 0; i < 4; i++) out.write(size >>> (8 * i));
    out.write(argument);
    out.write(argument >>> 8);
    out.write(payload);
    return out.toByteArray();
  }

  private static byte[] bytes(int... values) {
    byte[] b = new byte[values.length];
    for (int i = 0; i < b.length; i++) b[i] = (byte) values[i];
    return b;
  }

  private static byte[] file(byte[]... chunks) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(bytes(0x84, 0x10, 255, 255, 255, 255, 30, 0));
    for (byte[] c : chunks) out.write(c);
    return out.toByteArray();
  }

  private static byte[] info() throws IOException {
    return chunk(0x1001, 0, bytes(16, 0, 16, 0, 8, 0, 4, 0));
  }

  private static byte[] book(int y) throws IOException {
    return chunk(0x1002, 257, bytes(y, y, y, y, 128, 128, 0, 0, 0, 0));
  }

  private static byte[] solid() throws IOException {
    return chunk(0x1011, 0, bytes(0, 0xaa, 0, 0, 0, 0));
  }

  private static RoqDecoder decoder(byte[]... chunks) throws IOException {
    return new RoqDecoder(new ByteArrayInputStream(file(chunks)));
  }

  private static RoqDecoder.Video frame(RoqDecoder d) throws IOException {
    return (RoqDecoder.Video) d.next().orElseThrow();
  }

  @Test
  void decodesExpandedQuadsAndOwnsPixels() throws Exception {
    try (var d = decoder(info(), book(91), solid())) {
      var f = frame(d);
      assertEquals(16, f.width());
      assertEquals(0, f.milliseconds());
      assertEquals(0x5b5b5bff, f.image().rgbaAt(15, 15));
      byte[] pixels = f.yuv();
      pixels[0] = 0;
      assertEquals(91, f.yuv()[0]);
      assertTrue(d.next().isEmpty());
      assertTrue(d.next().isEmpty());
    }
  }

  @Test
  void permitsOnlyOneUnusedWordAtAnExhaustedCodewordBoundary() throws Exception {
    // One subdivided 8x8, four solid 4x4s and three solid 8x8s exhaust exactly eight modes.
    byte[] modes = bytes(0xaa, 0xea, 0, 0, 0, 0, 0, 0, 0);
    for (byte[] suffix : new byte[][] {bytes(), bytes(0, 0), bytes(0x5c, 0x6a)}) {
      var payload = new ByteArrayOutputStream();
      payload.write(modes);
      payload.write(suffix);
      try (var d =
          decoder(info(), book(47), chunk(0x1011, 0, payload.toByteArray()), book(72), solid())) {
        var decoded = frame(d).yuv();
        for (int pixel = 0; pixel < 256; pixel++) assertEquals(47, decoded[pixel]);
        assertEquals(72, frame(d).yuv()[0]);
        assertTrue(d.next().isEmpty());
      }
    }
    for (byte[] invalid :
        new byte[][] {
          bytes(0xaa, 0xea, 0, 0, 0, 0, 0, 0, 0, 0),
          bytes(0xaa, 0xea, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
          bytes(0, 0xaa, 0, 0, 0, 0, 0, 0)
        }) {
      try (var d = decoder(info(), book(47), chunk(0x1011, 0, invalid))) {
        assertThrows(IOException.class, d::next);
        assertThrows(IOException.class, d::next);
      }
    }
  }

  @Test
  void skipsUseTwoFrameHistoryAndMotionUsesPreviousFrame() throws Exception {
    try (var d =
        decoder(
            info(),
            book(10),
            solid(),
            book(20),
            solid(),
            chunk(0x1011, 0, bytes(0, 0)),
            chunk(0x1011, 0xff00, bytes(0, 0x55, 0x98, 0x98, 0x98, 0x98)))) {
      assertEquals(10, frame(d).yuv()[0]);
      assertEquals(20, frame(d).yuv()[0]);
      assertEquals(10, frame(d).yuv()[0]);
      var last = frame(d);
      assertEquals(10, last.yuv()[0]);
      assertEquals(100, last.milliseconds());
    }
  }

  @Test
  void firstFrameSeedsBothBuffers() throws Exception {
    try (var d = decoder(info(), book(72), solid(), chunk(0x1011, 0, bytes(0, 0)))) {
      frame(d);
      assertEquals(72, frame(d).yuv()[0]);
    }
  }

  @Test
  void partialCodebooksKeepPreviouslyExpandedQuads() throws Exception {
    try (var d =
        decoder(
            info(),
            book(12),
            solid(),
            chunk(0x1002, 256, bytes(90, 90, 90, 90, 128, 128)),
            solid())) {
      frame(d);
      assertEquals(12, frame(d).yuv()[0]);
    }
  }

  @Test
  void subQuadsAndExplicitTwoPixelCellsRespectCodewordBoundaries() throws Exception {
    // Each 8x8 subdivides, then all four 4x4 blocks subdivide to explicit 2x2 cells.
    var payload = new ByteArrayOutputStream();
    int left = 0;
    for (int quadrant = 0; quadrant < 4; quadrant++) {
      if (left == 0) {
        payload.write(255);
        payload.write(255);
        left = 8;
      }
      left--;
      for (int part = 0; part < 4; part++) {
        if (left == 0) {
          payload.write(255);
          payload.write(255);
          left = 8;
        }
        left--;
        payload.write(new byte[4]);
      }
    }
    try (var d = decoder(info(), book(47), chunk(0x1011, 0, payload.toByteArray()))) {
      var f = frame(d);
      assertEquals(0x2f2f2fff, f.image().rgbaAt(14, 14));
    }
  }

  @Test
  void monoSquaredDeltasWrapAndPreservePosition() throws Exception {
    try (var d = decoder(chunk(0x1020, 32760, bytes(4, 129, 128, 0)), chunk(0x1020, 0, bytes(2)))) {
      var a = (RoqDecoder.Audio) d.next().orElseThrow();
      assertEquals(0, a.firstSample());
      assertEquals(-32760, a.sound().sample16(0, 0));
      assertEquals(-32761, a.sound().sample16(1, 0));
      var b = (RoqDecoder.Audio) d.next().orElseThrow();
      assertEquals(4, b.firstSample());
      assertEquals(4, b.sound().sample16(0, 0));
    }
  }

  @Test
  void stereoPredictorsUseOppositeHeaderBytes() throws Exception {
    try (var d = decoder(chunk(0x1021, 0x12fe, bytes(1, 130, 2, 3)))) {
      var a = (RoqDecoder.Audio) d.next().orElseThrow();
      assertEquals(4609, a.sound().sample16(0, 0));
      assertEquals(-516, a.sound().sample16(0, 1));
      assertEquals(4613, a.sound().sample16(1, 0));
      assertEquals(-507, a.sound().sample16(1, 1));
    }
  }

  @Test
  void rejectsMalformedGeometryCodebooksAndMotion() throws Exception {
    for (byte[][] chunks :
        new byte[][][] {
          {chunk(0x1001, 0, bytes(17, 0, 16, 0, 8, 0, 4, 0))},
          {info(), chunk(0x1002, 257, new byte[6])},
          {info(), solid()},
          {info(), book(1), chunk(0x1011, 0, bytes(0, 0x55, 255, 255, 255, 255))},
          {info(), book(1), chunk(0x1011, 0, bytes(0))},
          {chunk(0x1021, 0, bytes(1))},
          {chunk(0x1012, 0, bytes(1))}
        }) {
      try (var d = decoder(chunks)) {
        assertThrows(IOException.class, d::next);
        assertThrows(IOException.class, d::next);
      }
    }
  }

  @Test
  void rejectsTruncationAndOversizedChunksWithoutAllocating() throws Exception {
    byte[] good = file(info(), book(42), solid());
    for (int size : new int[] {0, 1, 7, 9, good.length - 1}) {
      byte[] truncated = Arrays.copyOf(good, size);
      assertThrows(
          IOException.class,
          () -> {
            try (var d = new RoqDecoder(new ByteArrayInputStream(truncated))) {
              while (d.next().isPresent()) {}
            }
          });
    }
    byte[] huge = file(bytes(1, 16, 0, 0, 0, 1, 0, 0));
    try (var d = new RoqDecoder(new ByteArrayInputStream(huge))) {
      assertThrows(IOException.class, d::next);
    }
  }

  @Test
  void closesOwnedInputOnFailure() throws Exception {
    boolean[] closed = {false};
    var input =
        new ByteArrayInputStream(new byte[8]) {
          @Override
          public void close() {
            closed[0] = true;
          }
        };
    assertThrows(IOException.class, () -> new RoqDecoder(input));
    assertTrue(closed[0]);
  }
}
