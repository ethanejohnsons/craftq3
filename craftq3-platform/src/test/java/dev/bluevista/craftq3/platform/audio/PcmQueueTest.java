package dev.bluevista.craftq3.platform.audio;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import java.io.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PcmQueueTest {
  private static final PcmStream.Format FORMAT = new PcmStream.Format(22050, 2, 16);

  private static PcmSound sound(int frames, int marker) {
    byte[] bytes = new byte[frames * 4];
    Arrays.fill(bytes, (byte) marker);
    return new PcmSound(22050, 2, 16, bytes);
  }

  @Test
  void queuesAcrossChunkBoundariesWithoutGapsOrDuplicatedSamples() throws Exception {
    var queue = new PcmQueue(FORMAT, 22050);
    var first = sound(701, 13);
    var second = sound(5513, 27);
    assertTrue(queue.offer(0, first));
    assertFalse(queue.ready());
    assertTrue(queue.offer(701, second));
    assertTrue(queue.ready());
    queue.finish();
    var expected = new ByteArrayOutputStream();
    expected.write(first.pcm());
    expected.write(second.pcm());
    var actual = new ByteArrayOutputStream();
    while (!queue.exhausted()) {
      byte[] block = queue.read(65537);
      assertEquals(0, block.length % 4);
      assertTrue(block.length <= 1102 * 4);
      actual.write(block);
    }
    assertArrayEquals(expected.toByteArray(), actual.toByteArray());
    assertEquals(6214, queue.consumedFrames());
    assertEquals(6214, queue.submittedFrames());
    assertEquals(0, queue.read(400).length);
    queue.close();
    assertThrows(IOException.class, () -> queue.read(400));
  }

  @Test
  void backpressureDoesNotConsumePositionAndRetrySucceeds() throws Exception {
    var queue = new PcmQueue(FORMAT, 6000);
    assertTrue(queue.offer(0, sound(6000, 1)));
    assertFalse(queue.offer(6000, sound(500, 2)));
    assertEquals(6000, queue.submittedFrames());
    queue.read(2000);
    assertTrue(queue.offer(6000, sound(500, 2)));
    assertEquals(6500, queue.submittedFrames());
    assertThrows(IllegalArgumentException.class, () -> queue.offer(6000, sound(1, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> queue.offer(6500, new PcmSound(22050, 1, 16, new byte[2])));
  }

  @Test
  void distinguishesShortFiniteStreamsUnderrunsAndCancellation() throws Exception {
    var queue = new PcmQueue(FORMAT, 6000);
    assertFalse(queue.ready());
    assertFalse(queue.exhausted());
    assertThrows(IOException.class, () -> queue.read(400));
    assertTrue(queue.offer(0, sound(3, 0)));
    queue.finish();
    assertTrue(queue.ready());
    assertThrows(IllegalStateException.class, () -> queue.offer(3, sound(1, 0)));
    assertThrows(IllegalArgumentException.class, () -> queue.read(3));
    assertEquals(12, queue.read(14).length);
    assertTrue(queue.exhausted());
    queue.close();
    queue.close();
    assertEquals(0, queue.queuedFrames());
    assertFalse(queue.ready());
    assertThrows(IllegalStateException.class, queue::finish);
  }

  @Test
  void finishingWakesAWorkerWaitingForCapacity() throws Exception {
    var queue = new PcmQueue(FORMAT, 6000);
    queue.offer(0, sound(6000, 1));
    var result = new java.util.concurrent.CompletableFuture<Throwable>();
    var worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    queue.put(6000, sound(500, 2));
                    result.complete(null);
                  } catch (Throwable error) {
                    result.complete(error);
                  }
                });
    try {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      while (worker.getState() != Thread.State.WAITING) {
        if (System.nanoTime() > deadline) fail("Producer did not wait for capacity");
        Thread.sleep(1);
      }
      queue.finish();
      assertInstanceOf(
          IllegalStateException.class, result.get(3, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(6000, queue.submittedFrames());
    } finally {
      queue.close();
      worker.interrupt();
    }
  }

  @Test
  void validatesFormatsAndCapacityBeforeAllocation() {
    assertThrows(IllegalArgumentException.class, () -> new PcmStream.Format(0, 1, 16));
    assertThrows(IllegalArgumentException.class, () -> new PcmStream.Format(22050, 3, 16));
    assertThrows(IllegalArgumentException.class, () -> new PcmStream.Format(22050, 1, 24));
    assertThrows(IllegalArgumentException.class, () -> new PcmQueue(FORMAT, 5511));
    assertThrows(IllegalArgumentException.class, () -> new PcmQueue(FORMAT, Integer.MAX_VALUE));
    var queue = new PcmQueue(FORMAT, 5512);
    assertEquals(FORMAT, queue.format());
    queue.finish();
    assertTrue(queue.exhausted());
    assertFalse(queue.ready());
  }
}
