package dev.bluevista.craftq3.engine.common.time;

/** A clock advanced explicitly, for deterministic tests and replays. Not thread-safe. */
public final class ManualClock implements Clock {
  private long millis;

  public ManualClock(long start) {
    this.millis = start;
  }

  @Override
  public long millis() {
    return millis;
  }

  public void advance(long delta) {
    if (delta < 0) throw new IllegalArgumentException("Clocks never run backwards");
    millis += delta;
  }

  public void set(long value) {
    if (value < millis) throw new IllegalArgumentException("Clocks never run backwards");
    millis = value;
  }
}
