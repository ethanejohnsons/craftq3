package dev.bluevista.craftq3.engine.common.time;

/** A millisecond clock. Engine code never reads wall time directly so runs can be replayed. */
@FunctionalInterface
public interface Clock {
  /** Monotonic milliseconds since an arbitrary origin. */
  long millis();

  /** The host's monotonic clock. */
  static Clock system() {
    return () -> System.nanoTime() / 1_000_000L;
  }
}
