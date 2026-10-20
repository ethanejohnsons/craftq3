package dev.bluevista.craftq3.engine.render;

/** Rolling presentation intervals, independent of game ticks and camera delta clamping. */
public final class FrameTimings {
  private static final int WINDOW = 120;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private final long[] intervals = new long[WINDOW];
  private long previous;
  private boolean started;
  private int count;
  private int cursor;

  public void frame(long nowNanos) {
    if (started && nowNanos > previous) {
      intervals[cursor] = nowNanos - previous;
      cursor = (cursor + 1) % intervals.length;
      count = Math.min(count + 1, intervals.length);
    }
    previous = nowNanos;
    started = true;
  }

  public Snapshot snapshot() {
    if (count == 0) return new Snapshot(0, 0, 0);
    double total = 0;
    long worst = 0;
    for (int i = 0; i < count; i++) {
      total += intervals[i];
      worst = Math.max(worst, intervals[i]);
    }
    double ms = total / count / NANOS_PER_MILLI;
    return new Snapshot(1000 / ms, ms, worst / NANOS_PER_MILLI);
  }

  public record Snapshot(double fps, double meanMs, double worstMs) {}
}
