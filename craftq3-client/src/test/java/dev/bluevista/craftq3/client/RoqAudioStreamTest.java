package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.client.video.RoqAudioStream;
import dev.bluevista.craftq3.platform.audio.PcmStream;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class RoqAudioStreamTest {
  private static final PcmStream.Format MONO = new PcmStream.Format(22050, 1, 16);

  private static void await(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= end) fail("Soundtrack worker timed out");
      Thread.sleep(1);
    }
  }

  @Test
  void largeAudioChunkCrossesBoundedQueueWithoutDroppingOrRepeatingSamples() throws Exception {
    byte[] movie = CinematicsTest.movie(450, 22050 * 9);
    try (var stream = new RoqAudioStream(() -> new ByteArrayInputStream(movie), MONO)) {
      int bytes = 0;
      while (!stream.exhausted()) {
        await(() -> stream.ready() || stream.exhausted());
        if (stream.exhausted()) break;
        var block = stream.read(10001);
        assertTrue(block.length > 0 && block.length <= 2204);
        for (byte value : block) assertEquals(0, value);
        bytes += block.length;
        assertTrue(stream.submittedFrames() - stream.consumedFrames() <= 22050 * 4);
      }
      stream.completion().get(5, TimeUnit.SECONDS);
      assertEquals(22050 * 9 * 2, bytes);
      assertEquals(22050 * 9, stream.submittedFrames());
      assertEquals(stream.submittedFrames(), stream.consumedFrames());
      assertTrue(stream.failure().isEmpty());
    }
  }

  @Test
  void shortSoundtrackFinishesBeforeItsLongSilentVideoTailIsPresented() throws Exception {
    byte[] movie = CinematicsTest.movie(300, 1102);
    try (var stream = new RoqAudioStream(() -> new ByteArrayInputStream(movie), MONO)) {
      stream.completion().get(5, TimeUnit.SECONDS);
      assertTrue(stream.producerFinished());
      assertTrue(stream.ready());
      assertEquals(2204, stream.read(10000).length);
      assertTrue(stream.exhausted());
      assertEquals(0, stream.read(10000).length);
    }
  }

  @Test
  void cancellationReleasesAWorkerBlockedOnBackpressureAndItsOwnedInput() throws Exception {
    byte[] movie = CinematicsTest.movie(450, 22050 * 9);
    var inputClosed = new AtomicBoolean();
    var input =
        new ByteArrayInputStream(movie) {
          @Override
          public void close() {
            inputClosed.set(true);
          }
        };
    var stream = new RoqAudioStream(() -> input, MONO);
    try {
      await(() -> stream.submittedFrames() == 22050 * 4);
      assertFalse(stream.completion().isDone());
    } finally {
      stream.close();
    }
    stream.completion().get(5, TimeUnit.SECONDS);
    assertTrue(inputClosed.get());
    assertTrue(stream.closed());
    assertTrue(stream.failure().isEmpty());
    assertFalse(stream.producerFinished());
    assertThrows(IOException.class, () -> stream.read(100));
  }

  @Test
  void decoderAndFormatFailuresReachTheConsumerInsteadOfHangingStartup() throws Exception {
    for (byte[] movie : new byte[][] {new byte[8], CinematicsTest.movie(3, 1102)}) {
      try (var stream =
          new RoqAudioStream(
              () -> new ByteArrayInputStream(movie), new PcmStream.Format(22050, 2, 16))) {
        stream.completion().get(5, TimeUnit.SECONDS);
        assertTrue(stream.ready());
        assertFalse(stream.exhausted());
        assertTrue(stream.failure().isPresent());
        assertThrows(IOException.class, () -> stream.read(100));
      }
    }
  }
}
