package dev.bluevista.craftq3.client.video;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.video.RoqDecoder;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoqPlaybackTest {
  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
    return result;
  }

  private static byte[] chunk(int id, int argument, byte[] data) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(
        bytes(
            id,
            id >>> 8,
            data.length,
            data.length >>> 8,
            data.length >>> 16,
            data.length >>> 24,
            argument,
            argument >>> 8));
    out.write(data);
    return out.toByteArray();
  }

  private static byte[] movie(int frames, int audioSamples) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(bytes(0x84, 0x10, 255, 255, 255, 255, 30, 0));
    out.write(chunk(0x1001, 0, bytes(16, 0, 16, 0, 8, 0, 4, 0)));
    if (audioSamples > 0) out.write(chunk(0x1020, 0, new byte[audioSamples]));
    for (int frame = 0; frame < frames; frame++) {
      out.write(chunk(0x1002, 257, bytes(frame, frame, frame, frame, 128, 128, 0, 0, 0, 0)));
      out.write(chunk(0x1011, 0, bytes(0, 0xaa, 0, 0, 0, 0)));
    }
    return out.toByteArray();
  }

  private static final class Source implements RoqPlayback.Source {
    final byte[] bytes;
    int opened, closed;

    Source(byte[] bytes) {
      this.bytes = bytes;
    }

    public InputStream open() {
      opened++;
      return new ByteArrayInputStream(bytes) {
        boolean done;

        @Override
        public void close() {
          if (!done) {
            closed++;
            done = true;
          }
        }
      };
    }
  }

  private static class Sound implements RoqPlayback.AudioSink {
    final List<Long> starts = new ArrayList<>();
    final List<RoqDecoder.Audio> chunks = new ArrayList<>();
    int ends;

    public void begin(long cycle, long start, int units) {
      starts.add(start);
    }

    public void samples(RoqDecoder.Audio audio) {
      chunks.add(audio);
    }

    public void end() {
      ends++;
    }
  }

  @Test
  void cancellationPreservesBothCleanupFailures() throws Exception {
    byte[] movie = movie(3, 0);
    var sink =
        new Sound() {
          public void end() {
            throw new IllegalStateException("audio cleanup");
          }
        };
    var player =
        new RoqPlayback(
            () ->
                new ByteArrayInputStream(movie) {
                  public void close() throws IOException {
                    throw new IOException("stream cleanup");
                  }
                },
            RoqPlayback.Mode.ONCE,
            false,
            sink);
    var failure = assertThrows(IOException.class, player::close);
    assertEquals("stream cleanup", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals("audio cleanup", failure.getSuppressed()[0].getMessage());
    assertEquals(RoqPlayback.State.CLOSED, player.state());
    player.close();
  }

  @Test
  void presentsAtRationalFrameBoundariesAndStopsAfterFinalFrameDuration() throws Exception {
    var source = new Source(movie(3, 0));
    var sink = new Sound();
    try (var player = new RoqPlayback(source, RoqPlayback.Mode.ONCE, false, sink)) {
      assertEquals(RoqPlayback.State.PLAYING, player.advance(0));
      assertEquals(0, player.video().orElseThrow().number());
      player.advance(33);
      assertEquals(0, player.video().orElseThrow().number());
      player.advance(34);
      assertEquals(1, player.video().orElseThrow().number());
      player.advance(66);
      assertEquals(1, player.video().orElseThrow().number());
      player.advance(67);
      assertEquals(2, player.video().orElseThrow().number());
      assertEquals(1, source.closed); // Input closes as soon as EOF is decoded.
      assertEquals(RoqPlayback.State.PLAYING, player.advance(99));
      assertEquals(RoqPlayback.State.ENDED, player.advance(100));
      assertTrue(player.video().isEmpty());
      assertEquals(1, sink.ends);
    }
    assertEquals(1, source.closed);
    assertEquals(1, sink.ends);
  }

  @Test
  void audioKeepsSamplePositionsAndTailWithoutExtendingVideoDecoding() throws Exception {
    var source = new Source(movie(1, 2205));
    var sink = new Sound();
    try (var player = new RoqPlayback(source, RoqPlayback.Mode.HOLD, false, sink)) {
      player.advance(0);
      assertEquals(1, sink.chunks.size());
      assertEquals(0, sink.chunks.getFirst().firstSample());
      assertEquals(2205, sink.chunks.getFirst().sound().frames());
      assertEquals(RoqPlayback.State.PLAYING, player.advance(99));
      assertEquals(RoqPlayback.State.HELD, player.advance(100));
      assertEquals(0, player.video().orElseThrow().number());
      player.advance(1000);
      assertEquals(1, sink.chunks.size());
      assertEquals(1, sink.ends);
      assertEquals(1, source.opened);
    }
  }

  @Test
  void loopsWithoutMillisecondRoundingDriftAndSkipsKnownWholeCyclesAfterStall() throws Exception {
    var source = new Source(movie(1, 0));
    var sink = new Sound();
    try (var player = new RoqPlayback(source, RoqPlayback.Mode.LOOP, false, sink)) {
      player.advance(0);
      player.advance(33);
      assertEquals(0, player.cycle());
      player.advance(34);
      assertEquals(1, player.cycle());
      player.advance(1000);
      assertEquals(30, player.cycle());
      assertEquals(player.unitsPerSecond(), player.cycleStartUnits());
      assertEquals(3, source.opened);
      assertEquals(3, source.closed);
      assertEquals(2, sink.ends);
      assertEquals(List.of(0L, 735L, 22050L), sink.starts);
    }
    assertEquals(3, sink.ends);
  }

  @Test
  void silentPlaybackDoesNotAcquireOrSubmitAudio() throws Exception {
    var sink = new Sound();
    try (var player =
        new RoqPlayback(new Source(movie(2, 2205)), RoqPlayback.Mode.ONCE, true, sink)) {
      player.advance(0);
      player.advance(100);
      assertEquals(RoqPlayback.State.ENDED, player.state());
    }
    assertTrue(sink.starts.isEmpty());
    assertTrue(sink.chunks.isEmpty());
    assertEquals(0, sink.ends);
  }

  @Test
  void boundsCatchupAndRejectsClockRewindWithoutDestroyingPlayback() throws Exception {
    var source = new Source(movie(600, 0));
    try (var player = new RoqPlayback(source, RoqPlayback.Mode.HOLD, true, new Sound())) {
      player.advance(10000);
      assertTrue(player.catchingUp());
      assertTrue(player.video().orElseThrow().number() < 300);
      assertThrows(IllegalArgumentException.class, () -> player.advance(9999));
      player.advance(10000);
      assertFalse(player.catchingUp());
      assertEquals(300, player.video().orElseThrow().number());
      assertThrows(IllegalArgumentException.class, () -> player.advance(86_400_001));
    }
    assertEquals(source.opened, source.closed);
  }

  @Test
  void malformedEmptyAndReopenedMoviesReleaseStreamsAndAudio() throws Exception {
    for (byte[] invalid :
        new byte[][] {movie(0, 0), Arrays.copyOf(movie(2, 0), movie(2, 0).length - 1)}) {
      var source = new Source(invalid);
      var sound = new Sound();
      try (var player = new RoqPlayback(source, RoqPlayback.Mode.LOOP, false, sound)) {
        assertThrows(IOException.class, () -> player.advance(100));
        assertEquals(RoqPlayback.State.FAILED, player.state());
        assertTrue(player.video().isEmpty());
        assertThrows(IllegalStateException.class, () -> player.advance(100));
      }
      assertEquals(source.opened, source.closed);
      assertEquals(1, sound.ends);
    }
    var source = new Source(movie(1, 0));
    try (var player = new RoqPlayback(source, RoqPlayback.Mode.LOOP, true, new Sound())) {
      player.advance(0);
      source.bytes[6] = 60;
      assertThrows(IOException.class, () -> player.advance(34));
      assertEquals(source.opened, source.closed);
    }
  }

  @Test
  void sinkFailuresAndEarlyCancellationCloseAllOwnedResources() throws Exception {
    var source = new Source(movie(3, 2205));
    var sink =
        new Sound() {
          public void samples(RoqDecoder.Audio audio) {
            throw new IllegalStateException("sink failed");
          }
        };
    try (var player = new RoqPlayback(source, RoqPlayback.Mode.ONCE, false, sink)) {
      assertThrows(IllegalStateException.class, () -> player.advance(0));
      assertEquals(RoqPlayback.State.FAILED, player.state());
      assertEquals(1, source.closed);
      assertEquals(1, sink.ends);
    }
    source = new Source(movie(600, 0));
    var player = new RoqPlayback(source, RoqPlayback.Mode.LOOP, false, new Sound());
    player.advance(0);
    player.close();
    player.close();
    assertEquals(1, source.closed);
    assertThrows(IllegalStateException.class, () -> player.advance(0));
  }
}
