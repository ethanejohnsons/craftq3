package dev.bluevista.craftq3.fabric.audio;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioAssetCacheTest {
  private static PcmSound pcm(int value) {
    return new PcmSound(8000, 1, 8, new byte[] {(byte) value});
  }

  @Test
  void newFilesystemViewReplacesSameNameAndReclaimsRegistrationBudget() {
    var assets = new AudioAssetCache<Object>(1, 1, 8);
    var local = pcm(10);
    var approved = pcm(20);
    int handle = assets.register("Sound\\Menu.WAV", local);
    assertEquals(handle, assets.register("sound/menu.wav", approved));
    assertSame(local, assets.sound(handle));
    assertThrows(IllegalStateException.class, () -> assets.register("another.wav", approved));

    assets.reset();
    assertEquals(0, assets.sounds());
    assertThrows(IllegalArgumentException.class, () -> assets.sound(handle));
    int current = assets.register("sound/menu.wav", approved);
    assertSame(approved, assets.sound(current));
    assertEquals(20, assets.sound(current).pcm()[0]);
  }

  @Test
  void delayedCleanupDisposesOnlyItsOwnGenerationAfterRepeatedResets() {
    var assets = new AudioAssetCache<String>(2, 8, 12);
    var queue = new ArrayDeque<Runnable>();
    var disposed = new ArrayList<String>();
    int oldHandle = assets.register("effect.wav", pcm(1));
    assets.buffer(oldHandle, false, 4, () -> "old");
    var first = assets.reset();
    queue.add(() -> assets.discard(first, disposed::add));

    int intermediate = assets.register("effect.wav", pcm(2));
    assets.buffer(intermediate, false, 4, () -> "intermediate");
    var second = assets.reset();
    queue.add(() -> assets.discard(second, disposed::add));
    int current = assets.register("effect.wav", pcm(3));
    assets.buffer(current, false, 4, () -> "current");

    queue.remove().run();
    assertEquals(List.of("old"), disposed);
    assertEquals("current", assets.buffer(current, false));
    queue.remove().run();
    assertEquals(List.of("old", "intermediate"), disposed);
    assets.discard(first, disposed::add);
    assets.discard(second, disposed::add);
    assertEquals(2, disposed.size());
    assertEquals(1, assets.buffers());
  }

  @Test
  void retiredNativeResourcesRemainBudgetedUntilActuallyDisposed() {
    var assets = new AudioAssetCache<String>(2, 8, 6);
    int old = assets.register("old.wav", pcm(1));
    assets.buffer(old, false, 4, () -> "old");
    var retired = assets.reset();
    int current = assets.register("new.wav", pcm(2));
    var creations = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () -> assets.buffer(current, false, 3, () -> "new" + creations.incrementAndGet()));
    assertEquals(0, creations.get());
    assertEquals(0, assets.buffers());
    assertTrue(assets.ownsBuffers());
    assets.discard(retired, ignored -> {});
    assertFalse(assets.ownsBuffers());
    assertEquals(
        "new1", assets.buffer(current, false, 3, () -> "new" + creations.incrementAndGet()));
  }

  @Test
  void hostReloadDisposesActiveAndRetiredResourcesOnceButKeepsCurrentPcm() {
    var assets = new AudioAssetCache<String>(2, 8, 12);
    int old = assets.register("effect.wav", pcm(1));
    assets.buffer(old, false, 4, () -> "retired");
    var retired = assets.reset();
    var pcm = pcm(2);
    int current = assets.register("effect.wav", pcm);
    assets.buffer(current, false, 4, () -> "local");
    assets.buffer(current, true, 4, () -> "mono");
    var disposed = new ArrayList<String>();
    assets.discardAll(disposed::add);
    assertEquals(List.of("local", "mono", "retired"), disposed.stream().sorted().toList());
    assertFalse(assets.ownsBuffers());
    assertSame(pcm, assets.sound(current));
    assets.discard(retired, disposed::add);
    assertEquals(3, disposed.size());
    assertEquals("reloaded", assets.buffer(current, false, 12, () -> "reloaded"));
  }

  @Test
  void destroyedContextInvalidatesQueuedDisposalWithoutTouchingReplacementBuffers() {
    var assets = new AudioAssetCache<String>(2, 8, 4);
    int handle = assets.register("effect.wav", pcm(1));
    assets.buffer(handle, false, 4, () -> "destroyed");
    var retired = assets.reset();
    assets.forgetBuffers();
    int current = assets.register("effect.wav", pcm(2));
    assets.buffer(current, false, 4, () -> "new-context");
    assets.discard(retired, ignored -> fail("Stale OpenAL ID used after context destruction"));
    assertEquals("new-context", assets.buffer(current, false));
    assertTrue(assets.ownsBuffers());
  }

  @Test
  void failedCreationDoesNotConsumeNativeBudgetOrPublishBuffer() {
    var assets = new AudioAssetCache<String>(1, 1, 4);
    int handle = assets.register("effect.wav", pcm(1));
    assertThrows(
        IllegalStateException.class,
        () ->
            assets.buffer(
                handle,
                false,
                4,
                () -> {
                  throw new IllegalStateException("host allocation");
                }));
    assertFalse(assets.ownsBuffers());
    assertEquals("ok", assets.buffer(handle, false, 4, () -> "ok"));
    assertEquals(
        "ok",
        assets.buffer(
            handle,
            false,
            4,
            () -> {
              throw new AssertionError("Duplicate allocation");
            }));
  }
}
