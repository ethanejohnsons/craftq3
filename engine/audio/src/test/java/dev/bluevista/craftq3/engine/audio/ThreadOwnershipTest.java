package dev.bluevista.craftq3.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ThreadOwnershipTest {
  @Test
  void callsFromAnotherThreadFail() throws Exception {
    assumeTrue(ThreadOwner.class.desiredAssertionStatus(), "ownership checks need -ea");
    var sound =
        new SoundSystem(
            new MemoryAssets(), new NullAudioDevice(4, false), SoundSystem.Limits.DEFAULT);
    sound.claimThread();
    sound.update(16);
    var failure =
        assertThrows(
            ExecutionException.class,
            () -> CompletableFuture.runAsync(() -> sound.update(16)).get());
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    var register =
        assertThrows(
            ExecutionException.class,
            () -> CompletableFuture.supplyAsync(() -> sound.registerSound("a.wav", false)).get());
    assertInstanceOf(IllegalStateException.class, register.getCause());

    sound.releaseThread();
    assertEquals(0, CompletableFuture.supplyAsync(sound::activeVoices).get());
    CompletableFuture.runAsync(() -> sound.update(16)).get();
  }

  @Test
  void aClosedSystemRejectsCalls() {
    var sound =
        new SoundSystem(
            new MemoryAssets(), new NullAudioDevice(4, false), SoundSystem.Limits.DEFAULT);
    sound.close();
    sound.close();
    assertThrows(IllegalStateException.class, () -> sound.update(16));
  }
}
