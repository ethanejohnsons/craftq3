package dev.bluevista.craftq3.engine.common.thread;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ThreadOwnerTest {
  @Test
  void rejectsForeignThreadsOnceClaimed() throws InterruptedException {
    ThreadOwner owner = new ThreadOwner("cvar table");
    assertDoesNotThrow(owner::check);
    owner.claim();
    assertDoesNotThrow(owner::check);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread other =
        new Thread(
            () -> {
              try {
                owner.check();
              } catch (Throwable t) {
                failure.set(t);
              }
            });
    other.start();
    other.join();
    assertInstanceOf(IllegalStateException.class, failure.get());
  }
}
