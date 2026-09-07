package dev.bluevista.craftq3.render;

/** Rolling presentation intervals; independent of game ticks and camera delta clamping. */
public final class FrameTimings {
  private final long[] intervals = new long[120];
  private long previous;
  private int count, cursor;

  public void frame(long nowNanos) {
    if (previous != 0 && nowNanos > previous) {
      intervals[cursor] = nowNanos - previous;
      cursor = (cursor + 1) % intervals.length;
      count = Math.min(count + 1, intervals.length);
    }
    previous = nowNanos;
  }

  public Snapshot snapshot() {
    if (count == 0) return new Snapshot(0, 0, 0);
    double total = 0;
    long worst = 0;
    for (int i = 0; i < count; i++) {
      total += intervals[i];
      worst = Math.max(worst, intervals[i]);
    }
    double ms = total / count / 1_000_000.0;
    return new Snapshot(1000 / ms, ms, worst / 1_000_000.0);
  }

  public record Snapshot(double fps, double meanMs, double worstMs) {}
}
